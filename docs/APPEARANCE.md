# Apple Music appearance options

Update both the patched Apple Music APK set and the matching Sing companion.
In **Sing → Apple Music Appearance**, enable **Apple red tint** and/or
**Liquid Glass**. Both default to off, keeping existing configurations intact.
Open Apple Music using the Music button. If an already open screen retains old
colors, force-stop Apple Music in Android settings and reopen it.

Apple red replaces the known light/dark blue accent colors, including selected
navigation icons and labels. Neutral text, album artwork, and unrelated colors
are preserved. Disabling the option restores tracked native tint lists; reopening
Apple Music refreshes any colors cached by the host before the switch changed.

## Glass rendering

Apple Music 7.0.0 (1614) already has separate, childless `BlurView` backgrounds
named `bottom_nav_blur` and `mini_player_blur`. The hook keeps their existing GPU
backdrop capture and native foreground controls, lowers the heavy native blur,
and applies a rounded lens only to those backgrounds. It removes the opaque
material fill above each active backdrop while preserving content, native
elevation, outlines, and hit targets. The fill is restored if glass is disabled
or the corresponding backdrop is unavailable.

On Android 13+, an original AGSL shader adds edge refraction, restrained RGB
dispersion, saturation/body tint, and directional rim/Fresnel highlights. It
caches one shader per surface and adds no screen capture or animation loop.
Android 11–12 retain native blur with a drawn rim; they do not run the lens shader.
Shader failures also fall back to the native background and rim.

Unknown layouts and backdrop views containing foreground children are left
intact. This is an Android rendering approximation, not Apple's private iOS
implementation. Real phone rendering and performance still need validation.

## Research and source choice

- [QWEA0/Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android)
  is a strong classic-View reference for SDF refraction, dispersion, and highlights,
  under MIT. Its full library introduces additional native blur and capture code
  that this hook does not need.
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
  is an Apache-2.0 Compose Multiplatform toolkit. Its components cannot directly
  replace backgrounds inside the host's existing classic Views.
- Android's [AGSL guide](https://developer.android.com/develop/ui/views/graphics/agsl)
  and [RuntimeShader reference](https://developer.android.com/reference/android/graphics/RuntimeShader)
  document the platform rendering API and Android 13 requirement.

The project uses its own shader and integration code; no third-party glass
library or shader source is copied or bundled. Reusing Apple's existing backdrop
capture avoids an additional snapshot pipeline, ancestor-capture recursion, and
extra work while the CPU is already running vocal separation.

## Validation

The original shader compiled and rendered in Skia 144.1. Eight light/dark and
size cases passed checks for transparent rounded corners, center coverage,
alpha preservation, and premultiplied color. This is a desktop SkSL validation,
not a substitute for the Android AGSL driver or host View hierarchy.

`artifacts/apple-music-appearance-preview.png` is a synthetic renderer preview;
it is explicitly labeled as such and is not an Apple Music phone screenshot.
Unit tests cover accent selection, disabled alpha preservation, background-only
targeting, and boolean settings validation.

The audio changes in the same build prevent inference underruns from restoring
unprocessed full-volume vocals. A configuration below 1× Real-Time still causes
buffering; the glass effect does not change inference performance. Two-Way Slider
Mode also reaches the original mix at 50%; disable it for a conventional vocal
gain slider spanning the full range.
