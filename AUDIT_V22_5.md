# GenieX v22.5 model lifecycle audit

## Requested behavior

- Restore the last **successfully loaded** model, including its compute unit, on a normal app startup.
- Loading another downloaded model must not require a manual unload first.

## Implementation

### Last-successful model persistence

`AppPreferences` now stores a dedicated `last_loaded_model` + `last_loaded_compute` pair. It is deliberately separate from the catalog's selected row, so simply browsing/selecting a model does not change startup restore behavior.

The preference is written only after GenieX native model creation has succeeded and the durable model-load diagnostic has been marked complete. Failed or interrupted loads therefore do not replace the last known-good startup model.

For upgrades from v22.4, the app can migrate a last successful model from the existing completed model-load diagnostic record. Interrupted/failed records are not eligible.

Explicitly deleting the remembered model from the Models screen clears the startup-restore record. Manually unloading a model does not forget it.

### Startup restore safety

After GenieX SDK initialization, the app resolves the remembered model, verifies that its files are still available, validates/falls back to a compute unit supported by the current catalog entry, and loads it without presenting the compute picker.

A prior interrupted native model load suppresses automatic restore for that launch to avoid an automatic crash loop. The existing interrupted-load warning remains authoritative.

### Automatic model switching

A different available model now displays **Switch** rather than a disabled Load action. Selecting it goes through the normal compute choice (unless QAIRT/NPU-only), then:

1. obtains the process-wide inference mutex;
2. stops the local API listener if it was running, preventing a new request from racing teardown;
3. stops/destroys the current LLM/VLM wrapper;
4. clears native bridge state and conversation state;
5. waits briefly for driver/native allocations to settle;
6. records the requested replacement model + compute unit as the pending clean-runtime load;
7. uses the existing restarter process to relaunch a fresh inference process;
8. automatically loads the requested model; and
9. resumes the API server if it had been running before the switch.

This preserves the previous fresh-process safeguard for GPU/NPU/QAIRT allocations rather than attempting to keep two large models resident at once.

If inference/document processing currently owns the model, switching is rejected with a busy/finish-current-inference message; the app never destroys a native handle that is actively in use.

## Regression/cleanup changes

- Removed the Models-screen guard that required a manual unload before choosing another model.
- Removed the obsolete `blockedByActiveModel` UI state; it is now `switchesActiveModel` and remains actionable.
- Replaced programmatic clicks on hidden Load/Unload compatibility buttons with direct `requestModelLoad()` / `requestModelUnload()` calls.
- Centralized native wrapper teardown in `destroyLoadedModelLocked()` so manual unload and automatic switch cannot drift into different cleanup behavior.
- Model downloads are no longer unnecessarily blocked merely because another model is loaded; downloads remain owned by the foreground service.
- Updated the low-memory message so it describes actual post-unload memory pressure rather than implying the previous model still needs manual unloading.

## Static validation

Performed on the packaged source:

- 31 Android XML files parse successfully.
- `model_list.json` parses successfully.
- Android manifest project component references resolve to source files.
- Kotlin `R.*` references resolve to local resources.
- Shell scripts pass `bash -n`.
- GitHub Actions YAML parses successfully.
- No stale references remain to `blockedByActiveModel`, `pendingResumeAttempted`, or the old manual-unload guard text.
- Naive project-wide private-function reachability scan reports no declaration-only private functions.
- Naive unused Kotlin import scan reports no unused imports.
- Project-wide Kotlin parser sweep reports no syntax (`expecting ...`) diagnostics. Android/AndroidX/GenieX symbols cannot be semantically resolved without the Android/Gradle dependency graph.
- `AppPreferences.kt` compiles against a minimal Android `Context`/`SharedPreferences` API stub.

## Full build boundary

The project `build.sh` was invoked, but this environment does not contain Gradle/Android SDK tooling; it stops at:

`gradle: command not found`

The included GitHub Actions `lintDebug assembleDebug` job remains the authoritative full Android/Kotlin/dependency build gate.
