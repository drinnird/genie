# GenieX v22.7 web-server Qwen3.5 native chat-template crash audit

## Root cause

Diagnostics from the 2026-09-30 web-server crash show the process aborting with native `SIGABRT` while GenieX 0.4.0 is inside llama.cpp chat-template handling. The native exception is `std::invalid_argument` with `Unable to generate parser for this template` / `Jinja Exception: Unexpected message role`, and the stack reaches `geniex::LlamaLlm::apply_chat_template()` from the local `/v1/chat/completions` worker. The first request completed; the second multi-turn request reached native templating and aborted the process.

The request-role validator added in v22.4 remains useful, but this crash demonstrates that a valid OpenAI-style text history can still trigger llama.cpp's automatic Jinja parser for Qwen3.5. Because the exception crosses the GenieX JNI boundary as a process abort, Kotlin exception handling cannot recover after the native call begins.

## Fix

- Added a narrow Kotlin text-only ChatML renderer for **Qwen3.5-family `llama_cpp` models only**.
- `/v1/chat/completions` and the Android text-chat path now use that renderer for Qwen3.5 instead of calling native `applyChatTemplate()`.
- Historical completed Qwen3.5 `<think>...</think>` reasoning is stripped from prior assistant turns, matching the upstream text-template behavior, while the visible assistant answer is retained.
- `enable_thinking=false` emits the Qwen3.5 empty-think preamble; `enable_thinking=true` emits the normal opening think marker for the new assistant turn.
- Qwen3 (non-3.5), QAIRT, VLM media prompts, and unknown/future model families retain the existing native template path. This keeps the workaround scoped to the demonstrated failing family/runtime rather than globally replacing GenieX templating.
- The inference bridge now records the active runtime explicitly, so safe-template selection cannot accidentally apply to a QAIRT model with a similar name.
- API diagnostics now record message count, normalized roles, streaming/thinking flags, and runtime without logging message content.
- `/health` now reports the active runtime.

## Regression protection

Targeted Kotlin tests verify:

1. Qwen3.5 + `llama_cpp` uses the Kotlin renderer and makes **zero** native `applyChatTemplate()` calls for a `user -> assistant -> user` history.
2. Qwen3 + `llama_cpp` retains native templating.
3. Qwen3.5 + `qairt` retains native templating.
4. Invalid assistant-first histories are rejected before rendering.
5. Completed historical reasoning is removed while the visible assistant answer remains.
6. Both thinking-enabled and thinking-disabled generation preambles match the supported Qwen3.5 text-template behavior.

## Static audit

- Android XML parse: pass (31 files)
- model catalog JSON parse: pass
- GitHub workflow YAML parse: pass
- shell `bash -n`: pass
- local Android resource-reference scan: pass
- manifest-to-local-component scan: pass
- project Kotlin parser sweep: pass (32 Kotlin files, zero parser diagnostics)
- unused Kotlin import heuristic: pass
- all `InferenceBridge.setLlm` / `setVlm` call sites updated with runtime id: pass
- native `applyChatTemplate()` call-site review: remaining calls are deliberate fallback/media paths; Qwen3.5 llama.cpp text chat bypasses them before JNI

## Full build boundary

The project `build.sh` was invoked, but this execution environment has no Gradle/Android SDK tooling and stops at `gradle: command not found`. The repository GitHub Actions `lintDebug assembleDebug` job remains the authoritative full dependency-aware Android/Kotlin build and lint gate.
