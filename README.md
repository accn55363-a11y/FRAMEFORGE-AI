# FRAMEFORGE AI — Android v0.5.0

FRAMEFORGE AI is an Android gaming diagnostics and graphics research application. It does not claim to modify or inject frames into third-party games.

## Current capabilities
- Device report: Android version, model, available CPU cores, memory availability, display refresh rate and Android thermal status (where supported).
- OpenGL ES 2.0 GPU scene benchmark with vendor, renderer and GL version reporting. The reported FPS belongs to the app-owned benchmark scene only.
- Touch interval counter (not a hardware touch-latency measurement).
- Local game preference profiles (FPS preference, aspect ratio and Black Sky preference); these do not force changes in external games.
- GPU interpolation lab with two synthetic source images. The demo applies a known translation vector to warp source samples before blending at a user-selected phase. This is a controlled-scene motion-compensated blend, not general optical flow, trained AI, or system-wide frame generation.

## Build
Requires JDK 17 and Android SDK platform 35.

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Device limitations
Android apps generally cannot access another game's internal rendered frames or replace its presentation pipeline. Any system-wide or third-party-game frame generation requires supported OS/vendor integration and permissions. Results vary by GPU and thermal state.

## Test device
Initial target: TECNO Spark Go 2024 / Android 13 / Unisoc T606 / Mali-G57. Always validate on the actual device; emulator or CI success is not a substitute.
