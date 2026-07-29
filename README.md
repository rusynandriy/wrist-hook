# Wrist Hook

Record voice notes from your wrist. Raw audio is POSTed straight to any webhook
you configure. No phone, no account, no tracking.

![Platform](https://img.shields.io/badge/platform-Wear%20OS-4285F4?logo=wearos&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue)

A standalone Wear OS app (built and tested on a Pixel Watch 4). One tap on a
tile or complication and it's already recording; speak; tap again and the `.m4a`
is uploaded over the watch's own LTE or wifi to whatever HTTPS endpoint you
point it at. A haptic buzz and a checkmark confirm the send.

I built it because every existing watch voice-memo flow either needs the phone
nearby, traps the audio in someone's cloud, or takes three taps to start. I
wanted the thought captured before it evaporates, and the audio delivered
somewhere *my* software can act on it — in my case a Discord channel where a
bot transcribes and files it. Yours could be n8n, Home Assistant, or a
five-line server on your LAN.

## How it works

- **Record**: `MediaRecorder`, AAC mono 16 kHz @ 64 kbps in an MPEG-4 container
  (`note-<timestamp>.m4a`), written to app cache. Whisper-friendly, ~0.5 MB/min.
- **Upload**: `multipart/form-data` POST with one part named `file`
  (`audio/mp4`). If the URL is a Discord webhook, a `payload_json` part is added
  automatically (Discord requires it); other endpoints get a clean single-part
  upload. Optional `Authorization` header. HTTPS only.
- **On failure**: the file is kept and you get Retry / Settings / Discard —
  nothing is lost on flaky LTE.
- **Settings** (on-watch, persisted in DataStore): webhook URL, auth header,
  auto-stop on silence, max recording length (30 s – 10 min, default 5 min),
  accent color (red / blue / green / amber / violet).

## Setup

1. Build & sideload (below), open the app, grant mic access.
2. Set the webhook URL — either type/dictate it in settings, use a pairing code
   from [andriy.lol/pair](https://andriy.lol/pair), or push it over adb
   (debug builds only):

   ```bash
   adb shell am broadcast -n com.rusyn.wristhook/.SetWebhookReceiver \
     -a com.rusyn.wristhook.SET_WEBHOOK \
     --es url "https://example.com/hook"
   # optional: --es auth "Bearer <secret>"
   ```

3. Add the entry point: swipe to the end of your tiles → **Add tile** →
   Wrist Hook, or long-press the watch face → **Customize** → pick Wrist Hook
   for a complication slot.

Then: tap → talk → tap. Long-press during a recording cancels the take;
settings are on the menu that follows, and on the error screen.

## Integration recipes

**Discord** — create a channel webhook (Channel settings → Integrations →
Webhooks), paste the URL. The audio arrives as a file attachment; a bot can
pick it up and transcribe.

**n8n** — add a Webhook node (POST, binary data). The audio is in the `file`
field. Chain a transcription node or drop it into storage.

**Home Assistant** — a [webhook trigger](https://www.home-assistant.io/docs/automation/trigger/#webhook-trigger)
(`https://<ha>/api/webhook/<id>`) fires an automation; the multipart body is
available to scripts/add-ons that handle uploads.

**Generic + local Whisper** — a few lines of Python:

```python
from flask import Flask, request
app = Flask(__name__)

@app.post("/hook")
def hook():
    request.files["file"].save("/notes/in.m4a")   # then run whisper on it
    return ""
```

## Compared to the alternatives

| Tool | Phone required? | Raw audio to your endpoint? | Account / cloud required? | One-tap from watch face? | Open source? |
|---|---|---|---|---|---|
| **Wrist Hook** | No (LTE/wifi) | Yes — any HTTPS webhook | No | Yes (tile/complication) | Yes (MIT) |
| Google Recorder (Pixel Watch) | For transcripts/sync | No — Google account sync | Google account | App launch | No |
| Voice Recorder for Wear OS | Companion app to export | No — file export via phone | No | App launch | No |
| Easy Voice Recorder (Wear) | Yes (sync to phone) | No — Drive/Dropbox/OneDrive | Cloud storage account | App launch | No |
| Voicenotes / Otter-style AI notes | Mostly no (watch apps exist) | No — their cloud, their AI | Yes | Varies | No |
| Tasker + AutoWear | Yes (Tasker runs on phone) | Possible, with real effort | No | Achievable | No (paid) |

Honest notes: Google Recorder is excellent *if* you live in the Pixel ecosystem
and want transcripts in Google's cloud — it just can't deliver audio to your
own server. The generic recorder apps record fine but getting the file
somewhere useful means a phone round-trip. Voicenotes/Otter give you polished
AI notes but you're renting their pipeline. Tasker+AutoWear can be bent into
almost anything, including this, if you enjoy plumbing. Wrist Hook only does
one thing: mic → your URL.

## Build & sideload

JDK 17+ and Android SDK platform 36 (`local.properties` → `sdk.dir=...`).

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Sideload to the watch over wireless adb: enable Developer options (tap Build
number 7×), turn on **Wireless debugging**, then:

```bash
adb pair <IP>:<PAIR_PORT>       # code shown on the watch
adb connect <IP>:<CONNECT_PORT> # port from the main Wireless debugging screen
adb install app/build/outputs/apk/debug/app-debug.apk
```

Keep the watch on its charger if the wifi connection is flaky.

## Security & privacy

Short version: audio goes only to the URL you configure, over HTTPS, and
nothing else leaves the watch. No analytics, no third-party services, no Google
Play Services / Firebase dependencies. Details in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE) © Andriy Rusyn
