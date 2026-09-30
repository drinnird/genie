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
- GenieX Android 0.4.0

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


## v12 build fix

- Declares Android 13+ `POST_NOTIFICATIONS` permission for the foreground local API server.
- Requests the permission when the user starts the server.
- Keeps the foreground service functional if the user denies notification permission.
- Guards subsequent `NotificationManager.notify()` updates with a runtime permission check, resolving the Android Lint `NotificationPermission` build failure without suppressing the rule.

## v13 workspace/startup safety

- Selecting a parent location now creates/uses a dedicated `Genie/` child.
- `Genie/models`, `Genie/aihub`, `Genie/logs`, `Genie/diagnostics`,
  `Genie/attachments`, and `Genie/temp` are created and write-tested before the
  selection is saved.
- Workspace activation was removed from `Application.onCreate()` so a stale or
  inaccessible external-storage path cannot crash-loop the app before recovery
  UI appears.
- Main launch is guarded. The pending flag is cleared only after the GenieX SDK
  reports successful initialization; if startup dies before then, the next app
  launch stays on workspace setup instead of immediately repeating the crash.
- GenieX SDK initialization failure now returns to workspace setup with the
  error preserved instead of leaving an unusable saved workspace.

## v14 startup crash fix

Fixed a startup race where `GenieXSdk.init()` could invoke `onSuccess()` synchronously
before `MainActivity.initView()` had initialized `tvSelectedModel` and other view fields.
This produced `UninitializedPropertyAccessException` immediately after workspace setup.
The UI is now initialized before the SDK, and asynchronous UI refresh entry points are
guarded until view binding is complete.


## v15 web auth and context-budget fixes

- Browser chat now detects when the server requires authentication and disables Send until an API key is entered.
- Missing Authorization headers return a clear `API key required` response instead of a generic timeout/rejection path.
- Browser requests default to 512 output tokens instead of requesting an entire 1024-token llama context.
- InferenceBridge tracks the loaded model's context budget, estimates prompt usage conservatively, reserves safety headroom, and caps output to what remains.
- Old chat turns are dropped automatically when needed to make room for the current prompt and response. The newest user message is never silently dropped.
- Raw completion prompts are tail-trimmed only when necessary to reserve a minimum response budget.
- Context-length failures now return a specific `context_length_exceeded` API error with actionable text.
- Native Android chat uses the same safe output-budget calculation.

## v16 transcript attachments and lecture summarization

- Android chat can attach multiple `.txt` transcript files in one selection.
- Attached files are copied with bounded streaming I/O into `Genie/documents/sources/`.
- Lecture Notes mode processes every attached file in selected order as one lecture, preserves file boundaries, summarizes bounded chunks, consolidates them hierarchically, and saves Markdown notes under `Genie/documents/summaries/`.
- If the Android message box is blank in Lecture Notes mode, the built-in lecture-summary prompt is used. A typed prompt overrides the preset.
- After a summary completes, the attachment mode switches to Ask Files. Questions use bounded local lexical retrieval so only relevant transcript excerpts are sent to the model.
- The web chat now supports multiple TXT uploads, Lecture Notes / Ask Files modes, progress reporting, and Stop during document processing.
- Web uploads use `POST /v1/files` and are streamed directly to disk with a 64 MB per-file limit rather than loaded into one Android heap buffer.
- New document API endpoints: `GET/POST /v1/files`, `POST /v1/documents/summarize`, and `POST /v1/documents/query`.
- Intermediate summary jobs are stored under `Genie/documents/jobs/` and cleaned after successful completion; stale jobs older than 24 hours are cleaned automatically.
- Document processing is globally serialized with native chat/API inference to avoid racing the same GenieX model handle.

## v17 compact header and model actions

- Removed the large selected-model control card from the chat screen.
- Added a compact GenieX header with the selected model and active compute unit directly below the title.
- Added a right-aligned gear menu with Manage models, Web server, and Diagnostics.
- Moved Stop next to the message composer so it remains available only while generation is active.
- Model cards now show Download only when a model is absent, Load when it is downloaded, and Unload when it is active.
- Loading from the Models screen now returns directly to chat and starts the existing backend-selection/load flow; it no longer merely updates a selection preference.
- When another model is active, other model Load buttons are disabled with an explicit unload-first status to preserve the clean-process switching protections.

## v18 startup crash fix

Fixed a runtime startup crash introduced by the transcript/UI refresh path:
`refreshDocumentUi()` could call `refreshSendButtonState()` before `btnSend`
was initialized. The app now binds all composer/model/status views before any
state refresh and adds defensive `lateinit`/Activity-lifecycle guards to the
refresh helpers.

## v19 persistent model-store fix

- Fixes the actual Qualcomm Android model-cache initialization order. `GenieXSdk.init()` in the pinned Android SDK initializes the native model manager with `context.filesDir/geniex`; setting `GENIEX_DATADIR` alone does not override that explicit path.
- Workspace setup now loads the GenieX JNI bridge and calls `ModelManagerWrapper.init(<selected Genie root>)` before any Activity calls `GenieXSdk.init()`.
- The native model store is first-initialization-wins, so the later SDK initialization receives its already-initialized result and keeps the persistent workspace instead of switching to app-private storage.
- The model manager receives the `Genie/` root, intentionally placing model data under `Genie/models/` and AI Hub metadata under `Genie/aihub/` using the SDK's own normal directory layout.
- Model-download completion now resolves the SDK-reported model path and verifies that it is physically inside `Genie/models/`; diagnostics record the resolved path and verification result.
- The Models screen now shows the exact model-storage path in addition to the workspace root.
- No legacy private-model migration is included because this revision follows a clean reinstall; previous app-private files are not assumed to exist.

## v20 standard Hugging Face downloads and audit hardening

- Replaced the failing native Hugging Face pull path for `llama_cpp`/GGUF catalog entries with a conventional HTTPS downloader shared by both the chat screen and Models screen.
- The downloader queries Hugging Face repository metadata, selects the configured GGUF quantization, supports split GGUF shards, and adds the preferred `mmproj-*.gguf` file for VLM entries.
- Large model files stream through a 256 KiB buffer into resumable `.part` files. Retry uses HTTP `Range`; completed parts are atomically renamed before import.
- Download staging lives under `Genie/temp/huggingface/`. After download, the local GGUF directory is registered through GenieX `HubSource.LOCALFS`; successful imports are verified under `Genie/models/` and staging is removed.
- Qualcomm AI Hub/QAIRT models continue to use the GenieX native pull path because they require SDK-managed compiled bundles and chipset metadata.
- Added storage preflight for the remaining transfer, the local-import cache copy, and 256 MiB headroom. Stale abandoned staging directories older than seven days are cleaned opportunistically.
- Added bounded Hugging Face metadata parsing and throttled progress updates to avoid unnecessary heap growth and UI churn.
- Fixed a download Retry/cancellation race where a cancelled job could clear the state of a newly-started replacement job.
- Model-manager initialization is now process-idempotent, avoiding repeated synchronous JNI initialization as Activities open.
- Diagnostic log truncation and workspace-log migration now stream data instead of loading multi-megabyte log files into the JVM heap; recent-log display reads only a bounded tail and diagnostic export streams `logcat` directly into the ZIP.
- Removed unreferenced legacy `FileContentActivity`, `KeyboardUtil`, `SharePreferenceKeys`, its layout/manifest entry, and unused drawable/color/string/style resources.
- Static re-audit passes: shell syntax, JSON parsing, all project XML parsing, manifest-to-class checks, local resource-reference checks, deleted-reference sweep, and Kotlin parser sweep. The new download coordinator also compiles in isolation against API-compatible Android/GenieX stubs and passes helper tests for quant matching, split-shard ordering, range parsing, and staging names.
- A full Android `lintDebug assembleDebug` still cannot be executed in this sandbox because Gradle/Android SDK are not installed and external binary downloads are unavailable here. The repository's GitHub Actions build remains the authoritative full dependency/Android compile and lint gate.


## v21 foreground persistent downloads

- Moves model-download ownership out of `MainActivity`/`ModelManagementActivity` into a dedicated Android `dataSync` foreground service. Switching apps, locking the screen, or removing the UI task no longer cancels an active model transfer.
- The foreground service owns a partial CPU wake lock, publishes progress/cancel notification state, and uses `START_REDELIVER_INTENT` so an OS-recreated service can restart the same request and resume its `.part` file.
- Public Hugging Face GGUFs now stream directly into `Genie/models/local/<model>/`; there is no duplicate persistent-cache copy and no native `LOCALFS` registration step. The runtime resolves and loads the verified GGUF path directly.
- The completed-file marker lists every required GGUF. Split models are considered available only when all expected shards exist; VLM availability also requires the selected `mmproj` file.
- Existing v20 full/partial files under `Genie/temp/huggingface/` are migrated/reused when possible rather than downloaded again.
- Transient transfer I/O failures retry up to five attempts with resumable HTTP `Range` requests and bounded backoff.
- Explicit in-app model deletion removes the direct persistent model directory, any legacy v20 staging files, and an older SDK-managed copy when present. Completed persistent models are never removed by stale-part cleanup.
- Manifest declares `FOREGROUND_SERVICE_DATA_SYNC`, the download service is non-exported and `stopWithTask=false`, and the existing All Files Access model-storage design remains intact.


## v22 Qualcomm Hugging Face NPU bundles

- Upgraded the Android GenieX dependency to `com.qualcomm.qti:geniex-android:0.4.0` and updated model-create call sites to the current Android `LlmCreateInput` / `VlmCreateInput` signatures. Thinking mode remains supplied through `applyChatTemplate(...)`.
- Added a dedicated `QUALCOMM_HF_QAIRT` catalog/download path for Qualcomm-published, precompiled GenieX QAIRT bundles. The app resolves the current package dynamically from each official Qualcomm Hugging Face repo's `release_assets.json` instead of pinning a release/S3 URL.
- Hardware selection is restricted to Snapdragon SM8750 and SM8850. The resolver prefers the current `*-for-galaxy` asset key and can fall back to the corresponding generic chipset key when Qualcomm's release metadata uses the older name.
- Enabled the new path for `Qwen3-4B-Instruct-2507` and added a separate `Qwen3-VL-4B-Instruct (Qualcomm NPU • W4A16)` entry. Existing GGUF entries and the older base `Qwen3-4B` AI Hub entry are otherwise unchanged.
- QAIRT packages use the existing foreground resumable HTTPS service. The transport ZIP is staged with `.part` resume support, safely extracted with zip-slip/entry-count/expanded-size guards, validated for `metadata.json` and compiled `.bin` files, and then removed after successful extraction.
- The extracted QAIRT bundle is the persistent authoritative copy under `Genie/models/qualcomm-qairt/`; it survives app uninstall/reinstall with the shared workspace and is deleted only by the explicit in-app Delete action.
- A completed bundle records the target chipset. A bundle copied from an incompatible chipset is not offered for loading, but its persistent files remain visible to the delete path so the user can explicitly remove and replace it.
- QAIRT memory preflight measures the whole extracted bundle directory instead of only its small tokenizer/metadata anchor file.
- Non-NPU-optimized downloads retain their v21 behavior.
