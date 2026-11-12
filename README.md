# kiosk_ha — Android 2.x kiosk + Home Assistant panel & speaker

A minimal single-app kiosk for old Android devices (2.2 through 2.3/2.4 —
API level 8–10). No support libraries, plain Java + WebView. Doubles as a
touch dashboard for Home Assistant and a LAN speaker for announcements.

## What it does

- **HOME key can't leave the kiosk** — the app registers itself as a HOME
  (launcher) app, so pressing HOME just returns to it.
- **BACK is swallowed.** Press BACK 5 times within 3 seconds to exit
  (secret unlock combo — change `TAPS_TO_EXIT` in `KioskActivity.java`).
- **Auto-starts on boot** (`BootReceiver`).
- **Pulls itself back to the foreground** if anything else steals focus.
- **Keeps the screen on** and reloads the page on network errors.
- Shows a Home Assistant dashboard (sensors + toggle switches) fed from
  `kiosk_config.json`; falls back to a clock page without config.
- Doubles as a **LAN speaker**: HA pushes TTS/audio to a built-in HTTP
  server (`/play`, `/say`) and it plays through the phone speaker.

## Layout

```
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/kiosk/KioskActivity.java
app/src/main/java/com/example/kiosk/BootReceiver.java
app/src/main/res/layout/activity_kiosk.xml
app/src/main/res/values/strings.xml
app/src/main/assets/kiosk.html   (Metro-style dashboard)
app/src/main/assets/config.html  (browser tile picker served on :8090)
```

## Home Assistant dashboard

Point `entities` at whatever you want on the screen. Push the config and
restart the app:

```sh
adb push kiosk_config.json /data/local/tmp/kiosk_config.json
adb shell am start -n com.example.kiosk/.KioskActivity
```

Notes:
- Plain **http://** only: Android 2.3's TLS tops out at TLS 1.0.
- Prefer the device's LAN **IP** over hostnames on 2.3 (DNS of `.lan`
  domains can be flaky on old resolvers).
- Config is read from `/sdcard/kiosk_config.json` first, falling back to
  `/data/local/tmp/kiosk_config.json` — on 2.3 the sdcard is unmounted
  from the phone while USB is plugged in (mass storage).
- Without a config file the page shows `● no config` and the clock.

## LAN speaker (announcements)

The app runs a tiny HTTP server on port **8090** that Home Assistant
pushes to:

| Endpoint | Effect |
|---|---|
| `GET /ping` | liveness check |
| `GET /beep` | 1 s test tone (no network needed) |
| `GET /play?url=<http url or /path>` | download + play mp3/wav; HA `media-source://tts/...` ids are auto-regenerated (below) |
| `GET /say?text=...` | HA synthesizes speech (`tts_get_url` on `tts_entity`), phone downloads + plays the mp3 |
| `GET /stop` | stop playback |
| `GET /` | **web config page** — pick which tiles the dashboard shows (see below) |

### Dashboard tile picker (web page)

Open `http://PHONE-IP:8090/` in any LAN browser: it lists every HA
entity (proxied through the phone) with checkboxes; tick the ones you
want as tiles, filter by name/domain, hit **Save to phone**. The phone
rewrites its config into app-private storage (`/data/data/.../files/`)
and the dashboard updates within one poll — no adb, no restart. The
web-saved config takes precedence over adb-pushed ones; delete it
(`adb shell run-as com.example.kiosk rm /data/data/com.example.kiosk/files/kiosk_config.json`)
to fall back. Tap behavior on the dashboard: `switch/light/fan/
input_boolean` toggle ON/OFF, `script/button` fire once ("GO"),
everything else is a read-only tile.

### HA side (one-time, configuration.yaml)

Add a virtual speaker that forwards everything to the phone:

```yaml
rest_command:
  kiosk_play:
    url: "http://PHONE-IP:8090/play?url={{ url | urlencode }}"
    timeout: 10

media_player:
  - platform: universal
    name: Kiosk
    unique_id: kiosk_phone_speaker
    device_class: speaker
    commands:
      play_media:
        service: rest_command.kiosk_play
        data:
          url: "{{ media_content_id }}"
```

Restart HA; `media_player.kiosk` appears and anything played to it —
TTS, radio, sound effects — comes out of the phone:

```yaml
action: tts.speak
target:
  entity_id: tts.google_translate_pl_pl
data:
  media_player_entity_id: media_player.kiosk
  message: Pralka skončila
```

`GET /say?text=...` does exactly that in one curl. Audio URLs must be
reachable **from the phone** (it downloads them itself with the HA token
attached); paths like `/api/tts_proxy/...` and hostnames the phone
cannot resolve are rewritten to the configured HA host automatically.
Some routers block wireless↔wireless TCP between clients — the wired
HA host works fine.

## Build (inside a container — recommended)

Android 2.x needs an old toolchain (JDK 8 + Gradle 6.5 + AGP 4.1.3).
The `Dockerfile` bundles all of it, so no old SDK install is needed on
the host. Works with **docker** or **podman**:

```sh
cd kiosk-android
./build-in-docker.sh
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

The repo ships the stock Android **debug keystore** (the well-known
`androiddebugkey` / password `android`) — that is what signs the debug
APK; it is not a secret, don't use it for release builds.

The container runs Gradle against the mounted project, so the APK lands
in `app/build/outputs/apk/debug/` owned by your user.

## Install & activate

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then press the **HOME** button: Android will ask which launcher to use —
pick **Kiosk** (choose "Always"). It will now also start by itself after
reboots.

To exit the kiosk: press **BACK 5 times quickly**.

## Notes for Android 2.4 devices

"Android 2.4" was the special ARMv6 backport of Gingerbread — it is
API level 8, which `minSdkVersion 8` covers. The Gingerbread WebView is
WebKit 533 (circa 2010), so modern web pages won't render; keep kiosk
pages simple (or serve a page designed for old WebKit).
