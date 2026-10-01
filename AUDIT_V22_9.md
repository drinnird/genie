# GenieX v22.9 browser streaming audit

## User-observed behavior

When the host phone was awake, web-chat output appeared in relatively large text bursts. When the host display was off, the same stream appeared to advance almost character-by-character.

## Root cause

Two independent buffers were stacking while inference was fast:

1. `LocalApiServer.TokenChunker` buffered output for up to 50 ms or 96 characters before emitting an SSE event.
2. The browser waited another 90 ms before reparsing/repainting the growing Markdown response.

When the phone generated more slowly with its display off, individual native output pieces often arrived outside those batching windows, so the page *looked* more continuous even though inference itself was slower.

## Changes

- Server SSE pacing: 50 ms / 96 chars -> 16 ms / 32 chars.
- Browser Markdown pacing: fixed 90 ms timer -> one coalesced `requestAnimationFrame()` repaint using the newest accumulated response text.
- A 50 ms timeout is retained only as a fallback if the browser suspends animation frames.
- Existing sticky-scroll behavior is preserved: scrolling upward disables follow-to-bottom until the user returns near the bottom.
- Added streamed-chat telemetry: first-output delay, native output-piece count, SSE chunk count, streamed characters, total duration, approximate characters/sec, and power/screen state at completion.

## Performance rationale

The server still avoids a socket flush for every tiny native fragment, while the 16 ms pacing aligns closely with a 60 Hz display frame. The browser performs at most one expensive full Markdown parse/repaint per animation frame, even if multiple SSE events arrive in that frame. This removes the visible ~90-140 ms stacked latency without turning every token callback into an immediate DOM rebuild.

## Screen-off limitation

A partial CPU wake lock keeps the CPU running with the display off; it does not guarantee identical CPU/GPU/NPU clock rates. Samsung/Android power management can still reduce performance. v22.9 does not add redundant wake locks. The new telemetry is intended to distinguish a real inference-rate reduction from browser-side batching in future diagnostics.

## Static validation

- Browser inline JavaScript: `node --check` pass.
- Stream-pacing assertions: pass (`16 ms`, `32 chars`, no old `90 ms` render timer).
- Android XML parse: pass (31 files).
- Model JSON parse: pass.
- Local Android resource-reference scan: pass.
- Manifest component-to-source scan: pass.
- Shell `bash -n`: pass.
- Kotlin syntax-diagnostic sweep: no parser-level syntax diagnostics detected (full Android symbol resolution requires Gradle/Android SDK).
- Project build script invoked; local environment still stops at `gradle: command not found`.
