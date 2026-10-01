# GenieX v22.10 diagnostic/lifecycle audit

## Diagnostic findings

The 2026-09-30 17:25 diagnostics contain a controlled screen-on/screen-off comparison for the same active `unsloth/Qwen3.5-4B-GGUF` Q4_0 model using `llama_cpp` with the pinned `npu`/`HTP0` compute path.

- Screen-on request: 17:14:41.312 to 17:16:35.978 (~114.7 s).
- Screen-off request: 17:20:26.826 to 17:24:11.377 (~224.6 s).
- Both requests were one user message, 269 request bytes, requested 2048 output tokens, estimated prompt size 120 tokens, and received an effective 1832-token response budget.
- The server service reported `wake=true`, `wifi=true`, `idle=false`, and `saver=false`; `batteryExempt=false`.

This establishes that the service was not simply going to sleep. The observed wall-clock duration was about 1.96x longer with the display off. Exact token/s cannot be computed from this archive because it was captured before a completed v22.9 stream produced the new per-stream character/piece counters.

The native logs also show the pinned HTP0 path uses six strict/polling CPU host threads and CPU fallback buffers. This makes screen-off CPU/DVFS policy a plausible contributor even when HTP/NPU work remains active.

## Confirmed duplicate-model lifecycle bug

At 17:25:13 the same Android process recreated `MainActivity` while a native model was already owned by `InferenceBridge`. The Activity-local flags started false, so the startup-restore code loaded Qwen3.5-4B a second time rather than reusing the existing wrapper.

Memory telemetry shows the impact:

- Available system memory before the duplicate load: ~3.00 GiB.
- Available system memory after the duplicate load: ~1.37 GiB.
- Native heap allocation rose from ~1.63 GiB to ~3.17 GiB.
- Process swap rose from ~490 MiB to ~2.01 GiB.

This can cause severe latency, swap churn, low-memory kills, and misleading screen-off performance results.

## v22.10 changes

- Added a process-wide active-model snapshot to `InferenceBridge`.
- A recreated `MainActivity` now adopts the existing LLM/VLM wrapper instead of loading the model again.
- VLM vision configuration is retained with the process-owned wrapper so image preprocessing remains correct after Activity recreation.
- The direct load path defensively adopts any process-owned model before deciding whether a user request is a new load or a model switch.
- Existing explicit model switching still performs the normal unload/clean-restart path.
- Added `Hybrid — Hexagon NPU + CPU scheduler` as a separate compute option for Q4 GGUF models. `NPU` retains its existing pinned-HTP0 meaning; the app does not silently change existing user choices.
- Remembered `hybrid` selections survive restart via the existing successful-model preference mechanism.

## Static checks

- 31 Android XML files parsed successfully.
- Model catalog JSON parsed successfully.
- GitHub Actions YAML parsed successfully.
- Shell scripts pass `bash -n`.
- Modified Kotlin files report no parser/syntax diagnostics when passed through `kotlinc`; unresolved Android/SDK symbols are expected without the Android/GenieX classpath.
- `rb_hybrid` is declared in the compute dialog and referenced through generated view binding.

A full Android Gradle build still requires the Android SDK/Gradle environment used by GitHub Actions.
