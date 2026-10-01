# GenieX v22.12 progressive Markdown / chat scroll audit

## Scope

v22.12 changes only the Android chat presentation path. Model loading, model downloads,
local API behavior, QAIRT/GGUF routing, compute switching, and persistent model storage
remain as in v22.11.

## Progressive Android Markdown

Previously `PAYLOAD_STREAM_TEXT` intentionally bound the growing assistant response as
plain text, and Markwon rendered Markdown only after `LlmStreamResult.Completed`.
v22.12 keeps two adapter-level Markwon instances:

- a lightweight streaming renderer for core Markdown, tables, strikethrough, links and
  inline parsing;
- the existing full renderer, including LaTeX, for the final completed response.

Streaming Markdown is throttled to roughly 150 ms, with immediate refreshes at newline
or code-fence boundaries. This avoids parsing an ever-growing document on every native
output piece while still making formatting appear progressively. Text selection and link
movement are disabled during the changing stream and restored on completion to reduce
focus/touch churn.

## Streaming scroll regression

The old code called `RecyclerView.scrollToPosition(lastIndex)` after every streaming UI
update. That only makes the last adapter item visible. Once a single assistant item becomes
taller than the RecyclerView viewport, its top/middle can remain visible while newly added
text grows below the viewport; repeatedly scrolling to the same adapter position can then
keep snapping to that anchored region.

v22.12 uses bottom-edge tracking instead:

- while auto-follow is active, a post-layout pass measures the decorated bottom of the
  final item and scrolls only the exact positive delta needed to align it with the viewport
  bottom;
- a user drag disables auto-follow as soon as the view is no longer near the bottom;
- programmatic scrolling cannot disable auto-follow;
- manually returning within 72dp of the bottom re-enables follow mode;
- completion/error/profile insertion respects the user's current follow state instead of
  unconditionally pulling the transcript to the bottom.

This specifically handles a final streaming item that is itself taller than the viewport.

## Efficiency notes

The streaming renderer omits LaTeX because math rendering is the most expensive Markwon
plugin in this project. The completed response still gets the full LaTeX-capable render.
Only one pending bottom-scroll layout callback can exist at a time, preventing a queue of
scroll work from building up during fast token generation.

## Validation

- Android XML parsing: PASS
- model-list JSON parsing: PASS
- GitHub workflow YAML parsing: PASS
- shell script `bash -n`: PASS
- MarkdownNormalizer malformed-table regression: PASS
- MarkdownNormalizer fenced-code preservation: PASS
- source scan confirms streaming binds use Markwon rather than plain `TextView.text`: PASS
- source scan confirms the streaming path no longer calls `scrollToPosition(lastIndex)`: PASS
- Kotlin compiler parse sweep: no syntax/`expecting` diagnostics in modified files

A full Android Gradle build cannot be run in this environment because no Gradle executable
or Android SDK toolchain is installed. GitHub Actions remains the dependency-aware
`lintDebug` / `assembleDebug` validation step.
