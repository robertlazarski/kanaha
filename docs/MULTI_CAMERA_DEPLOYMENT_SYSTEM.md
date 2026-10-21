# Multi-Camera Deployment

How to bring up several Kanaha cameras, secure them, find them, drive them together
and make their footage match. Every command here was checked against the code as of
October 2026; the API itself is documented in the [README](../README.md#5-control-via-api).

**The rig this is written for:** a Pixel 10 Pro XL as A-camera, with a second one
planned, and Moto G phones as B-cameras. The Pixel 9 Pro and Moto X4 that the
January 2026 tests used have been retired; their results are kept under
[Tested devices](#tested-devices) as history.

## What it is

Each phone runs its own Apache httpd (HTTP/2, mTLS, Axis2/C) on port 8443. There is
no central server and no camera-to-camera traffic: the control station talks to
each camera directly, and the cameras are aligned afterwards, in post, from a shared
SMPTE timecode, recorded on each camera through an iRig. Adding a camera adds an endpoint
and nothing else.

The APK is **arm64-v8a only**, minimum Android 6.0 (API 23); 32-bit phones cannot
install it.

## Architecture

```
control station ──HTTP/2 + mTLS──▶ httpd (child process of ApacheService, port 8443)
                                      │ mod_axis2 → camera_control_service.c
                                      │ fork/execvp("/system/bin/am", "broadcast", …)
                                      ▼
                          CameraControlReceiver (Java, signature permission)
                                      │ drives OpenCamera, writes a response file
                                      ▼
                          native side reads the response file → HTTP/2 response
```

- `ApacheService` is a `specialUse` foreground service, **not exported**. The app
  starts it from `MainActivity`; nothing outside the app can start or stop it.
- The native side reaches Java with `am broadcast` through `fork`/`execvp`, so no
  caller-supplied string passes through a shell. On devices that refuse `am` from
  an app process (the Moto G Play 2024), it falls back to a request file that
  `CameraIpcPoller` picks up, which adds up to 500 ms per call.
- `CameraControlReceiver` is exported but guarded by the signature permission
  `org.kanaha.camera.permission.CAMERA_CONTROL`, so other apps on the phone cannot
  drive it.
- The httpd configuration ships as templates in `assets/apache/conf/`
  (`httpd.conf`, `ssl.conf`, `http2-performance.conf`, `axis2.conf`); at start the
  service substitutes only the device's hostname. See [SECURITY.md](SECURITY.md)
  for what those files enforce.

## Bringing a camera up

The camera must be **in the foreground**: Android gives the camera only to the app on
screen, and `startRecording` refuses with an explicit error while the app is in the
background. Over ADB:

```bash
adb shell svc power stayon usb                 # keep the screen on while plugged in
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n org.kanaha.camera/net.sourceforge.opencamera.MainActivity
```

Stop or restart (for example after provisioning a certificate):

```bash
adb shell am force-stop org.kanaha.camera
adb shell am start -n org.kanaha.camera/net.sourceforge.opencamera.MainActivity
```

Logs: `adb logcat -s KanahaApacheService KanahaCameraReceiver KanahaCameraService`.
The httpd's own logs are inside the app sandbox:
`adb shell run-as org.kanaha.camera ls files/apache/logs`.

Before recording, `getStatus` should show `"camera_available": true` and
`"preview_active": true`. Over WiFi alone there is no way to wake or unlock a phone;
someone has to bring the app to the front once.

## mTLS certificate architecture

One private CA, kept off every device. Each camera makes its own key; each control
station has a client certificate from the same CA. No private key ships in the APK.

1. On first run the app generates an RSA key and a CSR (`files/csr/camera.csr`) and
   keeps port 8443 closed; the notification reads "Awaiting provisioning".
2. Sign the CSR and push the certificate back:

   ```bash
   adb shell "run-as org.kanaha.camera cat files/csr/camera.csr" > camera.csr
   openssl x509 -req -in camera.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
     -out camera.crt -days 365 \
     -extfile <(printf "subjectAltName=IP:<ip>,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth")
   adb push camera.crt /data/local/tmp/server.crt && adb push ca.crt /data/local/tmp/ca.crt
   adb shell "run-as org.kanaha.camera sh -c 'cp /data/local/tmp/server.crt files/apache/ssl/server.crt && cp /data/local/tmp/ca.crt files/apache/ssl/ca.crt'"
   ```

3. Restart the app. Provisioning survives reboots and app updates.

Creating the CA and the control-station client certificate is in
[SECURITY.md](SECURITY.md#certificate-model-per-device-provisioning).

**Where clients look for certificates:** `$KANAHA_CERT_DIR` (default `~/kanaha-ca`),
holding `client.crt`, `client.key` and `ca.crt` (or `operator.crt`, `operator.key`
and `kanaha-ca.crt`). The discovery tool and the workflow scripts use the same
directory.

**Connect by the address in the certificate, without `-k`.** `-k` turns off server
verification entirely, which makes `--cacert` pointless. The SAN above covers the
camera's IP, and `localhost` for an `adb forward`. Give a camera a DHCP reservation so
its IP, and therefore its certificate, stays valid.

```bash
SSL=${KANAHA_CERT_DIR:-~/kanaha-ca}
CURL="curl -s --http2 --cert $SSL/client.crt --key $SSL/client.key --cacert $SSL/ca.crt"
```

Certificates last one year. Revocation (CRL/OCSP) is not implemented yet; until it
is, a compromised device is handled by re-issuing the CA and re-provisioning.

## Discovery

Each Kanaha app advertises `_https._tcp` over mDNS while its server runs, with
`api=kanaha-camera-control` in the TXT record (Kanaha Audio and Kanaha Calcs
advertise their own `api=` values).

```bash
./tools/kanaha-discover.sh                         # every Kanaha app found
./tools/kanaha-discover.sh --kind camera           # cameras only
./tools/kanaha-discover.sh --kind camera --json | jq -r '.[].ip'
./tools/kanaha-discover.sh --ip 192.168.8.168      # one known address
./tools/kanaha-discover.sh --scan                  # port scan when mDNS is blocked
```

Output looks like:

```
Found 2 Kanaha app(s):

  camera  pixel10proxl (Google Pixel 10 Pro XL)
    Address:  192.168.8.168:8443
    Status:   idle, battery 100%, 180000 MB free
    URL:      https://camera.local:8443/services/CameraControlService  (curl --resolve camera.local:8443:192.168.8.168)
```

The tool asks every app it finds for its status over mTLS with full certificate
verification: by IP first, then, for a certificate that names the camera by DNS
name, by `$KANAHA_CAMERA_TLS_NAME` (default `camera.local`) through `curl --resolve`,
which is the form the URL line shows. It lists an app that answers mDNS but not the status call as
unreachable, with the reason. `--json` without `--kind camera` includes the audio
and calcs apps too.

**Android names the host, not the app.** The mDNS A record carries the phone's
system hostname (`Android_54RWTDIY.local`), not the name Kanaha registers; apps
cannot set it (`NsdServiceInfo.setHostname()` is a blocked hidden API). The TXT
record carries `ip=` for that reason, and the discovery tool connects by IP. The
system hostname is not in the camera's certificate, so do not connect by it.

mDNS needs the control station and cameras on the same subnet with multicast
allowed (UDP 5353). Some enterprise networks block it; use `--scan` or `--ip`.

## Calling a camera

```bash
$CURL -H "Content-Type: application/json" -d '{"action":"getStatus"}' \
  "https://192.168.8.168:8443/services/CameraControlService/getStatus"
```

**The action in the body must match the URL, in camelCase.** The engine refuses a
body that names a different operation from the URL, so `{"action":"get_status"}`
sent to `/getStatus` fails with `operation mismatch`. (The service itself also knows
the snake_case names, but over HTTP the URL decides.)

Responses are `{"success":true,...}` or `{"success":false,"error":"..."}`;
`getStatus` nests its fields under `"status"`.

| Operation | Use |
|---|---|
| `getStatus` | Readiness, battery, storage, GPS time, `supports_hlg10` |
| `startRecording` | `clip_name`, `quality`, `start_at`, `open_gate`, `hlg10` |
| `stopRecording` | |
| `setExposure` / `getExposure` | Pin and read exposure, white balance, focus, stabilization, tone curve, frame rate, codec and bitrate; see [Matching cameras](#matching-cameras) |
| `setZoom` | Zoom or ramp the zoom during a take, scheduled to a moment in the song; cues queue |
| `playTone` | Audio sync slate, optionally at `start_at` |
| `listFiles`, `deleteFiles`, `cleanupFiles` | Files on the device |
| `sftpTransfer` | Send a recording and its sidecars to a storage server |
| `describeClip` | On-device description of a recording (`nano` build) |

`startRecording` also takes `duration` and `format`, which are logged and echoed
back but have no effect: a recording runs until `stopRecording`. Parameters and
examples for each operation are in the [README](../README.md#5-control-via-api).

## Starting and aligning cameras

Kanaha starts the cameras together; **SMPTE timecode aligns the clips in post.**

### Timecode: how clips are aligned

Each camera records SMPTE linear timecode (LTC) on its audio track, through an IK
Multimedia **iRig Pro I/O** on its USB-C port, fed by a timecode generator such as a
**Tentacle Sync** jammed to the same time as every other camera and the sound recorder.
Every frame of every clip then carries the same clock, so clips line up to the frame
however their recordings started. Hardware, cables and which phones can power the iRig
are in [IRIG_PRO_SMPTE_TIMECODE_SETUP.md](IRIG_PRO_SMPTE_TIMECODE_SETUP.md).

- **Timecode takes the phone's audio input.** With the iRig connected, Android records
  from it instead of the built-in microphone, so the camera's audio track holds timecode,
  not scene sound. Record sound on a separate recorder fed from the same timecode.
- **Kanaha does not generate or read timecode itself**; it records what the iRig delivers.
- **Pin the shutter, or the camera is not running at 30 fps.** On auto exposure in a dim
  room the Pixel 10 Pro XL picked a 33.2 ms shutter and its sensor ran at 29.74 fps: about
  60 frames, two seconds, short over a four-minute song against 30 fps timecode. With
  `setExposure` `{"iso": 800, "exposure_time_ns": 16666667, "fps": 30}` (1/60 s) a 10-bit
  HLG open-gate clip measured 29.998 fps (2026-10-20). Set timecode to 30 non-drop to
  match; drop-frame exists only at 29.97, and it skips frame numbers, never frames.
- **Reading it on Linux:** `ltcdump` (from [x42 ltc-tools](https://github.com/x42/ltc-tools),
  built from source; not packaged for Ubuntu) decodes the audio. Extract the track first:

  ```bash
  ffmpeg -i take.mp4 -vn -ac 1 -ar 48000 -c:a pcm_s16le take.wav && ltcdump -F take.wav | head
  ```

  Each line gives a timecode and the audio sample where that frame starts, so the clip's
  start timecode is the first decoded timecode minus its sample position / 48000. The
  difference between two clips' start timecodes is the offset to trim. Checked on
  2026-10-19 with timecode from `ltcgen` passed through an AAC-encoded MP4, as a phone
  records it: the first frame decoded 8 samples (0.2 ms) from the start.

### Starting together, and without timecode

- **`start_at`**: every camera fires at the same wall-clock millisecond, so WiFi
  delivery delay does not skew the start. It must be 50 ms to 30 s in the future;
  outside that window the camera starts immediately.
- **The recording-start sidecar**, one per recording (`<basename>.kanaha_start.json`,
  carried by `sftpTransfer` and `deleteFiles`), records when the start fired, not the first
  frame. Measured on 2026-10-19, the Pixel 10 Pro XL's first frame came
  0.38 s after `start_at` and the Moto G Play 2024's 1.12 s after, while their sidecars were
  9 ms apart: the sidecar alone left the two clips 0.74 s (22 frames) apart. Use it to find
  the takes, not to align them.
- **`playTone`**, a tone on every camera at the same moment, aligns clips to about 1–5 ms
  from their audio when there is no timecode. It needs the built-in microphone, so it does
  not apply to cameras recording timecode through the iRig.

```bash
CAMS=$(./tools/kanaha-discover.sh --kind camera --json | jq -r '.[].ip')
START_AT=$(( $(date +%s%N) / 1000000 + 3000 ))
for ip in $CAMS; do
  $CURL -H "Content-Type: application/json" \
    -d "{\"action\":\"startRecording\",\"clip_name\":\"scene_01\",\"start_at\":$START_AT}" \
    "https://$ip:8443/services/CameraControlService/startRecording" &
done
wait
```

Without timecode, follow it with a slate:

```bash
SLATE_AT=$(( $(date +%s%N) / 1000000 + 2000 ))
for ip in $CAMS; do
  $CURL -H "Content-Type: application/json" \
    -d "{\"action\":\"playTone\",\"frequency\":1000,\"duration_ms\":500,\"start_at\":$SLATE_AT}" \
    "https://$ip:8443/services/CameraControlService/playTone" &
done
wait
```

An `open_gate` start reopens the camera first (3–5 s), so give those takes at least
5 s of lead time. [GPS.md](GPS.md) measures how good `start_at` and the slate are and
compares them with SMPTE LTC.

## Matching cameras

Two cameras that each meter their own framing never match: auto exposure and auto
white balance decide differently for a wide shot of snow and a close-up of a
face, and stabilization crops each frame by a different amount. Identical phones
only help if they are told to make identical decisions.

### Setting up a second identical phone

**Keep both phones on the same Android build.** An OTA update changes Google's image
processing, so two Pixel 10 Pro XLs on different builds will not match on identical
settings.

- Turn off automatic system updates on both: Developer options > Automatic system
  updates.
- When you do update, update both before the next shoot, and redo the gray-card
  check below.
- Check the build matches: `adb shell getprop ro.build.fingerprint` on each.

Install the same Kanaha APK on both, provision each with its own certificate from the
same CA, and use the main (1×) lens on both: the other lenses have different
sensors, bit depths and colour.

### Pinning the settings

Meter exposure and focus on one camera and send the same values to all of them; lock
white balance on each camera separately, below:

```bash
VALUES=$($CURL -H "Content-Type: application/json" -d '{"action":"getExposure"}' \
  "https://$A:8443/services/CameraControlService/getExposure" \
  | jq -c '{action:"setExposure", iso, exposure_time_ns, focus_distance, stabilization:false, tone:"rec709", fps:30}')
for ip in $CAMS; do
  $CURL -H "Content-Type: application/json" -d "$VALUES" \
    "https://$ip:8443/services/CameraControlService/setExposure" &
done
wait
```

- **White balance: lock each camera, then correct each from a gray card in post.** With the
  gray card in shared light, let auto white balance settle (read `wb_gains` twice a couple of
  seconds apart until they stop changing), then send `{"action":"setExposure","wb":"lock"}`
  to each camera. The lock freezes what that camera's auto white balance chose, gains and
  factory colour calibration, and keeps it through open gate reopens and app restarts
  (`wb_mode` reads `locked`). It does **not** make different phones agree: on the same card
  the Pixel 10 Pro XL's auto white balance rendered it warm (R/G 1.21, B/G 0.85) and the Moto
  G Play 2024's near gray (1.04, 0.96), and locking froze both as they were. What the lock buys
  is a colour that stays put, so the gray-card correction below, measured once, stays valid
  for every take. Do not copy `wb_gains` or `wb_kelvin` between cameras: gains belong to one
  sensor, and the kelvin reading is an estimate that sat at 15000 K in daylight on both test
  phones.
- **`wb_kelvin` is the fallback**, for when no card is to hand: give every camera the same
  value (5600 daylight, about 6500 overcast, 3200 tungsten). OpenCamera turns it into gains with
  a generic formula and applies no colour calibration, so identical phones match each other
  but colour is less accurate, and different models do not match.
- **Values are refused, never clamped.** A value outside a camera's range, or a
  shutter longer than one frame, fails on that camera with its range in the error.
  Check every response for `"success":true`.
- **`fps` pinned on all.** On `"default"` OpenCamera requests no frame rate and each
  camera chooses its own; `ranges.fixed_fps` in `getExposure` lists what a camera can
  hold (15, 24, 30 and 60 on the Pixel 10 Pro XL).
- **Stabilization the same on all**, or off. Electronic stabilization crops by a
  varying amount.
- **`tone` fixes the contrast curve**, so the phone cannot adjust contrast to what each
  camera frames. `rec709` keeps a normal look; the log curves (`fine` to
  `extra_strong`) are flat and leave more room to grade. Not combinable with
  `hlg10`.
- **Settings persist** across takes and through the reopen that `open_gate` causes,
  until changed or reset with `{"action":"setExposure","auto":true}`.
- Shutter: 1/60 (`16666666` ns) at 30 fps gives normal motion blur. In bright sun at
  the lowest ISO that overexposes; fit the same ND filter to every camera rather than
  raising the shutter speed on one.
- The Moto Gs need Settings > Camera API > Camera2 for manual exposure and kelvin;
  on Camera1 they refuse with that instruction. Check what each supports with
  `getExposure` (`supports`, `ranges`). Pick values every camera accepts: with a Pixel
  10 Pro XL and a Moto G Play 2024 that means ISO 100–5333, 24 or 30 fps (the Moto has no
  60), and stabilization off (the Moto has none). The Moto records 1920×1080 at most, so a
  mixed edit is a 1080p edit, or a 4K one with the Moto's shots upscaled.

### Mixing a Pixel and a Moto in one edit

The two phones label their 8-bit files differently: the Pixel 10 Pro XL writes limited
range (16–235) with BT.709 colour, the Moto G Play 2024 full range (0–255) with BT.601.
Cut together as they are, the Moto's footage shows different contrast and slightly
different colour even on identical settings. Convert the Moto's to the Pixel's first:

```bash
ffmpeg -i moto_take.mp4 -vf "colorspace=all=bt709:iall=bt601-6-625:irange=pc:range=tv:format=yuv420p" -c:v libx264 -crf 18 -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv -c:a copy moto_take_709.mp4
```

Checked on test footage: full-range black (0) and white (255) land on 16 and 235. Do this
before the gray-card correction below. Different sensors also render saturation and hue
differently, which settings cannot fix; expect a per-camera grade on top.

### Gray-card check and correction

Each phone's auto white balance renders a gray card its own way, and between models the
difference is large: 17% in red between the Pixel 10 Pro XL and the Moto G Play 2024. Correct
each camera to neutral gray in post. With white balance locked, one measurement holds for
every take under that light.

1. With exposure and white balance locked on each camera (above), record a few seconds of
   the gray card filling the centre of each frame, in the light of the shoot.
2. Convert a Moto clip to BT.709 first ([above](#mixing-a-pixel-and-a-moto-in-one-edit)), then
   measure the mean colour of the centre third, as 0–255 R G B:

   ```bash
   ffmpeg -v error -ss 3 -i card.mp4 -vf "crop=iw/3:ih/3,scale=1:1:flags=area:in_range=tv:out_range=pc,format=rgb48le" -frames:v 1 -f rawvideo - | od -An -tu2 | awk '{print $1/257, $2/257, $3/257}'
   ```

3. The camera's correction gains are `rr = G/R` and `bb = G/B`, which bring the card to
   neutral. Keep them in a per-camera file; they hold until the light or the lock changes.
4. Apply them to that camera's footage before grading:

   ```bash
   ffmpeg -i take.mp4 -vf "format=gbrp,colorchannelmixer=rr=0.825:bb=1.183,format=yuv420p" -c:v libx264 -crf 18 -c:a copy take_corrected.mp4
   ```

Measured on 2026-10-19 under indoor light: the Pixel needed `rr=0.825 bb=1.183`, the Moto
`rr=0.960 bb=1.038`. With those, both rendered the card within 0.5% of neutral, at a
different moment of the take from the one measured.

**Measure again for each light.** Outdoors in daylight the same day, the two phones' auto
white balance agreed on a near-neutral cream wall within about 1%, where indoors they had
been 17% apart in red; the Pixel's warm rendering is an indoor and low-light behaviour.
The indoor gains applied to the daylight footage pushed the Pixel well off neutral. And some
differences are not white balance at all: the Pixel rendered the sky distinctly warmer and
less saturated than the Moto in every take, which gray-card gains cannot fix; that is for a
per-camera grade or LUT. For HLG footage the gains apply to
non-linear values, so measure and correct within HLG and keep corrections modest.

### Checking that it worked

Before relying on two cameras, test the one you have. With manual values pinned,
record the same scene twice with different framing, then compare the area the two
shots share (same crop on both). If the means match, the phone's scene-dependent
processing is not undoing the fixed settings, and two identical phones will match
apart from the gray-card gains. If they drift, pin `tone` as well and repeat.

**Result on the Pixel 10 Pro XL, 2026-10-19.** Tripod, 1× lens, a balcony with bright
sky in one framing and tilted down to mostly shade in the other, exposure, 5600 K, focus
and 30 fps pinned. The area both framings share was compared in blocks after aligning
the frames:

| | Default curve | `tone: rec709` (and 1.6 stops brighter) |
|---|---|---|
| Whole shared area, B/A brightness | 0.970 | 0.999 |
| Blocks in the upper two thirds | 0.92–1.04 | 0.97–1.04 |
| Blocks along the frame's bottom edge | 0.72–0.82 (2–3 code values) | 0.91–0.94 (1–2.5 code values) |

The phone does not re-tone the picture to suit what is in frame once the settings are
pinned. Two small residues remain with either curve: an area changes by a code value or
two depending on where in the frame it sits, which fits the lens edge correction rather
than scene processing, and a 1–4% warm shift with framing. Neither is something a
setting removes, and both are below what a grade notices. Pinning `rec709` costs nothing
and the shared area matched to 0.999 with it, though the brighter exposure in that pair
also narrows relative differences.

## File transfer

`sftpTransfer` sends a recording, and the sidecars that belong to it, to a storage
server listed in the camera's `servers.json`, authenticating with an ed25519 key and
refusing hosts not in `known_hosts`. Setup is in
[SFTP-FILE-TRANSFER.md](SFTP-FILE-TRANSFER.md).

```bash
for ip in $CAMS; do
  $CURL -H "Content-Type: application/json" \
    -d '{"action":"sftpTransfer","storage_server_id":"control1","video_filename":"*.mp4","destination_folder":"scene_01"}' \
    "https://$ip:8443/services/CameraControlService/sftpTransfer" &
done
wait
```

A 4K take is several GB; a transfer can take minutes over WiFi, and the call blocks
until it finishes (up to 20 minutes).

## Workflow scripts

`kanaha-camera-app/test-single-camera-workflow.sh`, `test-dual-camera-workflow.sh`
and `test-triple-camera-workflow.sh` run a full take on one, two or three cameras:
status, start, stop, transfer and cleanup. The triple script also uses `start_at` and
the `playTone` slate, and pulls files over ADB rather than SFTP. They read
certificates from `$KANAHA_CERT_DIR`. In the dual and triple scripts `--discover`
lists cameras and exits; it does not record. See each script's `--help`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `operation mismatch` | The body's `action` differs from the URL; use the camelCase name |
| `Kanaha Camera is not in the foreground` | Bring the app to the front |
| Connection refused on 8443 | Not provisioned yet ("Awaiting provisioning"), or the app is not running |
| `SSL certificate problem` / handshake failure | Wrong CA, a client cert from another CA, or connecting by a name or IP not in the camera's certificate |
| Request hangs until timeout | A `.probe` debug build: the native side broadcasts to `org.kanaha.camera` only |

Confirm HTTP/2 is actually negotiated; a silent fallback to HTTP/1.1 is the failure
to look for:

```bash
curl -v --http2 --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
  "https://$ip:8443/services/CameraControlService" 2>&1 | grep -i ALPN
# want: ALPN: server accepted h2
```

## Tested devices

The current tested list, with Android versions and caveats per phone, is in
[ANDROID_APK_BUILDING.md](ANDROID_APK_BUILDING.md).

**History, January 2026.** The same APK and API calls ran on a Pixel 9 Pro
(Android 15) and a Moto X4 (Android 9), over ADB and WiFi, including SFTP. Started
together over WiFi without `start_at`, which did not exist yet, the two recordings
began one second apart:

| Camera | File | Size | Start |
|---|---|---|---|
| Pixel 9 Pro | VID_20260105_085605.mp4 | 136 MB | 08:56:42 |
| Moto X4 | VID_20260105_085604.mp4 | 75 MB | 08:56:41 |

`start_at` and the `playTone` slate were added because of that second.

## Related documentation

- [SECURITY.md](SECURITY.md): certificates, the TLS configuration, input validation
- [GPS.md](GPS.md): how well cameras align, and how that compares with SMPTE LTC
- [OPENGATE.md](OPENGATE.md): open gate, 10-bit HLG, 12-bit RAW, grading with ffmpeg
- [SFTP-FILE-TRANSFER.md](SFTP-FILE-TRANSFER.md): storage server and SSH key setup
- [ANDROID_APK_BUILDING.md](ANDROID_APK_BUILDING.md): building, installing, tested devices
- [THREAD_MODEL.md](THREAD_MODEL.md): the threads a request crosses
