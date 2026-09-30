# GenieX v22.3 audit

This revision focuses on correctness under long-running use, native-model concurrency, memory pressure, and removal of unreachable code.

## High-impact fixes

1. **Fresh context for Android UI chat** — the v22.2 reset fix covered `InferenceBridge`, but `MainActivity` streamed directly from the wrappers. The direct LLM/VLM paths now call `reset()` before each complete templated prompt, preventing native KV state from accumulating across turns.
2. **Cancellation-safe model mutex** — `MainActivity` no longer owns `InferenceBridge`'s mutex. `tryRunExclusive()` keeps lock acquire/release in one singleton scope and releases in `finally`, preventing Activity cancellation from stranding the model lock.
3. **Small transcript correctness** — TXT files shorter than the overlap window now emit one chunk instead of being silently treated as empty.
4. **Lower avoidable allocations** — document token budgeting no longer creates a dummy string, and recycled image holders release thumbnail view references sooner.
5. **Lower background power** — the local API service takes the high-performance Wi-Fi lock only when LAN serving is enabled.
6. **Bounded HTTP headers** — the local API now caps both header count and aggregate header bytes.

## Dead-code cleanup

Removed the unreachable camera popup/capture path, CAMERA manifest declarations, hidden native-test button/`ExecShell`, obsolete model-panel collapse state, unused audio field, unused image helper, and unused workspace helper methods. FileProvider remains because Diagnostics sharing still uses it.

## Validation boundary

The source passes structural/static checks and a targeted Kotlin compile for the inference bridge. This environment still does not contain the Android Gradle/SDK toolchain, so the repository's GitHub Actions `lintDebug assembleDebug` job remains the authoritative full Android compile/lint gate.

## Additional hardening from the final pass

- **Failed-turn rollback:** if chat templating, context budgeting, reset, or streaming fails before a completed generation, the just-added user turn is removed from the hidden native-history list. Retrying no longer compounds an invisible failed prompt.
- **Smarter transfer retries:** permanent HTTP client errors (for example 403/404) fail immediately instead of burning five retry/backoff cycles. Only timeout/rate-limit/server-status failures and transport I/O remain retryable.
- **Linear history trimming:** LLM, VLM, and visible transcript trimming calculate retained character totals once and decrement them as old turns are removed instead of repeatedly rescanning the whole history.
