package org.francasio.sing.provider

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Optional defaults supplied by scripts/patch_apple_music.py. User settings win. */
internal object PatchDefaults {
    private const val CONFIG = "sing_patch_defaults.json"
    private const val CATALOG = "models.json"
    private val applied = mutableSetOf<String>()

    internal fun parseSettings(json: String): Map<String, Any> {
        val root = JSONObject(json)
        require(root.getInt("schema_version") == 1) { "Unsupported patch configuration" }
        val settings = root.getJSONObject("settings")
        return settings.keys().asSequence().associateWith { key ->
            settings.get(key).also { require(SettingsValidation.isValidSetting(key, it)) { "Invalid patch setting: $key" } }
        }
    }

    @Synchronized
    fun apply(context: Context, preferences: String, moduleApkPath: String? = null) {
        val identity = "$preferences:${moduleApkPath ?: context.packageName}"
        if (identity in applied) return
        // The host's AssetManager belongs to Apple Music. Read the module ZIP explicitly.
        try {
            if (moduleApkPath != null) {
                ZipFile(moduleApkPath).use { zip ->
                    val entry = zip.getEntry("assets/$CONFIG") ?: return
                    val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    seed(context, preferences, json) {
                        zip.getEntry("assets/$CATALOG")?.let { catalog ->
                            zip.getInputStream(catalog).bufferedReader().use { it.readText() }
                        }
                    }
                }
            } else {
                val json = context.assets.open(CONFIG).bufferedReader().use { it.readText() }
                seed(context, preferences, json) {
                    context.assets.open(CATALOG).bufferedReader().use { it.readText() }
                }
            }
            applied.add(identity)
        } catch (_: java.io.FileNotFoundException) {
            // Regular rooted builds have no patch configuration.
            applied.add(identity)
        } catch (t: Exception) {
            Log.w("AppleSing_PatchDefaults", "Cannot load patch defaults", t)
        }
    }

    private fun seed(context: Context, preferences: String, json: String, catalog: () -> String?) {
        val settings = parseSettings(json)
        val prefs = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
        val cache = File(context.filesDir, CATALOG)
        if (!cache.exists()) {
            catalog()?.let { contents ->
                require(JSONObject(contents).has("models")) { "Invalid patch model catalog" }
                val temporary = File(context.filesDir, "models.patch.tmp")
                temporary.writeText(contents)
                check(temporary.renameTo(cache)) { "Cannot cache patch catalog" }
            }
        }
        val editor = prefs.edit()
        for ((key, value) in settings) {
            if (prefs.contains(key)) continue
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            }
        }
        // Commit before the caller immediately reads these preferences.
        check(editor.commit()) { "Cannot save patch defaults" }
    }
}
