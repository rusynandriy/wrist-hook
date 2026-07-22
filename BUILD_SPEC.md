# Build Spec — Pixel Watch 4 Voice-Note App ("QuickNote")

You are building a **standalone native Wear OS app** for a **Pixel Watch 4 (LTE, Wear OS 6.1 / Android 16)**. This runs the model **Fable 5**. Build the whole thing in this repo (`/Users/andriyrusyn/projects/watch-voice-note`).

## Goal / UX
One-tap from the watch face → the app opens **already recording** → user speaks → one tap to **stop & send** → the audio file is POSTed directly from the watch to a configurable HTTPS endpoint (a Discord webhook) → haptic buzz + checkmark confirms. Fully phone-free (the watch has LTE + wifi). No on-watch transcription. No phone relay. No file-sync dependency.

## Hard requirements
1. **Records on the watch mic** using `MediaRecorder` (or `AudioRecord` if cleaner). Output an `.m4a`/AAC file (Whisper-friendly) written to app cache. Keep it robust for clips up to ~3 min.
2. **Direct upload from the watch**: on stop, do an HTTP **multipart/form-data POST** to a **user-configurable URL**. Format must match a **Discord webhook**: multipart with a file part named `file` (filename like `note-<timestamp>.m4a`, contentType `audio/mp4`), plus a `payload_json` part = `{"content":""}`. Use OkHttp. On non-2xx, show an error state and keep the audio file so it isn't lost (a simple "retry last" is a bonus, not required for v1).
3. **Configurable endpoint**: a minimal settings screen (or first-run prompt) where the webhook URL is entered and persisted (DataStore/SharedPreferences). Do NOT hardcode a URL. Also allow an optional bearer/secret header field (leave blank for plain Discord webhooks) for future-proofing.
4. **One-tap entry from the watch face**: implement as a **Tile and/or complication** that launches straight into the recording activity, and also a normal app launcher icon. Recording should start automatically on activity launch (with mic permission already granted), so it's tap→talk→tap.
5. **Permissions**: `RECORD_AUDIO`, `INTERNET`. Handle the runtime mic-permission request cleanly on first launch.
6. **Feedback**: visible recording indicator + elapsed timer; haptic on start, and haptic + checkmark on successful send; distinct error state on failure.

## Tech
- Kotlin, Jetpack Compose for Wear OS (`androidx.wear.compose`), Wear Tiles library for the tile.
- Min SDK appropriate for Wear OS 6 / Pixel Watch 4; target latest.
- Keep it minimal and dependency-light (Compose Wear + OkHttp + DataStore).

## Distribution (v1)
- Produce a **debug APK** buildable with Gradle (`./gradlew assembleDebug`). Document the exact **wireless ADB sideload** steps for a Pixel Watch 4 (enable Developer options → Wireless debugging → `adb pair` / `adb connect` → `adb install`). Put these in a `README.md`.
- Do NOT require Play Console for v1.

## Deliverables
- Full Gradle project that builds.
- `README.md`: what it is, how to build the APK, how to wireless-ADB sideload to the watch, how to set the webhook URL, and how to add the tile/complication.
- If the Gradle build can't be fully verified in this environment (SDK availability), still produce complete, correct source + build files and clearly note in the README what remains to verify on a machine with the Android SDK. Do NOT fake a successful build.

## Git / finish
- Commit to a branch `feat/v1-quicknote` and keep `main` clean. (Personal project — no PR needed, but branch it.)
- When done, update Airtable rec **recr2KumMpbaawU2v**:
```
source ~/.openclaw/workspace/.secrets
/usr/bin/curl -s -X PATCH 'https://api.airtable.com/v0/appnLZSNfhIY4UUaH/tblUAtc9i4w2RJMTQ/recr2KumMpbaawU2v' \
  -H "Authorization: Bearer $AIRTABLE_PAT" -H 'Content-Type: application/json' \
  -d '{"fields":{"Status":"Needs Andriy Review"}}'
```

## Out of scope (Jarvis handles separately)
- Creating the actual Discord webhook + configuring OpenClaw to ingest webhook-authored audio and transcribe it. The app just needs to POST to whatever URL is pasted into settings.
