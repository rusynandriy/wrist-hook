# Security & privacy

## What leaves the watch

- **Audio**: POSTed only to the webhook URL you configure, over HTTPS.
  Cleartext HTTP is blocked twice: by the app's network security config and by
  an explicit check in the uploader. Nothing is uploaded anywhere else.
- **Pairing (optional)**: if you use the "Pair with code" flow, the app makes
  one HTTPS request to `andriy.lol/api/pair/redeem` to exchange the 4-character
  code for a webhook URL. If you never use pairing, that endpoint is never
  contacted. Forks can repoint the constant in `net/Pairing.kt`.

That is the complete list of network traffic. There are no analytics, no
telemetry, no crash reporting, and no third-party SDKs — dependencies are
AndroidX, Kotlin, and OkHttp only. No Google Play Services or Firebase
dependencies, so nothing blocks an F-Droid listing.

## What's stored where

- **Webhook URL + optional auth header + preferences**: Jetpack DataStore in
  the app's private storage. `android:allowBackup="false"`, so they are never
  copied into cloud backups.
- **Recordings**: written to the app's private cache directory, deleted after a
  successful upload (or on discard/cancel). A failed upload keeps the file so
  you can retry; it stays private to the app and is subject to normal cache
  eviction.
- **Logs**: the app never logs the webhook URL, auth header, or audio.

## App surface

- Permissions: `RECORD_AUDIO`, `INTERNET`, `VIBRATE` — nothing else.
- `SetWebhookReceiver` (adb convenience for setting the URL) is exported
  **only in debug builds**. In release builds no other app can rewrite your
  webhook URL.
- The launcher activity, tile, and complication are the only other entry
  points; the tile and complication services are protected by the standard
  Wear OS bind permissions.

## Reporting

This is a small personal project. If you find a security issue, open a GitHub
issue or email the address on the profile.
