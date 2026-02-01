# iRig Pro I/O SMPTE Timecode Setup for Android

## Overview

This document describes how to connect an IK Multimedia iRig Pro I/O audio interface to Android devices for recording SMPTE timecode audio in Kanaha multi-camera productions.

## Equipment Required

| Item | Description | Amazon Link |
|------|-------------|-------------|
| iRig Pro I/O | USB audio interface for SMPTE timecode recording | B06W5H9FFJ |
| Mini-Din 7-pin to USB-C cable | Connects iRig directly to Pixel 9 Pro | (included with iRig or separate) |
| Mini-Din 7-pin to USB-A cable | Connects iRig to powered hub for Moto X4 | B0DRWK7V76 |
| USB-C OTG Adapter with PD | OTG + charging for Moto X4 | B08KPD5S82 |
| Sabrent Powered USB Hub | Provides independent power to iRig (Moto X4 only) | B00TPMEOYM |

## Device Compatibility

### Pixel 9 Pro (and newer devices)
- **Direct connection works** - No powered hub needed
- Modern USB-C PD implementation provides adequate power

### Moto X4 (and older devices)
- **Requires powered USB hub** - Phone's USB OTG cannot provide enough power
- Faded blue LED on iRig indicates insufficient power

## Connection Diagrams

### Pixel 9 Pro (Direct Connection - No Adapter Needed)

```
┌───────────────┐
│  PIXEL 9 PRO  │
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

**Note:** The Pixel 9 Pro's USB-C port provides sufficient power directly. No OTG adapter or powered hub required.

### Moto X4 (Powered Hub Required)

```
                                    ┌─────────────────┐
                                    │   WALL OUTLET   │
                                    └────────┬────────┘
                                             │
                         ┌───────────────────┼───────────────────┐
                         │                   │                   │
                         ▼                   ▼                   │
                 ┌───────────────┐   ┌───────────────┐           │
                 │ Phone Charger │   │ Hub AC Adapter│           │
                 │  (optional)   │   │  (5V/2.5A)    │           │
                 └───────┬───────┘   └───────┬───────┘           │
                         │ USB-C             │ DC barrel         │
                         │ cable             │ connector         │
                         ▼                   ▼                   │
┌─────────────┐   ┌─────────────────────────────────────────┐    │
│             │   │         OTG ADAPTER (B08KPD5S82)        │    │
│   MOTO X4   │   │                                         │    │
│             │   │  ┌─────────┐  ┌─────────┐  ┌─────────┐  │    │
│   ┌─────┐   │   │  │ USB-C   │  │ USB-A   │  │ USB-C   │  │    │
│   │USB-C│◄──╋───╋──┤ Male    │  │ Female  │  │ Female  │◄─┼────┘
│   │port │   │   │  │ (to     │  │ (for    │  │ (PD     │  │ (optional
│   └─────┘   │   │  │ phone)  │  │ access- │  │ charge) │  │  charging)
│             │   │  └─────────┘  └────┬────┘  └─────────┘  │
└─────────────┘   │                    │                    │
                  └────────────────────┼────────────────────┘
                                       │
                                       │ Hub's USB-A male cable
                                       │ (upstream to host)
                                       ▼
                  ┌────────────────────────────────────────┐
                  │        SABRENT POWERED USB HUB         │
                  │              (B00TPMEOYM)              │
                  │                                        │
                  │  ┌──────────┐    ┌──────────────────┐  │
                  │  │ Upstream │    │ USB-A Ports      │  │
                  │  │ Port     │    │ ┌──┐ ┌──┐ ┌──┐   │  │
                  │  │ (input)  │    │ │1 │ │2 │ │3 │   │  │
                  │  └──────────┘    │ └┬─┘ └──┘ └──┘   │  │
                  │                  └──┼───────────────┘  │
                  │  ┌──────────┐       │                  │
                  │  │ DC Power │◄──────┼── (from AC      │
                  │  │ Input    │       │    adapter)     │
                  │  └──────────┘       │                  │
                  └────────────────────┼───────────────────┘
                                       │
                                       │ Mini-Din 7-pin cable
                                       │ (B0DRWK7V76)
                                       │ USB-A male end
                                       ▼
                              ┌─────────────────┐
                              │  iRIG PRO I/O   │
                              │                 │
                              │  ┌───────────┐  │
                              │  │ Mini-Din  │◄─┼── 7-pin end
                              │  │ 7-pin     │  │   of cable
                              │  │ port      │  │
                              │  └───────────┘  │
                              │                 │
                              │  ┌───────────┐  │
                              │  │ BLUE LED  │  │ ← Should be
                              │  │    ●      │  │   BRIGHT BLUE
                              │  └───────────┘  │
                              │                 │
                              └─────────────────┘
```

## Connection Summary (Moto X4)

1. **Adapter USB-C male** → **Moto X4 USB-C port**
2. **Hub's USB-A male cable** → **Adapter's USB-A female port**
3. **Hub's DC power input** → **Hub's AC adapter** → **Wall outlet**
4. **Mini-Din cable USB-A end** → **Hub's USB-A port**
5. **Mini-Din cable 7-pin end** → **iRig Pro I/O**
6. *(Optional)* **Phone charger** → **Adapter's USB-C female port**

## Troubleshooting

### iRig Pro I/O LED Status

| LED Color | Status | Meaning |
|-----------|--------|---------|
| Bright Blue | OK | Full USB power, device ready |
| Faded Blue | Problem | Insufficient USB power |
| No Light | Problem | No USB power at all |

### Common Issues

**Faded blue light on iRig:**
- Cause: Phone's USB OTG port cannot provide enough power (~500mA needed)
- Solution: Use a powered USB hub with its own AC adapter

**No light on iRig:**
- Check all cable connections
- Verify powered hub's AC adapter is plugged in
- Try a different USB-A port on the hub

**Phone not charging:**
- Ensure PD charger is connected to adapter's USB-C female port
- Some older phones may not support simultaneous OTG + charging

### Moto X4 USB Settings

For best compatibility on Moto X4:
1. Go to **Settings → Developer options**
2. Set **Default USB configuration** to **"No file transfer"**
3. Disable **USB debugging** if not needed

## Why Older Phones Need a Powered Hub

The iRig Pro I/O requires approximately 500mA of USB bus power. Newer phones (like Pixel 9 Pro) have robust USB-C Power Delivery that can supply this through an OTG adapter. Older phones (like Moto X4 from 2017) have limited USB OTG power output, often only 100-150mA, which results in the faded blue LED indicating insufficient power.

A powered USB hub with its own AC adapter provides independent 500mA+ per port, bypassing the phone's USB power limitations entirely.

## Multi-Camera SMPTE Timecode Workflow

In a Kanaha multi-camera production:
1. Each camera device records SMPTE timecode on an audio channel via iRig Pro I/O
2. Timecode is generated by a Tentacle Sync or similar device
3. All cameras record independently with their own timecode track
4. Post-production software aligns all footage using the recorded timecode
5. This achieves frame-accurate synchronization without real-time coordination

## References

- Kanaha Multi-Camera Deployment System: `MULTI_CAMERA_DEPLOYMENT_SYSTEM.md`
- IK Multimedia iRig Pro I/O: https://www.ikmultimedia.com/products/irigproio/
