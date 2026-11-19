# THALA AI

THALA AI is an Android voice assistant with Tamil and English interaction, typed-chat fallback, and Android system voice output.

The Android Studio project source is being placed in the `android/` folder. Cloud chat sync will remain disabled by default; configure Firebase credentials locally and never commit them to this public repository.

## Build an APK

The **THALA AI Android APK** GitHub Actions workflow builds a debug APK from the project source and uploads it as the `THALA-AI-Android-APK` artifact after a successful run.

## Security

Do not commit passwords, private API keys, signing keys, or service-account files. Store any required build secrets in GitHub Actions Secrets.

## License

See [LICENSE](LICENSE).
