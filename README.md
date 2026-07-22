# QuickNote — one-tap voice notes from a Pixel Watch 4

A standalone native Wear OS app. One tap from the watch face opens the app **already
recording**; speak; one tap stops the recording and POSTs the audio (`.m4a`, AAC)
straight from the watch to a Discord webhook (or any HTTPS endpoint you configure).
Haptic buzz + green checkmark confirm the send. Fully phone-free — works over the
watch's LTE or wifi. No transcription on the watch, no phone relay.

- **Record**: `MediaRecorder`, AAC in an MPEG-4 container (`note-<timestamp>.m4a`),
  mono 44.1 kHz @ 128 kbps, written to app cache. Hard cap 5 minutes (auto stop & send).
- **Upload**: OkHttp `multipart/form-data` POST — a `file` part (`audio/mp4`) plus a
  `payload_json` part of `{"content":""}`, exactly what a Discord webhook expects.
- **On failure**: the audio file is kept and the error screen offers **Retry** /
  **Settings** / **Discard**. Nothing is lost on a dead LTE link.
- **Entry points**: launcher icon, a Wear **Tile** ("Record" button), and a watch-face
  **complication** (short-text or icon). All launch straight into recording.
- **Settings**: webhook URL + optional `Authorization` header value, persisted with
  DataStore. Nothing is hardcoded. Leave the auth header blank for plain Discord webhooks.

## In-app flow

1. First launch: grant the microphone permission, then the settings screen asks for
   the webhook URL (system keyboard/voice input).
2. Every later launch: recording starts immediately (haptic tap confirms).
   - **Tap anywhere** → stop & send.
   - **Long-press** → cancel the take and open settings (this is also how you change
     the URL later).
3. Success: double buzz + checkmark, app closes itself. Failure: long buzz + error
   screen with Retry.

## Build

Requirements: JDK 17+ and an Android SDK with platform `android-36`
(`local.properties` should point at it, e.g. `sdk.dir=/Users/you/Library/Android/sdk`).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Sideload to a Pixel Watch 4 over wireless ADB

1. On the watch: **Settings → System → About → Versions** and tap **Build number**
   7 times to enable Developer options.
2. **Settings → Developer options → Wireless debugging** → turn it on (watch and
   computer must be on the same wifi network).
3. Tap **Pair new device**. The watch shows a pairing code plus an `IP:port`.
4. On the computer:

   ```bash
   adb pair <IP>:<PAIR_PORT>        # enter the 6-digit code when prompted
   adb connect <IP>:<CONNECT_PORT>  # the port shown on the main Wireless debugging screen (differs from the pairing port)
   adb devices                      # should list the watch as "device"
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

5. Keep the watch on its charger during install if the connection is flaky —
   wifi on watches can be aggressive about sleeping.

## Set the webhook URL

**On the watch**: first launch opens settings automatically → tap **Webhook URL** →
type or dictate it. Later, long-press during a recording to reopen settings.

**Over adb** (much easier than typing a 120-character URL on a watch):

```bash
# NOTE: target the receiver component explicitly with -n. A bare implicit
# broadcast (-a only) will NOT be delivered to a manifest receiver on Android
# 8+ and silently no-ops (am still prints result=0).
adb shell am broadcast -n com.rusyn.quicknote/.SetWebhookReceiver \
  -a com.rusyn.quicknote.SET_WEBHOOK \
  --es url "https://discord.com/api/webhooks/<id>/<token>"
# optional auth header for non-Discord endpoints:
#   --es auth "Bearer <secret>"
```

Note: this receiver is exported for adb convenience, so any app installed on the
watch could also set the URL. Fine for a personal v1; remove the receiver or make
it non-exported if that ever matters.

## Add the tile / complication

- **Tile**: from the watch face, swipe left to the end of your tiles → **+ Add tile**
  (or long-press a tile) → pick **QuickNote**. Then swipe + one tap = recording.
- **Complication**: long-press the watch face → **Customize** → tap a complication
  slot → choose **QuickNote**. Supports short-text ("Rec" + mic icon) and
  icon-only slots. Tapping it launches recording.

## Verification status

- Verified: `./gradlew assembleDebug` builds successfully (macOS, JDK 23,
  Android SDK platform 36, AGP 8.11.1, Gradle 8.13). The merged APK manifest was
  inspected with `aapt2` and contains the mic/internet/vibrate permissions, the
  launcher activity, the tile service, the complication service, and the
  standalone-app flag.
- Not yet verified (needs the physical watch): on-device install, mic capture
  quality, tile/complication registration on the Pixel Watch 4 watch face, and a
  live POST to a real Discord webhook. The upload format matches Discord's
  documented webhook multipart contract (`file` part + `payload_json`).
