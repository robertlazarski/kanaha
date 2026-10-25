# iRig Pro I/O SMPTE Timecode Setup for Android

## Overview

This document describes how to connect an IK Multimedia iRig Pro I/O audio interface to Android devices for recording SMPTE timecode audio in Kanaha multi-camera productions. For how the timecode is then used (the studio rig, 30 fps, checking a recording), see [Using the timecode](#using-the-timecode); for 12-bit RAW takes, [12-bit_depth.md](12-bit_depth.md#timecode).

**Important:** Older Android phones ("drawer phones") require specific equipment and configuration. This guide documents extensive real-world testing with a Moto X4 (2017) to help users avoid common pitfalls.

## Equipment Required

### For Modern Phones (Pixel 6+, 2021+)

Modern USB-C phones use USB Type-C Dual Role Port (DRP) with CC pin role detection, replacing the older USB OTG standard. This provides automatic host/device negotiation and full USB Power Delivery (500mA+) — no OTG adapters needed.

| Item | Description | Amazon ASIN |
|------|-------------|-------------|
| iRig Pro I/O | USB audio interface | B06W5H9FFJ |
| Mini-Din 7-pin to USB-C cable | Direct connection | (included with iRig) |

### For Older Phones (Pre-2021)

SMPTE timecode recording on phones older than ~2021 is **unlikely to work** due to insufficient USB OTG power. See "Older Phones" section below for details. If you want to try, you'll need:

| Item | Description | Amazon ASIN | Critical Notes |
|------|-------------|-------------|----------------|
| Simple USB-C OTG Adapter | Basic OTG, NO charging passthrough | B07F9Z1NCP | **Must be simple adapter** (USB 2.0 or 3.0 OK) |
| Mini-Din 7-pin to USB-A cable | Connects iRig to adapter/hub | B0DRWK7V76 | |
| USB 2.0 Powered Hub (optional) | May help if phone can enumerate hubs | B00DQFGJR4 | **Must be USB 2.0 — USB 3.0 hubs fail on older phones** |

## Device Compatibility Matrix

| Device | Year | USB-C | Direct Connection | Notes |
|--------|------|-------|-------------------|-------|
| Pixel 10 Pro XL | 2025 | Yes | **Yes, verified 2026-10-25** | Shows as "USB-Audio - iRig PRO I/O"; LTC decoded continuously over a 270 s take |
| Pixel 9 Pro | 2024 | Yes | **Yes** | Full USB-C PD support |
| Pixel 6/7/8 | 2021+ | Yes | **Yes** | Full USB-C PD support |
| Phones from ~2021+ | 2021+ | Yes | **Likely** | Most provide sufficient USB power |
| Moto X4 (retired) | 2017 | Yes | No | **Not viable** — insufficient USB power, hubs don't enumerate |
| Older USB-C phones | 2016-2020 | Yes | Unlikely | Test with simple OTG adapter first; see "Older Phones" section |

## Connection Diagrams

### Modern Phone (Direct Connection)

```
┌───────────────┐
│ PIXEL 10 PRO  │
│               │
│   ┌───────┐   │
│   │ USB-C │   │
│   │ port  │   │
│   └───┬───┘   │
└───────┼───────┘
        │
        │ Mini-Din 7-pin to USB-C cable
        │ (USB-C male end)
        │
        ▼
┌─────────────────┐
│  iRIG PRO I/O   │
│                 │
│  ┌───────────┐  │
│  │ Mini-Din  │◄─┼── 7-pin end of cable
│  │ 7-pin     │  │
│  │ port      │  │
│  └───────────┘  │
│                 │
│  ┌───────────┐  │
│  │ BLUE LED  │  │ ← BRIGHT BLUE
│  │    ●      │  │   (full power)
│  └───────────┘  │
└─────────────────┘
```

**Note:** Modern phones provide sufficient USB-C power directly. No adapters or hubs needed.

### Older Phones (Pre-2021): Not Recommended for SMPTE Timecode

Phones from before ~2021 (like the Moto X4, 2017) do not provide enough USB power for the iRig Pro I/O. **If your phone is from 2021 or later, skip this section entirely** — just use a direct USB-C connection.

The iRig Pro I/O follows the USB specification strictly: it draws only 100mA until it enumerates with a USB host, then requests 500mA. Older phones provide only ~100-150mA via USB OTG — not enough for the iRig's USB controller chip to even initialize and complete enumeration. This creates a chicken-and-egg problem: the iRig needs enumeration to draw full power, but needs more power than the phone provides to complete enumeration. You can tell this is happening by the **dim blue LED** on the iRig (vs. the bright blue LED on a modern phone).

We tested every reasonable approach to inject external power on a Moto X4 (2017). None worked:

| Approach | Result | Why It Failed |
|----------|--------|---------------|
| USB 3.0 powered hub | Failed | Phone's OTG controller can't handle USB 3.0 SuperSpeed negotiation |
| USB 2.0 powered hub | Failed | Phone enters host mode but can't enumerate any hub (SDM660 limitation) |
| USB Y-splitter cable (power injection) | Failed | Even with VCC wire cut, the cable in the chain prevents OTG detection |
| iRig AA batteries | No effect | Batteries power the analog audio circuits, not the USB controller chip |
| iRig DC adapter (PSU 3A) | No effect | Designed for iOS device charging passthrough, doesn't supplement USB power |
| USB charger (no host) | No effect | Without a USB host, enumeration never occurs and iRig stays at 100mA |

**Quick test for your phone:** Connect iRig directly via a simple USB-C OTG adapter (no charging passthrough — e.g., B07F9Z1NCP). If the iRig LED is bright blue, your phone provides enough power. If dim blue, try a USB 2.0 powered hub. If that also fails, the phone is not viable for SMPTE timecode. The phone still works fine with Kanaha for camera control and video recording.

---

## Mini-Din 7-Pin Connector Handling

**WARNING:** The Mini-Din 7-pin connector is extremely fragile. Bent pins are the most
common failure mode and can destroy the cable permanently.

**Before inserting, match the pin pattern.** The plug's pins must mirror the port's holes,
and the plug has a solid rectangular key in its centre that fits the port's centre slot.
Hold the plug beside the port and turn it until its row of three pins lines up with the
port's row of three holes. A plug that only goes in with force is misaligned, has a bent
pin, or has a broken centre key: one damaged in use (2026-10-24) had a pin bent towards
the centre and its key crumbled away, and would not seat.

**Insertion procedure:**
1. Locate the **key notch** on the iRig's 7-pin port (small slot in the metal ring)
2. Align the cable connector's **key tab** (often marked with an arrow) with the key notch
3. Hold the connector lightly against the port and **rotate slowly** until the key tab drops into the slot - you'll feel it want to slide in
4. Push straight in with gentle, even pressure - it should click in smoothly
5. **Never force it.** If it resists, pull back and re-align. Forcing bends the pins.

**If pins are bent:**
- Use a needle or fine jeweler's screwdriver to gently nudge each pin back to vertical
- Work one pin at a time under bright light
- Apply minimal force - pins snap after being bent back and forth
- If pins are severely bent or touching each other, or the centre key is cracked or
  missing, the cable should be replaced

**Make it last:** leave the 7-pin end plugged into the iRig and connect and disconnect at
the phone end, and keep the cable's weight off the plug; sideways strain is what cracks
the insert and bends pins.

**Replacement cables:**
- Mini-Din 7-pin to USB-A: B0DRWK7V76
- Mini-Din 7-pin to USB-C: (included with iRig Pro I/O, or search for iRig Pro I/O USB-C cable)

---

## iRig Pro I/O LED Status

| LED Color | Status | Meaning |
|-----------|--------|---------|
| **Bright Blue** | OK | Enumerated with USB host, drawing ~500mA, device ready |
| **Dim/Faded Blue** | Pre-enumeration | USB-spec 100mA limit (no host, or host can't enumerate) |
| **Green** | OK | Audio input signal present, low level |
| **Orange** | OK | Audio input signal at nominal level |
| **Red** | Warning | Audio input signal clipping — reduce gain |
| **No Light** | Problem | No USB connection or power |

**Important:** A dim blue LED does NOT necessarily mean the cable or power source is bad.
The iRig Pro I/O is USB-spec compliant and limits itself to 100mA until it successfully
enumerates with a USB host. Dumb chargers (power strips, wall adapters) have no USB host
capability, so the iRig will always show dim blue on them. Only a USB host that can
complete enumeration (computer, modern phone) will produce a bright blue LED.

---

## iRig Pro I/O Power Sources

The iRig Pro I/O has three power inputs, but they serve **different purposes**:

| Power Source | What It Powers | Provides VBUS for USB? | Enables Enumeration? |
|-------------|---------------|----------------------|---------------------|
| **USB VBUS** (from host) | USB controller chip + analog circuits | Yes | Yes (if host provides 500mA+) |
| **2x AA Batteries** | Analog circuits (preamp, ADC/DAC, phantom power) | **No** | **No** |
| **DC Adapter** (iRig PSU 3A, 5V/3A) | Analog circuits + iOS device charging passthrough | **No** | **No** |

**Key insight:** The USB controller chip runs **exclusively** off USB VBUS from the host.
Neither batteries nor the DC adapter power the USB interface. This means:

- **Batteries** are designed for iOS/Lightning use, where the iOS device provides enough
  VBUS for the USB chip but not for the analog section
- **DC adapter** is designed to charge an iOS device via passthrough while the iRig is in use
- Neither can help an older Android phone that provides insufficient VBUS

**For modern phones (Pixel 6+, ~2021+):** Direct USB-C connection provides full VBUS power.
No batteries, adapters, or hubs needed.

---

## Troubleshooting

### Dim blue light on iRig

**Cause:** Insufficient USB power

**Solutions (in order):**
1. Verify hub's AC adapter is plugged in and hub has power LED lit
2. Try a different USB port on the hub
3. Ensure you're using a USB 2.0 hub, not USB 3.0
4. Check that simple OTG adapter is used (no charging passthrough)

### Phone not entering host mode

**Symptoms:** No USB notification, `host_connected=false` in USB dump

**Diagnostic command (via ADB over WiFi):**
```bash
adb shell dumpsys usb | grep -E "host_connected|current_mode"
```

**Expected output when working:**
```
host_connected=true
current_mode=dfp
```

**Solutions:**
1. Replace OTG adapter with simple type (B07F9Z1NCP)
2. Replace USB 3.0 hub with USB 2.0 hub (B00DQFGJR4)
3. Try connecting a simple USB device (flash drive) to verify OTG works at all

### Hub works on laptop but not on phone

**Cause:** USB 3.0 hub incompatible with phone's OTG

**Solution:** Use a USB 2.0 hub instead. USB 3.0's SuperSpeed negotiation is not supported by older phones' OTG controllers.

---

## Technical Background

### Why Older Phones Need Special Equipment

1. **Limited OTG power output** - Older phones provide only 100-150mA via USB OTG. The iRig needs ~500mA.

2. **USB-C implementation varies** - Early USB-C phones (2015-2018) have inconsistent OTG support. Many don't properly detect all OTG adapters.

3. **USB 3.0 complexity** - USB 3.0 adds SuperSpeed signaling (SSTX+/-, SSRX+/-) beyond USB 2.0's four wires. Older OTG controllers only understand USB 2.0.

4. **Power backfeed issues** - Some powered hubs send voltage upstream, confusing the phone's USB-C detection circuitry.

### USB Hub Protocol Differences

| Feature | USB 2.0 Hub | USB 3.0 Hub |
|---------|------------|-------------|
| Max Speed | 480 Mbps | 5 Gbps |
| Signal Lines | 4 (VCC, D+, D-, GND) | 9 (adds SSTX+/-, SSRX+/-, GND_DRAIN) |
| Enumeration | Simple | Complex (SuperSpeed negotiation) |
| OTG Compatibility | Good | Poor on older phones |
| Recommendation | **Use this** | Avoid for older phones |

---

## Using the Timecode

### The studio rig

The sound recorder is the timecode master: a Zoom F8 jams one Tentacle Sync E per
camera, and each Tentacle's LTC output feeds that camera's iRig Pro I/O. Every camera
then records the same timecode as audio, and the edit lines them up by it, with no
network coordination during the take.

- **Set everything to 30 fps, not 29.97.** Kanaha records 30 fps. LTC at 29.97 non-drop
  runs 0.1% slow against it, and each camera's timecode drifts from the recorder's by
  about 8 frames over a 4.5 minute song; the first whole-song test (2026-10-25) measured
  exactly that. Set the F8 to 30, re-jam, and check each Tentacle shows 30.
- **Re-jam after the F8 restarts.** A Tentacle runs freely from its last jam; an F8 that
  powers up again at 00:00:00 no longer matches the Tentacles.
- **Gain.** The iRig's mono input appears on both channels of the recording. LTC is a
  square wave and decodes even when clipping, but set the gain so it peaks around
  −6 dBFS (orange LED, not red).
- **The USB-C port is taken.** With the iRig in the phone's only port, use wireless adb
  for control during a shoot and copy recordings off between songs.

### Checking it

The phone sees the interface:

```bash
adb shell dumpsys audio | grep -m1 -o "iRig[^]]*"
```

A recording carries continuous LTC (x42 `ltc-tools`):

```bash
ltcdump -F -c 1 take.wav | head
```

Each line is the timecode and the samples it spans: at 48 kHz and 30 fps, 1600 samples a
frame (1601.6 means the LTC is running at 29.97). For 12-bit RAW takes,
`tools/kanaha-develop` reads the LTC, writes the first frame's timecode into the MOV, and
warns when the rate is not 30 fps: [12-bit_depth.md](12-bit_depth.md#timecode). Software
sync without timecode hardware (GPS/NTP, `start_at`, a slate tone) is in
[GPS.md](GPS.md).

---

## The "Drawer Phone" Opportunity

There are an estimated **500 million unused smartphones** sitting in drawers worldwide. Many of these are perfectly capable devices limited only by:
- Outdated OS (can't run latest apps)
- Cracked screens (still functional for recording)
- Battery degradation (works fine when plugged in)

Kanaha's multi-camera system can repurpose these devices as dedicated recording stations. The key challenges documented here—USB OTG compatibility and power delivery—are solvable with the right equipment.

**Caveat:** Phones from approximately 2019 and older (notably the Moto X4 / Qualcomm SDM660)
lack sufficient USB OTG VBUS current for the iRig Pro I/O to enumerate. After exhaustive
testing (5 approaches including hubs, Y-splitters, batteries, and DC adapters), these phones
are **not viable for SMPTE timecode recording**. Kanaha itself works fine for camera control
and video recording on these devices — only the USB audio interface for timecode is affected.
Phones from ~2021+ (Pixel 6 and later) with full USB-C Power Delivery work with a simple
direct connection.

---

## References

- Kanaha Multi-Camera Deployment System: [MULTI_CAMERA_DEPLOYMENT_SYSTEM.md](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md)
- 12-bit RAW takes, timecode and `kanaha-develop`: [12-bit_depth.md](12-bit_depth.md)
- Software sync without timecode hardware: [GPS.md](GPS.md)
- IK Multimedia iRig Pro I/O: https://www.ikmultimedia.com/products/irigproio/
- Amazon Basics USB 2.0 Hub: https://www.amazon.com/dp/B00DQFGJR4
- Simple USB-C OTG Adapter: https://www.amazon.com/dp/B07F9Z1NCP
- USB Y-Splitter Power Injection Cable (Plan B): https://www.amazon.com/dp/B072LLMKJF
- Understanding OTG: https://lavalink.com/lavablog/articles/understanding-otg/
