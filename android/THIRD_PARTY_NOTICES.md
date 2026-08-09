# Android third-party notices

OpenTypeless Android 0.2 is an MIT-licensed clean-room implementation. It does not copy code or
bundle model weights from Typeless, Gboard, FUTO Voice Input, HeliBoard, Sayboard, whisperIME, or
Offline Voice Input.

The production APK uses only Android platform APIs. Speech selected as **Android on-device** or
**Android system service** is provided by the recognition service installed on the user's device;
that service and any language models it downloads have their own terms and privacy behavior.

Build and test dependencies are not embedded as application runtime code:

- Android Gradle Plugin and AndroidX Test — Apache License 2.0.
- JUnit 4 — Eclipse Public License 1.0.
- OkHttp MockWebServer — Apache License 2.0, test scope only.
- JSON-java — public domain, test scope only.

No speech or language model is bundled. Users who connect a self-hosted service or install/import a
model are responsible for that service or model's license and attribution requirements.
