# FRAMEFORGE AI Android v0.3.0

Native Android gaming diagnostics prototype built with Kotlin, Jetpack Compose and OpenGL ES 2.0.

## Implemented in this build
- Arabic / English interface.
- Device model, Android version, reported RAM, available logical CPU processors, display refresh rate and Android thermal status.
- GPU vendor, renderer and OpenGL version read from the app's active OpenGL context.
- Basic Vulkan feature-presence check (not a full Vulkan capability/benchmark implementation).
- OpenGL ES shader compile/link error checks.
- 10-second synthetic GPU workload benchmark with average FPS, average frame time and approximate 1% low derived from app render-loop intervals.
- GLSurfaceView lifecycle pause/resume handling.
- Local per-game preference profiles (FPS preference, aspect-ratio preference and Black Sky preference).
- Tap counter and inter-tap interval test.

## Important technical limits
- This is a diagnostics and prototype application, not a universal game optimizer.
- It cannot intercept or replace frames rendered by PUBG Mobile, Free Fire, Call of Duty Mobile or other third-party games. Standard Android applications do not receive a general public API for that.
- No true AI optical-flow frame generation is implemented yet. The GPU benchmark is a synthetic scene owned by this application.
- The 1% low value is an approximation based on this app's render-loop intervals, not a game capture measurement.
- FPS and aspect ratio in game profiles are saved preferences only; they do not change third-party game settings.
- Vulkan detection here checks feature declarations only; a real Vulkan renderer and device capability query remain future work.
- Black Sky is a preference label only, not a modification of external game graphics.
- Touch intervals are not hardware touch-to-photon latency.

## Build
Requires JDK 17, Android SDK 35 and Gradle 8.9. Run `gradle assembleDebug`. GitHub Actions builds and uploads a debug APK artifact.
