# 8K Video and Kanaha

## BLUF

Kanaha cannot record 8K today, and on the Pixel 10 Pro XL it never will: the phone's
hardware video encoders stop at 3840×2176, so no app can record a larger frame. What a
Pixel calls 8K is a 4K recording that Google upscales in the cloud. Kanaha's largest
recorded frame is 4K itself, 3840×2160.

Some Samsung, Xiaomi, OnePlus and Realme phones do record native 8K. Whether that
reaches a third-party app like Kanaha is unknown per phone and has to be checked on
the device; if it does, adding it to Kanaha is small.

## What the Pixel calls 8K

On the Pixel 9 Pro and 10 Pro, choosing 8K in the stock Pixel Camera app records a 4K
file on the phone. With **Video Boost** on, the clip uploads to Google Photos, Google
processes it in the cloud and returns an 8K version hours later, at 24 or 30 fps, for
clips up to 10 minutes.

That processing is more than resizing. Google describes it as HDR+-style tone
processing, Night Sight denoising for low light, and stabilization, done on servers
because the phone cannot do it in real time. The resolution step itself, from 4K to
8K, adds no detail the sensor did not record.

None of this is reachable from Kanaha. Video Boost belongs to the stock camera app and
Google Photos. A Camera2 app records through the phone's hardware encoders, which accept
at most 3840×2160 for H.264 and 3840×2176 for HEVC (`/vendor/etc/media_codecs_c2.xml`,
read from the device on 2026-10-19). The camera offers larger streams, up to 4032×3024 at
4:3, but nothing can encode them; the full 12-megapixel frame is reachable only as RAW
stills or RAW sequences (see [OPENGATE.md](OPENGATE.md), Part 2).

## Reframing: 4K for pixels, open gate for coverage

| | 4K (Kanaha today) | Open gate (Kanaha today) | 8K |
|---|---|---|---|
| Frame | 3840×2160 | 2560×1920 | 7680×4320 |
| Megapixels | 8.3 | 4.9 | 33.2 |
| 16:9 crop | 3840×2160 | 2560×1440 | 7680×4320, upscaled |
| 9:16 vertical crop | 1215×2160, 16:9 band's height only | 1080×1920, full sensor height | 2430×4320, upscaled |
| Recorded pixels | all | all | about a quarter (upscaled from 4K) |

4K is the most real resolution the phone records, and for a 16:9 deliverable at 4K it
is the one to shoot. Open gate, limited to 2560×1920 by the same encoder ceiling, keeps
the top and bottom of the scene that 16:9 never records, so its vertical and taller
crops show more of the scene, and it covers 1080p deliverables, including a native
1080×1920 vertical, without upscaling. An upscaled 8K file gives more room to crop only
in appearance, since past 4K the extra pixels are interpolated.

## If you want an 8K file anyway

For a deliverable that has to be 7680×4320, for instance YouTube's 8K tier, upscale
locally. Upscale only the final cut, after grading.

```bash
# Plain resize: fast, honest, adds no detail
ffmpeg -i graded.mp4 -vf "scale=7680:4320:flags=lanczos" -c:v libx265 -crf 20 -preset medium -pix_fmt yuv420p10le -tag:v hvc1 -c:a copy out_8k.mp4
```

An open gate source is 4:3, so crop to 16:9 first (`crop=2560:1440`, then scale); from
open gate that is a 3× upscale, against 2× from 4K.
For something closer to Video Boost's look, an AI upscaler such as Real-ESRGAN, run
through a frame-by-frame tool such as video2x, sharpens and denoises as it scales; it
needs a GPU and is slow. Neither recovers detail that was never recorded.

Expect about four times the data of 4K and about a quarter of the render speed.
Measured on the development machine, 4K 10-bit HEVC encodes at 3.7 fps (x265 slow)
to 32 fps (Intel VAAPI), so 8K runs at roughly 1–8 fps.

## Phones with native 8K

Several Samsung (Galaxy S Ultra since the S20 Ultra), Xiaomi, OnePlus and Realme phones
record native 8K to local storage. For Kanaha the question is not whether the phone
records 8K but whether **its Camera2 API offers an 8K video stream to third-party
apps**, and that is often not the case. Manufacturers commonly keep their best modes for
their own camera app: Samsung's developer forum states that third-party apps on
Samsung phones are held to 30 fps for thermal and power reasons, and that only the
native camera app gets every feature the hardware supports. Nothing found confirms or
rules out 8K for third-party apps on any specific model.

To check a phone before buying it for this, or one already in hand:

```bash
adb shell dumpsys media.camera | grep -E "7680|4320"
```

No match means Camera2 offers no 8K stream to Kanaha, whatever the box says. A match
is worth a test recording in Kanaha with the OpenCamera resolution picker
(Settings > Video > Video resolution), which lists what Camera2 actually offers.

## What adding 8K to Kanaha would take, on a phone that offers it

Little. `startRecording` already passes a native OpenCamera quality string through
when the camera supports it, and maps the names `4K`, `1080p`, `720p` and `480p` to
resolutions in `configureRecordingQuality()`. An `8K` name mapped to 7680×4320 is a
few lines. The real costs are elsewhere:

- **Storage:** about 80–100 Mbps for 8K30 HEVC, roughly 0.6–0.75 GB a minute, two to
  three times a 4K take, and SFTP transfers take as much longer.
- **Heat:** 8K recording is where phones throttle or stop first; long takes may not
  survive.
- **Matching:** an 8K camera in a rig of 4K cameras is graded down to the others' look,
  so the gain is limited to reframing that one camera's shots.

## Sources

- [Google Pixel Camera Help: Video Boost](https://support.google.com/pixelcamera/answer/14257322?hl=en)
- [Android Authority: Pixel 10 Pro video upgrades](https://www.androidauthority.com/google-pixel-10-pro-video-upgrades-3591405)
- [Tom's Guide: 8K on Galaxy S25 Ultra vs Pixel 10 Pro XL vs OnePlus 15](https://www.tomsguide.com/phones/i-recorded-8k-video-with-the-galaxy-s25-ultra-vs-pixel-10-pro-xl-vs-oneplus-15-does-it-matter-that-the-iphone-17-pro-cant)
- [Samsung Developer forum: Galaxy S22 Ultra maximum video resolution and FPS from the camera API](https://forum.developer.samsung.com/t/galaxy-s22-ultra-maximum-video-resolution-and-fps-from-camera-api/21218)
- [Samsung Developer forum: Camera apps, suggestion to Samsung developers](https://forum.developer.samsung.com/t/camera-apps-suggestion-to-samsung-developers/19213)
