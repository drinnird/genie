# GenieX v22.6 build-log regression audit

The GitHub Actions log `logs_99631963790.zip` reached Android resource processing and failed specifically in `compileDebugKotlin` with two type mismatches in `MainActivity.kt`:

- VLM history trimming passed `List<String?>` to `ChatRolePolicy.oldestTurnPrefixCount(List<String>)`.
- VLM history validation passed `List<String?>` to `ChatRolePolicy.validateForGeneration(List<String>)`.

GenieX 0.4.0 exposes `VlmChatMessage.role` as nullable. The two call sites now map nullable roles with `role.orEmpty()`. This intentionally converts a null role to an unsupported empty role so the existing validator/repair path handles it before JNI instead of silently dropping the message.

No LLM call sites required the same change because `ChatMessage.role` is non-null in the current SDK surface.
