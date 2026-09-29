# GenieX Android custom build notes

Base: Qualcomm AI Hub Apps 0.37.2 (`apps/geniex_chat_android`).

## Included customizations

- Dedicated model-management screen with downloaded, selected, active, downloading, and failed states.
- Qwen-only catalog. Existing Qwen3 entries are retained; Qwen3.5 4B and Huihui Qwen3.5 2B/4B entries are added.
- Compute-unit policy used by the UI:
  - QAIRT: NPU only.
  - GGUF Q4*: NPU, GPU, or CPU selectable when loading.
  - Other GGUF quantizations: GPU or CPU selectable when loading.
- The active-model banner records and displays the requested compute unit.
- In-process OpenAI-style API server with `/v1/models` and `/v1/chat/completions`.
- Server screen displays localhost, LAN address, port, and `/v1` base URL. LAN mode requires an API key.
- UI and API inference share one loaded native model and one inference mutex to avoid duplicate model memory and concurrent access to a native handle.
- Model unload is blocked while inference owns the native handle.
- Rolling private diagnostics, model-load checkpoints, historical process-exit information, native exit trace export when available, and diagnostic ZIP save/share.
- Generated chat tokens are not written to app logcat by the modified UI.
- Previous interrupted model load is detected on next app launch and does not auto-reload the model.
- GitHub Actions builds on every push to `main` and on manual dispatch, runs `lintDebug assembleDebug`, increments Android `versionCode` and `versionName` for every workflow attempt, and publishes the APK to GitHub Releases only after a successful build.

## Build/toolchain audit

The uploaded 0.37.2 source pins:

- Android Gradle Plugin 8.13.0
- Kotlin 2.2.0
- Java 17
- compileSdk 34
- targetSdk 34
- minSdk 31
- NDK 27.3.13750724
- GenieX Android 0.3.5

The included GitHub Actions workflow uses Gradle 8.13 and installs Android platform 34, Build Tools 35.0.0, and NDK 27.3.13750724.

Static audit performed before packaging:

- XML parse: pass
- JSON parse: pass
- Manifest activity/source references: pass
- ViewBinding/resource ID cross-check for changed/new screens: pass
- Qwen-only model catalog / required model repo+quant checks: pass
- Compute-unit policy source check: pass
- Web API endpoint presence check: pass
- Prompt/token log guard check: pass
- GitHub Actions YAML load and embedded Bash `bash -n`: pass
- Version patch logic smoke test: pass
- Kotlin parser-level syntax check on new Kotlin source: pass (Android/SDK symbol resolution requires a real Android/Gradle build)

## Important validation boundary

The current execution environment does not contain Gradle or an Android SDK and cannot resolve external Maven/Android downloads from the shell, so a full local `lintDebug assembleDebug` could not be executed here. The included GitHub Actions workflow performs that full build audit before a release is created. A failed lint/compile/build does not publish an APK.

## Runtime note

The Q4* => NPU eligibility is the requested product policy. Qualcomm's current documentation specifically identifies Q4_0 as the GGUF precision with best Hexagon NPU support; K-quants such as Q4_K_M are documented as typically GPU/CPU. For that reason the app records the requested backend and exports SDK/runtime diagnostics so actual behavior can be verified on-device.


## Standalone build fix

The project no longer imports `apps/_shared/scripts/versions.env` or
`_shared/android/common.gradle`. Android SDK/NDK versions are pinned directly
in `apps/geniex_chat_android/build.gradle`, preventing missing shared-file
failures in minimal GitHub repositories.
