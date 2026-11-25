# FRAMEFORGE AI — Android Foundation v0.1.0

Native Android starter project using Kotlin + Jetpack Compose.

## Current implemented features
- Native Android application structure.
- Arabic/English interface toggle.
- Device report: model, Android version, CPU core count, reported RAM, display refresh rate and Android thermal status.
- Locally saved game-name profiles.
- Target FPS and aspect-ratio profile preferences.
- Black Sky preview inside the app only.
- Frame-generation capability explanation and safe compatibility-first UI.

## Important technical limits
This foundation build does **not** generate frames inside third-party games. Ordinary Android apps do not have a public system API to intercept another game's rendered frames and inject interpolated frames into its presentation pipeline. Real frame generation needs supported GPU processing, a permitted compositor/game integration, and device-specific validation.

The Black Sky switch is an in-app preview only. It does not alter external games. The FPS and aspect-ratio controls save profile preferences; they do not force unsupported settings into games.

Aim assistance is limited to legitimate touch practice and sensitivity guidance. No automated aiming, game tampering, anti-cheat bypass, overclocking, or thermal protection bypass is included.

## Build
Requires JDK 17 and Android SDK platform 35.

```bash
gradle assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions
Upload this folder to a GitHub repository. The workflow at `.github/workflows/android.yml` builds a debug APK and uploads it as an Actions artifact.
