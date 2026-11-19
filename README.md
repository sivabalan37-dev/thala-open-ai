# THALA AI

THALA AI is an Android voice assistant with Tamil and English interaction, typed-chat fallback, and Android system voice output.

## Android Studio project

The complete Android Studio project is available directly in the `android/` folder. Open that folder in Android Studio.

The app uses Android speech recognition and the phone's installed text-to-speech voices. Language availability and offline speech support depend on the speech services and voice data installed on the device.

## Features

- Tamil and English voice interaction
- Typed input when speech recognition is unavailable
- Select installed system voices and adjust voice pitch and speed
- Open apps and perform supported phone actions, including timers, alarms, and calendar actions

## Cloud chat sync

Cloud chat sync is optional and disabled by default. The Firebase Web API key in `android/app/src/main/java/com/thala/assistant/CloudConfig.kt` has been cleared. To enable cloud sync, configure your own Firebase Project ID and Web API Key locally. Do not commit those values to this public repository.

## Build an APK

The **THALA AI Android APK** GitHub Actions workflow runs on pushes to `main` and can also be started manually:

1. Open the repository's **Actions** tab.
2. Select **THALA AI Android APK**.
3. Choose **Run workflow**.
4. After a successful run, download the `THALA-AI-Android-APK` artifact.

The workflow integrates the checked-in `VoiceEngine.kt`, applies the migration script, builds a debug APK, and uploads it as an artifact. The artifact is available only after the workflow succeeds.

## Permissions

Android may request microphone, contacts, phone, or calendar permissions when you use features that need them. Typed input remains available as a fallback.

## Security

Do not commit passwords, private API keys, signing keys, or service-account files. Store any required build secrets in GitHub Actions Secrets.

## License

See [LICENSE](LICENSE).
