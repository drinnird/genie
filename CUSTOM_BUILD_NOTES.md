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


## Lint manifest fix

Declared camera hardware as optional (`android.hardware.camera`, `required=false`) so the CAMERA permission does not imply a required hardware feature. Removed the redundant `android:extractNativeLibs` manifest attribute; legacy JNI packaging remains enabled through Gradle (`packaging { jniLibs.useLegacyPackaging = true }`).

## Browser chat and llama.cpp-style API

The local server now serves a lightweight, dependency-free browser chat UI at `/` while retaining API access to the same loaded GenieX model. The browser UI uses the same inference mutex and model instance as the Android chat, so it does not load a second copy of the model.

Supported server routes:

- `GET /` - browser chat UI
- `GET /health` and `GET /v1/health` - health check
- `GET /v1/models` and `GET /models` - loaded model metadata
- `POST /v1/chat/completions` - OpenAI-style chat completions, synchronous or SSE streaming
- `POST /v1/completions` - OpenAI-style raw prompt completions, synchronous or SSE streaming (LLM only)
- `POST /completion` - focused llama.cpp-style raw prompt completion (LLM only)

This is intentionally a focused compatibility layer rather than a full reimplementation of every llama-server endpoint. LAN inference requests continue to require a bearer API key. The web UI stores that key only in browser `sessionStorage` and does not persist conversation history.


## v4 model-load stability fix

Diagnostics from a Samsung Snapdragon device showed Android terminating the app
with `ApplicationExitInfo.REASON_LOW_MEMORY` while Qwen3.5 4B Q4_0 was being
reloaded on the GPU immediately after an NPU unload. The same model loaded and
ran on NPU successfully.

Changes:
- Qwen3.5 0.8B / 2B / 4B use the text-only LLM path by default; dedicated
  Qwen3-VL models remain available for image input. This avoids loading the
  additional `mmproj` vision projector and the larger VLM context for ordinary chat.
- GPU loads perform an available-memory preflight and fail gracefully with an
  actionable message instead of risking an Android low-memory process kill.
- Native model unload waits briefly for driver/native allocations to settle and
  logs post-unload available memory before another backend is loaded.

## v5 responsive UI / UX refresh

- Reworked the main model-control card so action buttons use weighted responsive rows and cannot overflow narrow phones.
- The Load button is hidden while a model is active; Unload becomes the primary action. Stop appears only while generation is actually running.
- The main composer is now a rounded Material card with a dedicated message field, primary Send action, and secondary attachment/clear controls.
- Updated chat bubbles, spacing, typography, surfaces, and app colors for a cleaner visual hierarchy.
- Refreshed Models, Server, and Diagnostics screens with Material cards and two-column responsive action rows.
- Changed image attachment controls to `GONE` for non-VLM models so unused controls no longer reserve layout space.
- The custom Material theme is now applied through the manifest, and the browser chat accent matches the Android UI.

Static audit for this revision:
- all Android XML files parse successfully
- all Kotlin `R.id` references resolve to defined resources
- model catalog JSON parses successfully
- GitHub Actions YAML parses successfully
- responsive control-state invariants verified in `MainActivity`

A full Android Gradle build still runs in GitHub Actions (`lintDebug` + `assembleDebug`) before any APK release is published.


## UI v6 resource-link fix

Removed unsupported `insetTop`/`insetBottom` style items from the custom Material button styles. These were causing `processDebugResources` to fail before Kotlin compilation. Button sizing remains controlled by `android:minHeight`, layout margins, and Material button padding.


## v7 model-switch stability

- Added controlled fresh-process restart before loading another model after a native runtime has been used.
- Pending model/backend selection survives the restart and resumes automatically.
- Added conservative per-model memory preflight for high-memory QAIRT 4B bundles.
- Added Qwen3 4B GGUF Q4_0 as a lower-risk NPU/GPU/CPU alternative to the QAIRT bundle.
- Reduced browser status polling from 5s to 15s and downgraded expected socket disconnects to informational logs.
- The 8 GiB QAIRT threshold is a safety policy based on observed LOW_MEMORY process kills in diagnostics, not an official Qualcomm minimum.


## v8 server reliability

Diagnostics from the LAN web chat showed the model process remained alive while HTTP
traffic stopped after the Android activity went to the background. Earlier sessions also
showed broken-pipe SSE disconnects and overlapping completion requests. v8 therefore:

- runs the local API as an Android foreground service, with CPU and Wi-Fi locks while enabled;
- allows only one inference request at a time and returns HTTP 429 to overlapping requests;
- adds `POST /v1/stop` and `/stop` to cancel the active stream;
- adds a Stop button and AbortController to the browser chat;
- avoids status polling during generation and polls every 30 seconds when idle;
- shortens idle client socket timeouts so abandoned browser connections cannot pin workers; and
- keeps six HTTP workers so health/model requests remain responsive during a generation.

## v9 workspace + model-management UX

- Added first-run `StartupActivity` for a persistent user-selected working directory.
- The app requests Android All files access because GenieX native runtimes require POSIX filesystem paths for model weights.
- Sets `GENIEX_DATADIR` before GenieX SDK/model-manager initialization, so models are stored under `<workspace>/models` and are rediscovered when the same workspace is selected after reinstall.
- Persistent workspace folders: `models/`, `aihub/`, `logs/`, `diagnostics/`, `attachments/`, and `temp/`.
- Diagnostics logs and exported ZIPs now live in the workspace. Sharing uses a temporary cache copy so FileProvider does not expose the whole workspace.
- Model cards no longer show Delete for unavailable models.
- RecyclerView change animation is disabled for the model list so download percentage updates do not flash/pulse the whole card.
- The main model control panel is collapsible/expandable and remembers its state.

Android removes app preferences and URI grants on uninstall. The shared workspace files remain, but after reinstall the user must select the same workspace again once. The model manager then detects its existing cache.


## v10 enhanced model-loader diagnostics

- Persistent model-load stage checkpoints are synchronously flushed before native runtime transitions.
- `logs/memory.csv` samples system/app/native/JVM memory every 500 ms while a model is loading.
- `logs/native-runtime.log` snapshots this app process' logcat approximately every two seconds during model initialization.
- `logs/model-loader.log` records resolved model paths, runtime/backend configuration, file sizes, and builder stages.
- `logs/api-server.log` isolates API/server chatter from the main app log.
- On the next launch, the most recent Android `ApplicationExitInfo` is correlated with the last persisted model-load stage.
- Exported diagnostic ZIPs include all of the above plus current logcat and process-exit traces.
- Prompt/response bodies and API credentials are not intentionally logged by app-level diagnostics. Native SDK log output is captured as emitted by the SDK/runtime.

## v11 Android memory / performance hardening

This revision is focused on reducing peak allocations and long-session memory growth on phones.
It also fixes the two nullable compute-unit Kotlin type errors found in the v10 GitHub Actions log.

- GGUF llama.cpp loads use conservative mobile settings instead of the SDK's larger defaults:
  `nCtx=1024`, adaptive `nBatch=128/256`, `nUBatch=64/128`, and host thread counts capped at 6.
  The smaller batch is selected when Android reports under 4 GiB of currently available RAM.
- Model loading refuses to start while Android is already reporting `lowMemory` pressure.
- CPU, GPU and NPU model loads retain the existing preflight / clean-process switching safeguards.
- Removed explicit `System.gc()` from model switching. Native/driver allocations are not Java-heap
  objects, and forced GC can add long pauses without releasing accelerator memory.
- Native chat history, API history, output token counts, and the visible Android transcript are bounded
  so long sessions cannot retain an ever-growing object graph.
- API request bodies are capped at 512 KiB and API responses are capped at 4096 generated tokens.
- SSE output is coalesced into short chunks instead of allocating/flushing JSON for every token.
- Streaming chat updates are throttled to ~80 ms and use RecyclerView payload updates; Markdown is
  rendered once when generation finishes rather than reparsed on every token.
- Markwon is shared per adapter instead of constructed for every assistant ViewHolder.
- Image attachment preprocessing uses sampled decoding before EXIF rotation/crop, JPEG quality 90,
  and chat thumbnails use sampled RGB_565 decoding rather than full-resolution bitmaps.
- Coroutine work is tied to a `SupervisorJob` and cancelled when MainActivity is destroyed.
- The API worker executor and cached web UI are released when the server stops or Android reports
  memory pressure. The pool is intentionally small (4 workers) because model inference itself is serialized.
- Diagnostics' high-frequency timestamp/proc parsing was tightened to reduce allocation overhead while
  preserving the 500 ms model-load memory trace.

Static validation for this revision includes Android XML/JSON parsing, resource-ID/manifest-class checks,
workflow YAML and shell syntax validation, Kotlin parser sweeps, and targeted compilation of the adaptive
performance settings. GitHub Actions remains the authoritative Android `lintDebug` + `assembleDebug` build.
