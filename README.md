# CamFilter

A lightweight, open-source Android app that applies real-time video filters
(skin smoothing, brightness/contrast, grayscale/sepia) to the phone's camera
feed using CameraX + a custom OpenGL ES shader pipeline, plus an optional
floating "bubble" overlay preview.

## How the frame pipeline works

```
Camera sensor
   -> CameraX Preview use case
      -> Surface backed by a SurfaceTexture bound to an
         GL_TEXTURE_EXTERNAL_OES texture   (CameraFilterEngine.kt)
         -> GPU fragment shader applies brightness/contrast,
            box-blur skin smoothing, grayscale/sepia grade
            -> drawn to the on-screen GLSurfaceView
            -> (optional, throttled) glReadPixels -> Bitmap -> FrameBus
               -> floating overlay bubble (OverlayService.kt)
```

CameraX never touches a Bitmap or the CPU for the main preview path — frames
go sensor -> GPU texture -> shader -> screen, which is what makes 30-60fps
filtering possible on modest hardware. See the extensive comments in
`CameraFilterEngine.kt` for the exact mechanics (SurfaceTexture, the
`uSTMatrix` transform, and how each filter is implemented in GLSL).

## Project structure

```
CamFilter/
├── build.gradle.kts                  # root Gradle config
├── settings.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts              # CameraX + AndroidX deps
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml       # camera / overlay / FGS permissions
│       ├── java/com/opensource/camfilter/
│       │   ├── MainActivity.kt
│       │   ├── CameraFilterEngine.kt # OpenGL renderer / filter shaders
│       │   ├── CameraGLSurfaceView.kt
│       │   ├── FrameBus.kt           # in-process pub/sub for the bubble
│       │   └── OverlayService.kt     # floating bubble foreground service
│       └── res/
│           ├── layout/activity_main.xml
│           ├── layout/overlay_floating.xml
│           ├── values/{strings,colors,themes}.xml
│           └── drawable/*.xml
└── .github/workflows/android.yml     # CI: builds + publishes a GitHub Release
```

## Building locally

You need Android Studio (Koala+) or a JDK 17 + Android SDK command-line setup.

```bash
# From the project root, with Android Studio's bundled Gradle, or:
gradle assembleDebug
```

This repo intentionally does not commit a Gradle wrapper JAR (it's a binary
file). Either open the project in Android Studio once — it will offer to
generate `gradlew`/`gradlew.bat` for you — or run `gradle wrapper` yourself
locally. The GitHub Actions workflow doesn't need the wrapper at all; it
installs Gradle 8.7 directly via `gradle/actions/setup-gradle`.

## CI/CD: getting a downloadable APK from GitHub Actions

Push to `main` (or open a PR) and `.github/workflows/android.yml` will:

1. Set up JDK 17 + the Android SDK + Gradle 8.7 on a clean Ubuntu runner.
2. Run `gradle assembleDebug` and `gradle assembleRelease`.
3. Upload both APKs as a workflow artifact (visible under the run's
   **Artifacts** section — this works for PRs too).
4. On pushes to `main`, also publish them to a new **GitHub Release** tagged
   `build-<run number>`, so you get a stable download link without manually
   creating releases.

The release `buildType` is configured to sign with the Android debug keystore
so CI can produce an installable APK without you needing to store a real
signing key as a repository secret. Before distributing this anywhere beyond
your own devices/testers, generate a real keystore and point
`app/build.gradle.kts`'s `signingConfigs` / `buildTypes.release.signingConfig`
at it instead.

## About the floating overlay / "virtual camera" (please read)

The app can show a small draggable bubble on top of other apps
(`SYSTEM_ALERT_WINDOW` / `TYPE_APPLICATION_OVERLAY`), mirroring the filtered
feed a few times per second. That's a real, fully-permitted Android feature —
`OverlayService.kt` implements it, throttled and lightweight.

What it **cannot** do, and what no unprivileged, lightweight, sideloadable APK
can do on stock Android *or* on OEM skins like EMUI/HarmonyOS, MIUI, One UI,
etc: register itself as a system-wide virtual camera device so that some other
app (Zoom, WhatsApp, Google Meet, Instagram...) picks up your filtered stream
instead of the raw sensor. Android's Camera2/CameraX APIs don't expose a
mechanism for third-party apps to publish a new camera device, and there is no
cross-OEM equivalent of a Linux `v4l2loopback` virtual video driver available
to ordinary apps. In practice, people who want this either:

- **Root their device** and install a community virtual-camera Magisk module
  (this is a device-modification / root-tooling project in its own right, not
  something a lightweight app installed the normal way can do), or
- **Build the filter directly into their own video-calling app** using that
  app's SDK (which is what most "beauty mode" filters in WhatsApp/Instagram/
  TikTok actually are — an in-app pipeline, not a system camera replacement).

This project takes the honest, achievable path: a great in-app filtered
camera preview, plus a floating self-view bubble you can glance at while using
other apps — without pretending it can silently replace your camera system-wide.

## License

Add whichever open-source license you prefer (MIT/Apache-2.0 are common
choices) as `LICENSE` before publishing.
