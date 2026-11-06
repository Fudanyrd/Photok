## Resolving Git Merge Conflicts

This repository carries the **cloud backup** feature (documented in
[`CLOUD_BACKUP.md`](CLOUD_BACKUP.md)) on top of the upstream `leonlatsch/Photok`
repository. Periodically we pull new features and security updates from `origin`
(upstream). Because those changes touch the same files as our cloud backup work,
merges frequently produce conflicts.

Use this section as a checklist when resolving those conflicts — for maintainers and
for Coding Agents.

### Before you start

1. Confirm the remotes: `origin` is upstream `leonlatsch/Photok`, `fork` is this fork.
   Pull the latest upstream first: `git fetch origin`.
2. Re-read the current state of the relevant docs before touching code:
   [`AGENTS.md`](AGENTS.md), [`ENCRYPTION.md`](ENCRYPTION.md), and
   [`CLOUD_BACKUP.md`](CLOUD_BACKUP.md). The live source tree is the source of truth.
3. Work from a feature branch, not `main`/`release`. Merge or rebase upstream into it
   and resolve conflicts there, then review the diff before integrating.

### The one rule that overrides everything else

> **The cloud backup functionality must be intact after conflict resolution.**
> `CLOUD_BACKUP.md` describes the contract. If resolving a conflict would remove,
> disable, or break any part of it, that is wrong — stop and fix it. Do **not** resolve
> a conflict by dropping cloud backup code just because it is "ours" and the upstream
> side is simpler.

### Checklist while resolving each conflict

- Prefer a resolution that keeps **both** changes where possible (upstream's fix *and*
  the cloud backup wiring), instead of blindly taking one side.
- Never lose cloud backup code paths. The feature lives in
  `app/src/main/java/dev/leonlatsch/photok/backup/cloud/` (and its `test/` mirror).
  If a conflict would delete or orphan any of these files, re-add them and re-wire them.
- Preserve DI bindings: cloud bindings live in `app/.../backup/cloud/di/CloudModule.kt`
  and related Hilt modules. If upstream renamed or reorganized a module or a binding,
  update the cloud bindings to match the new names/signatures instead of deleting them.
- Preserve the entry points upstream may have moved:
  - the navigation routes that open the cloud backup screen and reuse
    `AppRoute.RestoreBackup`,
  - the settings/preferences wiring (`AppPreferences.CloudBackup` and its screen entry),
  - any `AndroidManifest.xml` entries (for example `OAuthWaitService`).
- Keep the build wiring intact: cloud backup depends on extra libraries (OkHttp and the
  Drive transport) declared in `app/build.gradle.kts`. If upstream changed the build
  file, retain the cloud dependencies and the Play/FOSS guard patterns from
  `AGENTS.md` — a checkout without the `pro/` submodule must still configure and build.
- Respect encryption rules from `ENCRYPTION.md` while merging. Upstream may change the
  encryption core; make sure the cloud layer still uses `CryptoEngine` and stores its
  secrets (Drive credentials, OAuth tokens) as described in `CLOUD_BACKUP.md`, never
  in plaintext or in shared preferences.
- Keep the restore pipeline reusable: cloud backup depends on the existing local
  `BackupStrategyImpl` / `ValidateBackupUseCase` flow. If upstream refactored those,
  adapt the cloud use cases to the new API instead of reverting upstream's change.

### Verify after resolving

1. **Build both flavors** and confirm the FOSS build does not require the `pro/`
   submodule. Run: `./gradlew assembleFossDebug assemblePlayDebug` (or the equivalent
   on your machine).
2. **Run the cloud backup tests**:
   `./gradlew :app:testFossDebugUnitTest` (and the Play variant). The cloud backup tests
   live under `app/src/test/.../backup/cloud/`.
3. Grep for leftover conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) and confirm
   none remain.
4. Re-read `CLOUD_BACKUP.md` and trace the flow end-to-end against the merged code:
   credential import → unlock → connect → upload → download → restore. Every step the
   doc describes must still have a code path.
5. If cloud backup can no longer be verified (for example because an upstream API change
   needs adaptation work), open a follow-up issue rather than silently shipping a broken
   or disabled feature.

### When All is Done

Push to my repository `github.com/Fudanyrd/Photok`, branch `feat/cloud-backup`. Do not
merge the branch with cloud-backup feature into main.
