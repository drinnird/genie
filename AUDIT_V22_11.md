# GenieX v22.11 compute-switch / Android Markdown audit

## Runtime compute switching

The v22.10 loader already had the safe model-switch path but intentionally treated the same model ID as a no-op. v22.11 distinguishes **same model + same compute** from **same model + different compute**.

For a supported compute change, the app now:

1. refuses the change while document processing or generation is active;
2. acquires `InferenceBridge` exclusive ownership;
3. stops the local API listener if it is running;
4. stops and destroys the current LLM/VLM wrapper;
5. clears process-owned native model state and waits for driver/native allocations to settle;
6. records the requested model/backend as a pending safe-runtime load;
7. restarts the inference process; and
8. reloads the same model on the selected CPU/GPU/NPU/Hybrid backend, resuming the API server afterward when applicable.

A compute choice is persisted as the startup preference only after native model creation succeeds. If the replacement backend fails, the previous successful model/compute preference remains the last known-good startup target.

Two entry points expose the action for the active model: the active-compute status line in the chat header and a **Change compute** gear-menu item. Single-backend QAIRT entries do not expose a meaningless compute switch.

## Android Markdown rendering

Completed assistant messages were already rendered with Markwon, including the table, strikethrough, linkify, inline-parser, and LaTeX plugins. The audit found two practical rendering issues:

- a narrow 340dp assistant bubble made wide Markdown tables difficult to read;
- LLMs can emit nearly-valid tables with a malformed separator row, e.g. four pipe cells for a five-column table because `:--- :---` was accidentally joined, or literal trailing backslashes after table rows.

v22.11 adds a conservative `MarkdownNormalizer` before Markwon. It repairs those table-specific mistakes outside fenced code blocks and leaves ordinary prose/code unchanged. Assistant bubbles use the available row width instead of the former 340dp cap. Streaming remains plain text to avoid repeatedly parsing an ever-growing Markdown document on the UI thread; `Completed` triggers the full Markdown render.

The oxygen-mask table example supplied in chat is covered by a targeted Kotlin test and normalizes to a five-column separator row.

## Static / regression checks

- 31 Android XML files parse successfully.
- `model_list.json` parses successfully.
- GitHub Actions workflow YAML parses successfully.
- Project manifest component references resolve to source (external AndroidX provider excluded from project-source check).
- Kotlin/Java local `R.*` references resolve against project resources.
- Shell scripts pass `bash -n`.
- Kotlin PSI parser sweep: 32 Kotlin files, zero syntax diagnostics.
- `MarkdownNormalizer` compiles and passes targeted malformed-table + fenced-code preservation tests.
- `build.sh` was invoked; this sandbox still lacks Gradle/Android SDK and stops at `gradle: command not found`.
