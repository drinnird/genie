# GenieX v22.8 web UI / long-response / screen-off audit

## Findings

1. The bundled browser chat hard-coded `max_tokens: 512` on every `/v1/chat/completions` request. This cleanly stops generation after roughly 512 output tokens even when the model could continue. Typing `continue` works because the previous assistant output is included in the next request history.
2. The HTTP server itself also defaulted omitted `max_tokens` to 512. The inference bridge already has a context-aware output budget and can safely reduce a larger request when prompt/context usage requires it.
3. The browser rendered assistant output with `textContent`, so Markdown appeared as literal punctuation.
4. The browser called `scrollBottom()` for every streamed chunk, forcing the viewport back to the newest token while the user tried to read earlier output.
5. The background server service already held a non-reference-counted `PARTIAL_WAKE_LOCK` for its lifetime and a `WIFI_MODE_FULL_HIGH_PERF` lock while LAN serving. Adding a second permanent lock would be redundant. Remaining screen-off throttling can come from Android/Samsung battery policies, power-saver state, or hardware governor behavior.

## Changes

- Browser/API default response request increased from 512 to the existing safe API maximum of 2048 tokens.
- `/health` and `/v1/models` now expose `max_output_tokens`; the web UI reads that value rather than hard-coding its own limit.
- The inference bridge still caps the effective response against the loaded context window and its existing safety reserve, so a long prompt can lower the actual output budget below 2048.
- Added a dependency-free safe Markdown renderer for assistant output. Raw model HTML is escaped first; supported Markdown includes headings, emphasis, strikethrough, inline/fenced code, lists, blockquotes, links, horizontal rules, and simple pipe tables. Links are restricted to HTTP/HTTPS/mailto.
- Streaming Markdown re-rendering is throttled to reduce browser CPU/layout overhead.
- Auto-scroll now follows output only while the user is already near the bottom. Scrolling upward disables follow mode until the user returns near the bottom or starts a new message.
- Added a Server-screen **Background power settings** control. Samsung devices open the documented Never sleeping apps screen when available; other devices fall back to Android battery-optimization settings.
- Server diagnostics now record requested output-token limit and screen/idle/power-saver/battery-exemption state for chat requests.
- Foreground-service startup diagnostics record whether the CPU wake lock and LAN Wi-Fi lock are actually held.

## Static regression checks

- Android XML parse: pass (31 files)
- model catalog JSON parse: pass
- GitHub workflow YAML parse: pass
- shell `bash -n`: pass
- browser JavaScript `node --check`: pass
- new ViewBinding IDs resolve to XML resources: pass
- stale browser `max_tokens: 512`: absent
- local API default is tied to `PerformanceTuning.MAX_API_RESPONSE_TOKENS`: pass
- unused Kotlin import heuristic: pass
- Kotlin parser sweep: zero parser/syntax diagnostics (Android/GenieX symbol resolution still requires Gradle/Android SDK)

## Behavioral boundary

This removes the accidental 512-token browser cap. A response can still end before 2048 generated tokens when the prompt/history consumes most of the model's context window; that is intentional context protection. Automatic endless continuation was not added because there is no reliable stop-reason field in GenieX Android's `LlmStreamResult.Completed` API, and blindly issuing continuation prompts can create runaway generation.
