# GenieX v22.13 build-regression audit

## Source log reviewed
GitHub Actions archive `logs_99683182474.zip` failed during `:compileDebugKotlin` with one source error:

- `MainActivity.kt:1885:42 Unresolved reference 'layoutManager'`

All Android resource, manifest, native library, and dependency-processing stages had completed before this Kotlin compilation failure.

## Root cause
`View.doOnNextLayout { view -> ... }` exposes its callback parameter as `View`. v22.12 named that parameter `recycler` and then accessed `recycler.layoutManager`, which is a `RecyclerView` API and therefore does not resolve on `View` at compile time.

## Fix
The callback no longer relies on its generic `View` parameter. It obtains `binding.rvChat`, whose compile-time type is `RecyclerView`, then performs the existing `LinearLayoutManager` bottom-edge tracking against that object.

No scroll behavior or Markdown cadence was changed by this patch.

## Regression checks
- Verified no remaining parameterized `doOnNextLayout` callbacks depend on RecyclerView-only members.
- Parsed all 31 Android XML files.
- Parsed project JSON files.
- Validated shell-script syntax with `bash -n`.
- Rechecked the streaming bottom-scroll helper and its `LinearLayoutManager` references.

A full Android Gradle build cannot be executed in the local sandbox because the Android/Gradle toolchain is not installed; GitHub Actions remains the authoritative dependency-aware compile/lint gate.
