# FRAMEFORGE AI — Android v0.6.0

Gaming diagnostics and an experimental, app-owned image interpolation laboratory.

## Current capabilities
- Device report: Android version, model, memory snapshot, refresh rate and thermal status where exposed by Android.
- OpenGL ES renderer/vendor/version detection from the app's GL context.
- A 10-second GPU scene benchmark. Results describe this app's test scene, not a third-party game's FPS.
- Tap interval counter (not a measurement of hardware touch latency).
- Synthetic GPU motion-vector warp/blend demonstration.
- User-selected image pair interpolation using coarse CPU block matching. The estimator searches a small displacement window for each 8x8 block and warps two images toward an intermediate phase.
- Local game preference profiles; these do not modify external games.
- Arabic and English UI.

## Important limitations
- The image lab is a classical block-matching prototype, not a neural AI model and not dense optical flow.
- It processes two user-selected still images. It does not capture or inject frames into PUBG Mobile, Free Fire, Call of Duty Mobile, or other external games.
- The benchmark measures an app-owned OpenGL ES scene. It is not a game benchmark and does not guarantee higher game FPS.
- Resolution/aspect settings and Black Sky are app-side preferences/previews, not system-wide or external-game modifications.
- Android thermal and GPU telemetry availability varies by device/vendor.

## Build
Open the project in Android Studio with JDK 17 and Android SDK 35, or run:

```bash
./gradlew assembleDebug
./gradlew test
```

The repository's GitHub Actions workflow builds a debug APK and runs unit tests. A successful workflow is required before treating the APK as verified.

## Test device
Initial target: TECNO Spark Go 2024, Android 13, Unisoc T606, Mali-G57. Compatibility on other devices must be validated individually.
