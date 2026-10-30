# Cloud Backup — Design & Setup

This document describes the Google Drive backup transport added on top of the existing
local backup/restore pipeline. Read `AGENTS.md` and `ENCRYPTION.md` first.

## Scope (v1)

- **Manual snapshot**: "Back up to Drive now" and "Restore from Drive".
- **Google Drive REST API**; no Play Services, so `foss` builds work.
- **Visible `Photok/` folder** in My Drive (scope `drive.file`).
- **Keep the 3 newest archives**; older ones are deleted after a successful upload.
- **No extra archive encryption**: the uploaded bytes are exactly the local backup zip.
  Photo blobs are AES/CBC encrypted by the vault; `meta.json` (album names, file names,
  dates, wrapped VMK) is plaintext, as in every local backup.

## Why the existing pipeline is reused

The current code is already `Uri`-driven:

- `BackupStrategyImpl` / `DumpDatabaseUseCase` write into a `ZipOutputStream`.
- `ValidateBackupUseCase` + `RestoreBackupViewModel` read a `ZipInputStream` and need
  random access for the end-of-central-directory check.

The cloud layer therefore only adds transport:

```
CloudBackupViewModel
  ├─ CreateCloudBackupUseCase ──> cacheDir/cloud/<name>.zip   (existing strategies)
  ├─ CloudBackupRepository.upload(file) ──> Drive resumable upload
  └─ CloudBackupRepository.download(id, cacheFile)
         └─ navigate(AppRoute.RestoreBackup(fileUri))        (existing restore UI)
```

This keeps per-version restore strategies, duplicate handling, progress, cancellation,
and password handling untouched.

## Module layout (`app/.../backup/cloud`)

```
domain/
  CloudConfig            BuildConfig-backed OAuth client configuration
  CloudAuth              connect / disconnect / accessToken
  CloudConnection        Disconnected / Connecting / Connected(email)
  CloudBackupRepository  list / upload / download / delete / prune
  CloudSession           access token, refresh token, expiry, email, folder id
  CloudError             typed failures for the UI
  CloudFiles             file naming and retention helpers
  CreateCloudBackupUseCase
data/
  auth/                  OAuth token client, token store (Keystore AES-GCM),
                         OAuthWaitService (keeps the process alive during consent)
  drive/                 Drive REST calls, resumable upload, JSON models
  oauth/                 PKCE, authorization URL, loopback redirect server
  di/CloudModule
ui/
  CloudBackupScreen / ViewModel / UiState / UiEvent
```

## Google Cloud setup

1. Enable **Google Drive API** in project `photok-513205`.
2. Create an OAuth client of type **Desktop app**.
3. Add `http://localhost` as an allowed redirect URI (the loopback port is appended
   at runtime; Google allows any loopback port). If the device browser cannot reach
   `localhost`, set `DRIVE_REDIRECT_HOST=127.0.0.1` in `.env`, add
   `http://127.0.0.1` as a second redirect URI, and rebuild.
4. Keep the app in **Testing** and add the Google account as a test user, or publish
   the consent screen. `drive.file` only grants access to files this app created.
5. Provide credentials at build time, never in git:
   - `local.properties` (gitignored) or environment variables:
     `GOOGLE_ID`, `GOOGLE_API_KEY` (the existing `.env` names), or
     `DRIVE_CLIENT_ID`, `DRIVE_CLIENT_SECRET`.
   - They are exposed as `BuildConfig.DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET`.
   - Without them the feature shows "not configured" and sends no requests.
6. Rotate the client secret if it was ever shared or committed; `.env` and
   `google.json` are now ignored by git.

The OAuth client secret of an installed/desktop client is not confidential per
Google's model, but it is still removed from the repository and injected per build.

## OAuth flow (no Play Services)

1. Start the `OAuthWaitService` foreground service. Android freezes cached apps while the
   browser is in front; a frozen process still has the listening socket, but cannot answer,
   and the browser reports that localhost took too long.
2. Bind a loopback `ServerSocket` on IPv4 (`127.0.0.1`) and, when available, the same port
   on IPv6 (`::1`). `localhost` may resolve to either.
3. Open `https://accounts.google.com/o/oauth2/v2/auth` in a Custom Tab with PKCE
   (`S256`), `state`, `access_type=offline`, `prompt=consent`, and
   `redirect_uri=http://localhost:<port>`.
4. The browser is redirected to the loopback server, which answers with a small
   "return to Photok" page and hands the code to the waiting coroutine.
5. Exchange the code at `https://oauth2.googleapis.com/token`.
6. Store the refresh token encrypted with an Android Keystore AES/GCM key.
7. Refresh access tokens silently; `401` triggers one refresh and one retry.

The whole flow runs in an application-scoped coroutine inside the `GoogleCloudAuth`
singleton, not in the screen's ViewModel, so a vault auto-lock or a destroyed screen does
not cancel it. The foreground service is stopped when the flow finishes, fails or is
cancelled.

Custom URI schemes are blocked by Google for Android OAuth clients, so the loopback flow
is used instead of `com.googleusercontent.apps...:/oauth2redirect`.

## Drive transport

- Folder is created once (`name=Photok`, `mimeType=application/vnd.google-apps.folder`)
  and its id is cached in the encrypted session.
- Upload: `uploadType=resumable`, 8 MiB chunks (multiple of 256 KiB), `Content-Range`,
  progress from bytes sent.
- Download: `files/{id}?alt=media` streamed to `cacheDir/cloud/`, progress from bytes
  written; the existing restore screen takes over from the local file.
- Retention: after an upload with zero failed photos, list newest-first and delete
  everything after the third archive.
- Cache files live in `cacheDir/cloud/`; the OS may clear them. Stale files are pruned
  when the cloud screen opens. An uploaded archive is deleted immediately after upload.

## Failure handling

- `CloudError` carries the typed reason; the UI maps each case to a string.
- Cancellation rethrows `CancellationException` and deletes partial archives/downloads.
- Quota errors (`storageQuotaExceeded`) and insufficient local cache space have their
  own messages.
- A backup with failed photo files is still uploaded, but retention is skipped so an
  older complete archive is never replaced by an incomplete one.

## Testing

- Pure JVM tests for PKCE, authorization URL, redirect parsing, OAuth JSON, Drive JSON,
  file naming, retention ordering, and the ViewModel with fake auth/repository.
- No network tests; no Keystore tests (the cipher is behind an interface).

## Build notes

- Changing `.env`, `local.properties`, or the environment requires a rebuild. If Gradle
  reused the generated `BuildConfig`, run with `--rerun-tasks` once.
- The consent page stays open in the browser after the redirect. The loopback server
  hands the code to the app in the background; the page asks the user to return. A
  "Waiting for Google…" notification is shown while the flow runs.
- The flow survives the vault locking while the browser is open. If the app shows the
  unlock screen on return, unlock and open Cloud backup; the connection is already stored.
- Keep the app in the foreground while an upload runs. This is a manual snapshot, not a
  background job.

## Troubleshooting

- **`localhost took too long to respond`**: the app process was frozen or the browser
  picked IPv6. The foreground service and dual IPv4/IPv6 listener address this. If it
  still happens, set `DRIVE_REDIRECT_HOST=127.0.0.1` in `.env`, add
  `http://127.0.0.1` as a redirect URI in the OAuth client, and rebuild.
- **`redirect_uri_mismatch`**: the OAuth client is missing `http://localhost` (or
  `http://127.0.0.1` if you switched the host).
- **`accessNotConfigured`**: enable the Google Drive API in the Cloud project.
- **`invalid_grant`**: the grant was revoked or expired; disconnect and connect again.
