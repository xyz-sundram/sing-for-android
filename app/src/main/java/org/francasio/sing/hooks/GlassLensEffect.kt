package org.francasio.sing.hooks

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.min

/**
 * An original lens shader for Apple's existing, childless backdrop BlurViews.
 * Apply only to the background view: labels, artwork and controls stay sharp.
 *
 * Design references, not copied implementation:
 * https://developer.android.com/develop/ui/views/graphics/agsl
 * https://github.com/Kyant0/AndroidLiquidGlass
 *
 * Backdrop capture and scheduling remain owned by the host. This class performs
 * no capture, adds no animation callbacks, and retains one shader per surface.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassLensEffect {
    private data class Parameters(val width: Int, val height: Int, val density: Float, val dark: Boolean)

    private val shader = RuntimeShader(SHADER_SOURCE)
    private var parameters: Parameters? = null
    private var cachedEffect: RenderEffect? = null

    fun update(width: Int, height: Int, density: Float, dark: Boolean): RenderEffect {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val next = Parameters(width.coerceAtLeast(1), height.coerceAtLeast(1), safeDensity, dark)
        if (parameters == next) return checkNotNull(cachedEffect)

        val shortSide = min(next.width, next.height).toFloat()
        shader.setFloatUniform("resolution", next.width.toFloat(), next.height.toFloat())
        shader.setFloatUniform("cornerRadius", shortSide * 0.5f)
        shader.setFloatUniform("edgeWidth", min(12f * safeDensity, shortSide * 0.26f))
        shader.setFloatUniform("bend", min(7f * safeDensity, shortSide * 0.12f))
        shader.setFloatUniform("pixelDensity", safeDensity)
        shader.setFloatUniform("darkMode", if (dark) 1f else 0f)
        // RenderEffect captures the current shader uniforms. Rebuild when they
        // change, but reuse the same effect for subsequent identical layouts.
        cachedEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        parameters = next
        return checkNotNull(cachedEffect)
    }

    private companion object {
        val SHADER_SOURCE = """
            uniform shader backdrop;
            uniform float2 resolution;
            uniform float cornerRadius;
            uniform float edgeWidth;
            uniform float bend;
            uniform float pixelDensity;
            uniform float darkMode;

            float4 readBackdrop(float2 position) {
                float2 safePosition = clamp(position, float2(0.5), resolution - float2(0.5));
                return float4(backdrop.eval(safePosition));
            }

            float3 straightColor(float4 value) {
                return value.rgb / max(value.a, 0.0001);
            }

            half4 main(float2 position) {
                float2 local = position - resolution * 0.5;
                float2 q = abs(local) - (resolution * 0.5 - float2(cornerRadius));
                float2 corner = max(q, float2(0.0));
                float cornerLength = length(corner);
                float signedDistance = cornerLength + min(max(q.x, q.y), 0.0) - cornerRadius;
                float coverage = 1.0 - smoothstep(-0.8, 0.8, signedDistance);
                if (coverage <= 0.0) return half4(0.0);

                // Analytic rounded-rectangle normal; the straight edges never
                // divide by a zero corner length.
                float2 direction = sign(local);
                float2 normal = q.x > q.y ? float2(direction.x, 0.0) : float2(0.0, direction.y);
                if (cornerLength > 0.001) normal = direction * corner / cornerLength;

                float depth = max(-signedDistance, 0.0);
                float edge = 1.0 - smoothstep(0.0, max(edgeWidth, 0.001), depth);
                // Sample inward through the lens. Displacement is confined to
                // the rim, leaving the central backdrop stable when scrolling.
                float2 refracted = position - normal * bend * edge * edge;
                float dispersion = min(0.45 * pixelDensity, 1.2) * edge * edge;
                float4 centerSample = readBackdrop(refracted);
                float3 redSample = straightColor(readBackdrop(refracted + normal * dispersion));
                float3 blueSample = straightColor(readBackdrop(refracted - normal * dispersion));
                float3 middleSample = straightColor(centerSample);
                float3 color = float3(redSample.r, middleSample.g, blueSample.b);

                float luminance = dot(color, float3(0.2126, 0.7152, 0.0722));
                color = mix(float3(luminance), color, 1.10);
                float3 bodyTint = mix(float3(0.98, 0.985, 1.0), float3(0.055, 0.065, 0.085), darkMode);
                color = mix(color, bodyTint, mix(0.065, 0.10, darkMode));

                // A narrow directional highlight plus a weaker opposite lobe
                // gives the rim depth without painting an opaque white border.
                float2 light = normalize(float2(-0.65, -0.76));
                float facingLight = max(dot(normal, light), 0.0);
                float facingAway = max(dot(normal, -light), 0.0);
                float rimWidth = max(0.85 * pixelDensity, 0.8);
                float rim = 1.0 - smoothstep(0.0, rimWidth, depth);
                float normalZ = sqrt(max(0.0, 1.0 - 0.85 * 0.85 * edge * edge));
                float fresnel = pow(1.0 - normalZ, 3.0);
                color *= 1.0 - 0.075 * rim * facingAway;
                float highlight = rim * (0.055 + 0.24 * facingLight * facingLight)
                    + 0.055 * fresnel * facingLight;
                color = mix(color, float3(1.0), highlight);

                float alpha = centerSample.a * coverage;
                return half4(half3(clamp(color, float3(0.0), float3(1.0)) * alpha), half(alpha));
            }
        """.trimIndent()
    }
}
