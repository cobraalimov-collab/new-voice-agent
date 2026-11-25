# AI Phone Agent (Android, Kotlin)

Voice-controlled phone agent powered by the Anthropic Messages API.
Say **"agent" / "агент" / "yordamchi"** + a command; the agent reads the screen through an
Accessibility Service, asks Claude for the next action (max 15 steps), performs it, and speaks the result.

## Build the APK with GitHub Actions
1. Create a new GitHub repository and push the contents of this folder (the `.github` folder must be at the repo root).
2. Open the **Actions** tab -> **Build APK** -> *Run workflow* (it also runs on every push).
3. When it finishes, download the **app-debug** artifact -> unzip -> `app-debug.apk`.

No Gradle wrapper is needed: the workflow installs Gradle 8.7 + JDK 17 and runs `gradle assembleDebug`.
Local build (Android Studio): open the folder, let it sync, press Run.

## First run checklist
1. Install the APK, open it, paste your API key (`sk-ant-...`).
2. Tap **Grant** for Microphone and Notifications.
3. Accessibility: tap **Open**, enable *AI Phone Agent*.
   If the switch is greyed out (sideloaded app, Android 13+): **App info** -> menu (⋮) -> **Allow restricted settings**, then retry.
4. Choose a language and press **Test Voice & API** - you should hear Claude's reply within a few seconds.
   The log shows exactly which step failed (`[API]`, `[TTS]`, `[ERROR]`).
5. Press **Start Listening**, say e.g. *"agent, open YouTube"*.

## Notes
- Model: `claude-sonnet-5-5`, automatic fallback to `claude-sonnet-4-6` on HTTP 404.
- Uzbek TTS: if the `uz-UZ` voice is missing, speech falls back to Turkish (`tr-TR`), then Russian, then English.
- Speech recognition needs the Google app / Google Speech Services (internet) on the phone.
- Samsung: Settings -> Battery -> set this app to "Unrestricted" so the OS never freezes it.
- Licensing: `LicenseManager.kt` (`OWNER_BYPASS = true`). Set a `LicenseVerifier` and flip the flag for production.
