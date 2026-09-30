# GenieX v22.4 native chat-template crash fix

## Crash root cause

The 2026-09-30 14:40:46 diagnostics record a native `SIGABRT` during the second chat request. Android's native tombstone reports an uncaught `std::invalid_argument` from `geniex::LlamaLlm::apply_chat_template()` with the Qwen Jinja error `Unexpected message role.` This is not an out-of-memory crash; the process had roughly 2.5 GiB system memory available immediately after model load.

The UI history limiter could remove the oldest `user` message by itself while retaining the paired `assistant` reply. A later prompt could therefore reach GenieX as `assistant, user`, which violates strict Qwen chat-template role ordering. The native template implementation aborts the process rather than safely returning that Jinja exception to Kotlin.

## Fixes

- LLM and VLM UI histories now trim complete oldest `user`/`assistant` turns rather than individual messages.
- A pure Kotlin `ChatRolePolicy` validates optional-leading-`system`, then alternating `user`/`assistant` roles, with generation requests ending in `user`.
- Immediately before every UI `applyChatTemplate()` call, malformed hidden history is repaired by retaining only the current user turn. This gives the UI a safe recovery path even if stale state originated in an older build.
- `InferenceBridge` validates and normalizes roles before JNI and validates again after history bounding.
- API history bounding also removes complete turns, so it cannot create an orphan assistant message.
- `/v1/chat/completions` validates roles before acquiring the inference slot or writing streaming headers. Unsupported/malformed role sequences return HTTP 400 `invalid_request_error` instead of reaching native code.
- A dedicated `InvalidChatSequenceException` remains as defense-in-depth inside `InferenceBridge`.

## Regression checks

- `ChatRolePolicy` compiled and passed valid/invalid sequence plus whole-turn-removal tests.
- `InferenceBridge` compiled against GenieX-0.4.0-compatible wrapper stubs and kotlinx-coroutines.
- All 31 Android XML files parse.
- `model_list.json` parses.
- GitHub Actions workflow YAML parses.
- Shell scripts pass `bash -n`.
- Kotlin local `R.*` references resolve.
- Manifest-owned app components resolve; external AndroidX provider declarations are excluded from that source check.
- Unused-import heuristic is clean after the change.
- Project-wide Kotlin compiler frontend scan reports no syntax/parser diagnostics; a full Android type/link build still requires Gradle + Android SDK.

## Build-environment limitation

The repository build script cannot run to completion in this sandbox because the Android Gradle toolchain is not installed. The GitHub Actions `lintDebug` / `assembleDebug` job remains the authoritative full build gate.
