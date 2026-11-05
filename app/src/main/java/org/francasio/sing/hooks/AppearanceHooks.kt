package org.francasio.sing.hooks

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.util.ReflectionUtils
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.WeakHashMap

/** Style native Apple Music Views; never capture the screen or process foreground controls. */
internal class AppearanceHooks(
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit
) {
    private data class TintState(val text: ColorStateList? = null, val image: ColorStateList? = null,
        val navText: ColorStateList? = null, val navIcons: ColorStateList? = null)
    private class GlassState(val controller: WeakReference<Any>, val radius: Float?, val overlay: Int?,
        val foreground: Drawable?) {
        var lens: GlassLensEffect? = null
        var rim: Drawable? = null
        var shaderUnavailable = false
        var nativeTuningUnavailable = false
    }
    private val tinted = WeakHashMap<View, TintState>()
    private val glass = WeakHashMap<View, GlassState>()
    private data class SurfaceState(val background: Drawable?, val tint: ColorStateList?, val replacement: Drawable)
    private val surfaces = WeakHashMap<View, SurfaceState>()
    private val originalLists = java.util.Collections.synchronizedMap(WeakHashMap<ColorStateList, ColorStateList>())
    private val mappedLists = object : LinkedHashMap<ColorStateList, ColorStateList>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ColorStateList, ColorStateList>?): Boolean = size > 256
    }
    private val roots = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private var application: Application? = null
    private var currentActivity = WeakReference<Activity>(null)
    private var scheduled = false

    fun install(register: (Executable, (XposedInterface.Chain) -> Any?) -> Unit) {
        try {
            ReflectionUtils.findMethod(Resources::class.java, "getColor", Int::class.javaPrimitiveType!!, Resources.Theme::class.java)?.let { method ->
                register(method) { chain ->
                    val result = chain.proceed()
                    if (SingSettings.appleRedTint && isHostResource(chain.thisObject as Resources, chain.getArg(0) as Int)) {
                        AppearancePolicy.tint(result as Int)
                    } else result
                }
            }
            ReflectionUtils.findMethod(Resources::class.java, "getColorStateList", Int::class.javaPrimitiveType!!, Resources.Theme::class.java)?.let { method ->
                register(method) { chain ->
                    val result = chain.proceed()
                    if (SingSettings.appleRedTint && isHostResource(chain.thisObject as Resources, chain.getArg(0) as Int)) {
                        tintList(result as ColorStateList)
                    } else result
                }
            }
            logInfo("Installed Apple Music accent color hooks")
        } catch (t: Throwable) { logError("Could not install appearance color hooks", t) }
    }

    fun bind(app: Application) {
        if (application != null) return
        application = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
                val reference = WeakReference(activity)
                val listener = ViewTreeObserver.OnGlobalLayoutListener { reference.get()?.let { schedule(it) } }
                roots.remove(activity)?.let { activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
                roots[activity] = listener
                activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(listener)
                schedule(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                roots.remove(activity)?.let { listener ->
                    val observer = activity.window.decorView.viewTreeObserver
                    if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
                }
                if (currentActivity.get() === activity) currentActivity.clear()
            }
            override fun onActivityDestroyed(activity: Activity) { roots.remove(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        })
    }

    fun onSettingsChanged() { currentActivity.get()?.let { schedule(it) } }

    private fun schedule(activity: Activity) {
        if (scheduled) return
        scheduled = true
        val reference = WeakReference(activity)
        activity.window.decorView.post {
            scheduled = false
            reference.get()?.takeUnless { it.isDestroyed }?.let { applyTree(it.window.decorView) }
        }
    }

    private fun applyTree(root: View) {
        try {
            val queue = java.util.ArrayDeque<View>()
            queue.add(root)
            while (!queue.isEmpty()) {
                val view = queue.removeFirst()
                updateTint(view)
                updateGlass(view)
                updateSurface(view)
                if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
            }
        } catch (t: Throwable) { logError("Could not refresh Apple Music appearance", t) }
    }

    private fun isHostResource(resources: Resources, id: Int): Boolean = try {
        resources.getResourcePackageName(id) == "com.apple.android.music"
    } catch (_: Resources.NotFoundException) { false }

    @Synchronized
    private fun tintList(input: ColorStateList): ColorStateList {
        val source = originalLists[input] ?: input
        mappedLists[source]?.let { return it }
        if (!source.isStateful) {
            val color = AppearancePolicy.tint(source.defaultColor)
            if (color == source.defaultColor) return source
            return ColorStateList.valueOf(color).also { originalLists[it] = source; mappedLists[source] = it }
        }
        val enabled = android.R.attr.state_enabled
        val checked = android.R.attr.state_checked
        val selected = android.R.attr.state_selected
        val pressed = android.R.attr.state_pressed
        val activated = android.R.attr.state_activated
        val states = arrayOf(intArrayOf(-enabled), intArrayOf(enabled, checked, pressed), intArrayOf(enabled, checked),
            intArrayOf(enabled, selected, pressed), intArrayOf(enabled, selected), intArrayOf(enabled, activated, pressed),
            intArrayOf(enabled, activated), intArrayOf(enabled, pressed), intArrayOf(enabled, android.R.attr.state_focused),
            intArrayOf(enabled, android.R.attr.state_hovered), intArrayOf())
        var changed = false
        val colors = IntArray(states.size) { i ->
            val old = source.getColorForState(states[i], source.defaultColor)
            AppearancePolicy.tint(old).also { if (it != old) changed = true }
        }
        if (!changed) return source
        return ColorStateList(states, colors).also { originalLists[it] = source; mappedLists[source] = it }
    }

    private fun updateTint(view: View) {
        if (!SingSettings.appleRedTint) {
            tinted.remove(view)?.let { restoreTint(view, it) }
            return
        }
        val old = tinted[view] ?: run {
            fun original(list: ColorStateList?) = list?.let { originalLists[it] ?: it }
            fun changed(list: ColorStateList?): ColorStateList? = original(list)?.takeIf { tintList(it) !== it }
            val nav = view.javaClass.name.endsWith("BottomNavigationView")
            TintState(
                text = changed((view as? TextView)?.textColors),
                image = changed((view as? ImageView)?.imageTintList),
                navText = if (nav) changed(ReflectionUtils.callMethod(view, "getItemTextColor") as? ColorStateList) else null,
                navIcons = if (nav) changed(ReflectionUtils.callMethod(view, "getItemIconTintList") as? ColorStateList) else null
            ).takeIf { it.text != null || it.image != null || it.navText != null || it.navIcons != null }
                ?.also { tinted[view] = it } ?: return
        }
        if (view is TextView) old.text?.let { list ->
            val mapped = tintList(list)
            if (view.textColors !== mapped) view.setTextColor(mapped)
        }
        if (view is ImageView) old.image?.let { if (view.imageTintList !== tintList(it)) view.imageTintList = tintList(it) }
        old.navText?.let { if (ReflectionUtils.callMethod(view, "getItemTextColor") !== tintList(it)) ReflectionUtils.callMethod(view, "setItemTextColor", tintList(it)) }
        old.navIcons?.let { if (ReflectionUtils.callMethod(view, "getItemIconTintList") !== tintList(it)) ReflectionUtils.callMethod(view, "setItemIconTintList", tintList(it)) }
    }

    private fun restoreTint(view: View, old: TintState) {
        if (view is TextView) old.text?.let { view.setTextColor(it) }
        if (view is ImageView) old.image?.let { view.imageTintList = it }
        old.navText?.let { ReflectionUtils.callMethod(view, "setItemTextColor", it) }
        old.navIcons?.let { ReflectionUtils.callMethod(view, "setItemIconTintList", it) }
    }

    private fun updateGlass(view: View) {
        val existing = glass[view]
        if (!SingSettings.liquidGlass) {
            if (existing != null) {
                if (Build.VERSION.SDK_INT >= 31) view.setRenderEffect(null)
                if (view.foreground === existing.rim) view.foreground = existing.foreground
                existing.controller.get()?.let { controller ->
                    existing.radius?.let { ReflectionUtils.setFloatField(controller, "k", it) }
                    existing.overlay?.let { ReflectionUtils.setIntField(controller, "j", it) }
                    try { ReflectionUtils.callMethod(controller, "a") } catch (_: ReflectiveOperationException) {}
                }
                glass.remove(view)
            }
            return
        }
        if (view.width <= 0 || view.height <= 0) return
        val name = try { view.resources.getResourceEntryName(view.id) } catch (_: Resources.NotFoundException) { return }
        if (!AppearancePolicy.isGlassBackground(name, (view as? ViewGroup)?.childCount ?: 0)) return
        if (view.javaClass.name != "eightbitlab.com.blurview.BlurView") return
        val controller = ReflectionUtils.getObjectField(view, "a") ?: return
        // These fields belong to the supplied 7.0.0 native BlurView controller.
        // If the implementation differs, retain the stock capture/blur instead.
        val radiusField = ReflectionUtils.findField(controller.javaClass, "k")?.takeIf { it.type == Float::class.javaPrimitiveType }
        val overlayField = ReflectionUtils.findField(controller.javaClass, "j")?.takeIf { it.type == Int::class.javaPrimitiveType }
        val state = existing ?: GlassState(WeakReference(controller), radiusField?.getFloat(controller), overlayField?.getInt(controller), view.foreground).also {
            glass[view] = it
            logInfo("Applying liquid glass to $name (API ${Build.VERSION.SDK_INT}, background only)")
        }
        if (radiusField != null && overlayField != null && !state.nativeTuningUnavailable) {
            val targetRadius = 2f // Native GPU scale is 4: retain detail for the lens instead of a 64px blur.
            val color = state.overlay ?: 0
            val targetOverlay = (minOf(color ushr 24, 36) shl 24) or (color and 0x00FFFFFF)
            try {
                if (radiusField.getFloat(controller) != targetRadius || overlayField.getInt(controller) != targetOverlay) {
                    radiusField.setFloat(controller, targetRadius)
                    overlayField.setInt(controller, targetOverlay)
                    ReflectionUtils.callMethod(controller, "a")
                }
            } catch (t: ReflectiveOperationException) {
                state.nativeTuningUnavailable = true
                logError("Retaining native backdrop blur settings", t)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !state.shaderUnavailable) {
            try {
                val lens = state.lens ?: GlassLensEffect().also { state.lens = it }
                val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                view.setRenderEffect(lens.update(view.width, view.height, view.resources.displayMetrics.density, dark))
            } catch (t: RuntimeException) {
                state.shaderUnavailable = true
                state.lens = null
                logError("Glass lens unavailable; using native blur and rim", t)
                view.setRenderEffect(null)
                applyRim(view, state)
            }
        } else applyRim(view, state)
    }

    private fun applyRim(view: View, state: GlassState) {
        if (state.rim != null) return
        val rim = GlassRimDrawable(view.resources.displayMetrics.density)
        state.rim = state.foreground?.let { LayerDrawable(arrayOf(it, rim)) } ?: rim
        view.foreground = state.rim
    }

    private fun updateSurface(view: View) {
        val name = try { view.resources.getResourceEntryName(view.id) } catch (_: Resources.NotFoundException) { return }
        val backdropName = when (name) {
            "bottom_nav_view" -> "bottom_nav_blur"
            "mini_player_content" -> "mini_player_blur"
            else -> return
        }
        val id = view.resources.getIdentifier(backdropName, "id", "com.apple.android.music")
        val backdrop = if (id != 0) view.rootView.findViewById<View>(id) else null
        val hasBackdrop = backdrop != null && backdrop.isShown && backdrop.alpha > 0f && glass.containsKey(backdrop)
        if (!SingSettings.liquidGlass || !hasBackdrop) {
            surfaces.remove(view)?.let { old ->
                if (view.background === old.replacement) {
                    view.background = old.background
                    view.backgroundTintList = old.tint
                }
            }
            return
        }
        if (surfaces.containsKey(view)) return
        // Native bg_apple_material has opaque fill layers above the separate
        // BlurView. Remove only that fill so it cannot conceal the lens below;
        // controls, their hit regions, native elevation, and artwork stay intact.
        val rim = GlassRimDrawable(view.resources.displayMetrics.density)
        surfaces[view] = SurfaceState(view.background, view.backgroundTintList, rim)
        view.backgroundTintList = null
        view.background = rim
    }
}

private class GlassRimDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        rect.inset(density * 0.5f, density * 0.5f)
        paint.shader = LinearGradient(0f, rect.top, rect.width() * 0.35f, rect.bottom,
            intArrayOf(0x80FFFFFF.toInt(), 0x10FFFFFF, 0x40202030), null, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        val radius = rect.height() * 0.5f
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, bounds.height() * 0.5f) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
