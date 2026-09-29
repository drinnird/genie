# Custom GenieX Chat Android

Minimal customized Android source based on Qualcomm AI Hub Apps v0.37.2.

This repository intentionally contains only the files needed to build the customized GenieX Chat Android app and publish its APK with GitHub Actions.

## Automatic build

The workflow at `.github/workflows/build-geniex.yml` runs on every push to `main` and can also be started manually from the Actions tab.

A successful run:

1. Installs Java 17, Gradle 8.13, and the required Android SDK/NDK components.
2. Assigns a unique `versionCode` and `versionName` for every workflow attempt.
3. Runs `lintDebug` and `assembleDebug`.
4. Publishes the resulting APK to GitHub Releases.

## Android project

The app is located at:

`apps/geniex_chat_android`

The only repository-level shared build file required by the app is:

`apps/_shared/scripts/versions.env`

## Uploading to GitHub

Extract this ZIP and upload the *contents* of the extracted folder to the root of your GitHub repository. The repository root should contain `.github`, `apps`, `LICENSE`, and this README.
