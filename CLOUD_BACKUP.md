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
- **Runtime OAuth credentials**: the Drive client ID/secret are not baked into the build.
  The user pastes `google.json` on first use. It is encrypted with the vault password into
  app-private storage, unlocked per vault session, and never uploaded or included in a
  backup archive.

## Why the existing pipeline is reused

The current code is already `Uri`-driven:

- `BackupStrategyImpl` / `DumpDatabaseUseCase` write into a `ZipOutputStream`.
- `ValidateBackupUseCase` + `RestoreBackupViewModel` read a `ZipInputStream` and need
  random access for the end-of-central-directory check.

The cloud layer therefore only adds transport:

```
CloudBackupViewModel
  ├─ CloudCredentialsSession.unlock(password)  (before any cloud call)
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
  CloudOAuthConfig       redirect host only; no client secret
  DriveClientCredentials decrypted client id/secret, masked toString()
  CloudCredentialsState  Missing / Locked / Unlocked
  CloudCredentialsRepository
  LoadCloudCredentialsUseCase / StoreCloudCredentialsUseCase
  CloudAuth              connect / disconnect / accessToken / credential state
  CloudConnection        Disconnected / Connecting / Connected(email)
  CloudBackupRepository  list / upload / download / delete / prune
  CloudSession           access token, refresh token, expiry, email, folder id,
                         client-id fingerprint
  CloudError             typed failures for the UI
  CloudFiles             file naming and retention helpers
  CreateCloudBackupUseCase
data/
  auth/                  OAuth token client, token store (Keystore AES-GCM),
                         OAuthWaitService (keeps the process alive during consent),
                         CloudCredentialsCipher, CloudCredentialsSession
  credentials/           google.json parser, encrypted file repository
  drive/                 Drive REST calls, resumable upload, JSON models
  oauth/                 PKCE, authorization URL, loopback redirect server
  di/CloudModule
ui/
  CloudBackupScreen / ViewModel / UiState / UiEvent
  DriveCredentialsDialog
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
5. Download the OAuth client JSON (`google.json`). Do **not** put it in the APK or in
   the repository:
   - On first opening Cloud backup, the app shows "Drive credentials not set up".
   - The user pastes the JSON into the import dialog.
   - The app reads `installed.client_id` and `installed.client_secret`, asks for the
     vault password, encrypts the pair, and writes
     `noBackupFilesDir/cloud/drive-credentials.enc`.
   - The build no longer reads `GOOGLE_ID` / `GOOGLE_API_KEY` /
     `DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET`. Only `DRIVE_REDIRECT_HOST` remains a
     build/environment value.
6. If the file is missing, corrupt, or was cleared after a failed password change, the
   same import dialog is shown again. The owner can always paste the JSON again.
7. `.env` and `google.json` stay ignored by git.

The OAuth client secret of an installed/desktop client is not confidential per
Google's model, but it is no longer part of the repository or `BuildConfig`. On-device it
is stored encrypted and only decrypted into memory.

## Credential storage and unlock

- **File**: `noBackupFilesDir/cloud/drive-credentials.enc`. `noBackupFilesDir` keeps the
  secret out of Android Auto Backup; it is also not part of the local backup zip.
- **Envelope**: `[version][kdfIterations][salt][nonce/iv][ciphertext]`.
- **Crypto**: PBKDF2-HMAC-SHA256 (100k iterations, 256-bit key) + AES-GCM. A wrong
  password or corrupted file fails closed instead of silently producing garbage.
- **Import**: JSON + vault password. Malformed JSON is rejected before anything is
  written.
- **Unlock**: before the first cloud operation in an unlocked vault session, the user
  enters the vault password. The password is verified against the vault first, then the
  credentials are decrypted into memory. No network request is made while they are
  locked.
- **Wrong password**: show "wrong vault password", leave the encrypted file untouched.
- **Corrupt/unreadable file**: show a re-import prompt and do not keep trying to decrypt.
- **Session cache**: `CloudCredentialsSession` holds the decrypted credentials in memory
  for the whole unlocked vault session. They are intentionally retained across individual
  Drive requests so silent access-token refresh and the post-`401` retry work. They are
  cleared on vault lock, disconnect, and process death.
- **Password change**: after a successful vault password change, re-encrypt the
  credentials file from the old password to the new one. If the password change or the
  re-encryption fails for any reason, delete the credentials file and clear the OAuth
  token session; the user pastes `google.json` again. Never leave a file that the current
  password cannot open.
- **Vault reset**: delete the credentials file and the OAuth token session.

The credentials are encrypted with the vault password only; there is no VMK-wrapped copy.
Unlocking the vault with biometrics or a recovery phrase still requires the vault password
before cloud operations.

## OAuth flow (no Play Services)

1. Unlock the Drive credentials with the vault password (see above). `connect` is only
   available while the credential session is unlocked.
2. Start the `OAuthWaitService` foreground service. Android freezes cached apps while the
   browser is in front; a frozen process still has the listening socket, but cannot answer,
   and the browser reports that localhost took too long.
3. Bind a loopback `ServerSocket` on IPv4 (`127.0.0.1`) and, when available, the same port
   on IPv6 (`::1`). `localhost` may resolve to either.
4. Open `https://accounts.google.com/o/oauth2/v2/auth` in a Custom Tab with PKCE
   (`S256`), `state`, `access_type=offline`, `prompt=consent`, and
   `redirect_uri=http://localhost:<port>`. The authorization URL uses the runtime client
   ID.
5. The browser is redirected to the loopback server, which answers with a small
   "return to Photok" page and hands the code to the waiting coroutine.
6. Exchange the code at `https://oauth2.googleapis.com/token` using the runtime client ID
   and secret.
7. Store the refresh token encrypted with an Android Keystore AES/GCM key. The stored
   session also records a client-id fingerprint; a token from a different imported client
   is discarded.
8. Refresh access tokens silently; `401` triggers one refresh and one retry. The client
   secret for refresh comes from the in-memory credential session, which is kept for the
   whole unlocked vault session.

The whole flow runs in an application-scoped coroutine inside the `GoogleCloudAuth`
singleton, not in the screen's ViewModel, so a vault auto-lock or a destroyed screen does
not cancel it. The flow captures the client credentials when it starts, so the consent and
token exchange can finish even if the vault locks while the browser is open. The
foreground service is stopped when the flow finishes, fails or is cancelled. The
in-memory credential cache itself is still cleared on vault lock; after returning, unlock
the vault and enter the vault password again before the next cloud operation.

Custom URI schemes are blocked by Google for Android OAuth clients, so the loopback flow
is used instead of `com.googleusercontent.apps...:/oauth2redirect`.

## Drive transport

- Every operation first requires an unlocked credential session. A missing or locked
  session fails with a typed error before any network call.
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
- Missing credentials (`CredentialsMissing`) -> show the `google.json` import dialog; no
  requests are sent.
- Locked credentials (`CredentialsLocked`) -> ask for the vault password and resume the
  pending action; no requests are sent.
- Invalid pasted JSON (`InvalidCredentialsJson`) -> inline error before saving.
- Wrong vault password -> password error; the encrypted credentials file is untouched.
- Corrupt credentials (`CredentialsCorrupt`) -> re-import prompt.
- `AuthorizationFailed` only clears the OAuth token session; the encrypted Drive
  credentials file remains and does not need to be pasted again.
- Cancellation rethrows `CancellationException` and deletes partial archives/downloads.
- Quota errors (`storageQuotaExceeded`) and insufficient local cache space have their
  own messages.
- A backup with failed photo files is still uploaded, but retention is skipped so an
  older complete archive is never replaced by an incomplete one.
- If credential re-encryption fails during a password change, the credentials file and
  OAuth session are deleted and the UI asks for `google.json` again.

## Testing

- Pure JVM tests for PKCE, authorization URL, redirect parsing, OAuth JSON, Drive JSON,
  file naming, retention ordering, and the ViewModel with fake auth/repository.
- Credential tests: `google.json` parsing, AES-GCM round trip, wrong password/tampered
  ciphertext, file repository exists/load/store/clear, and no plaintext in the file.
- ViewModel tests: missing -> import -> unlocked, password required before cloud calls,
  wrong password, invalid JSON, no network while locked, and client change clearing the
  OAuth session.
- Password-change tests: successful re-encryption and deletion of the credentials file
  plus OAuth session on any password-change/re-encryption failure.
- No network tests; no Keystore tests (the cipher is behind an interface).

## Build notes

- Drive client credentials no longer require a rebuild. They are pasted at runtime and
  stored encrypted on the device.
- `DRIVE_REDIRECT_HOST` is still read at build time. Changing it in `.env` or the
  environment requires a rebuild; if Gradle reused the generated `BuildConfig`, run with
  `--rerun-tasks` once.
- The consent page stays open in the browser after the redirect. The loopback server
  hands the code to the app in the background; the page asks the user to return. A
  "Waiting for Google…" notification is shown while the flow runs.
- The OAuth flow survives the vault locking while the browser is open. The decrypted
  credential cache does not; after returning, unlock the vault and enter the vault
  password again before the next cloud operation.
- Keep the app in the foreground while an upload runs. This is a manual snapshot, not a
  background job.

## Troubleshooting

- **"Drive credentials not set up"**: paste the `google.json` from the Google Cloud OAuth
  client. Make sure it is a Desktop app client with `http://localhost` (or the configured
  redirect host) as an allowed redirect URI.
- **"Wrong vault password"**: the credentials file was not modified. Enter the vault
  password again.
- **"Drive credentials could not be decrypted"**: the file is corrupt or was encrypted
  with a different password. Paste `google.json` again.
- **Cloud credentials were cleared after a password change**: the re-encryption failed or
  the old password was unavailable. Paste `google.json` again.
- **`localhost took too long to respond`**: the app process was frozen or the browser
  picked IPv6. The foreground service and dual IPv4/IPv6 listener address this. If it
  still happens, set `DRIVE_REDIRECT_HOST=127.0.0.1` in `.env`, add
  `http://127.0.0.1` as a redirect URI in the OAuth client, and rebuild.
- **`redirect_uri_mismatch`**: the OAuth client is missing `http://localhost` (or
  `http://127.0.0.1` if you switched the host).
- **`accessNotConfigured`**: enable the Google Drive API in the Cloud project.
- **`invalid_grant`**: the grant was revoked or expired, or different client credentials
  were imported. Disconnect and connect again.
