# Target Lock Android v0.2

Android prototype for low-latency visual target lock and retention.

## Included
- Camera2 fullscreen landscape preview
- 640x360 Y-plane analysis stream
- tap-to-acquire grayscale template
- predicted-position template matching on every analysis frame
- adaptive search radius and template update
- constant-velocity 50 ms lead predictor
- confidence/lost-frame handling
- compact Russian HUD with tracker FPS and latency
- back-camera preference

## Build
JDK 17 + Android SDK 35 + Gradle 8.9 / Android Gradle Plugin 8.7.3:

gradle assembleDebug

APK: app/build/outputs/apk/debug/app-debug.apk
