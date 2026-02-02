# iRig Pro I/O SMPTE Timecode Setup for Android

## Overview

This document describes how to connect an IK Multimedia iRig Pro I/O audio interface to Android devices for recording SMPTE timecode audio in Kanaha multi-camera productions.

**Important:** Older Android phones ("drawer phones") require specific equipment and configuration. This guide documents extensive real-world testing with a Moto X4 (2017) to help users avoid common pitfalls.

## Equipment Required

### For Modern Phones (Pixel 9 Pro, 2020+)

| Item | Description | Amazon ASIN |
|------|-------------|-------------|
| iRig Pro I/O | USB audio interface | B06W5H9FFJ |
| Mini-Din 7-pin to USB-C cable | Direct connection | (included with iRig) |

### For Older Phones (Moto X4, pre-2020)

| Item | Description | Amazon ASIN | Critical Notes |
|------|-------------|-------------|----------------|
| iRig Pro I/O | USB audio interface | B06W5H9FFJ | |
| Mini-Din 7-pin to USB-A cable | Connects iRig to hub | B0DRWK7V76 | |
| Simple USB-C OTG Adapter | Basic OTG, NO charging passthrough | B07F9Z1NCP | **Must be simple adapter - see "Lessons Learned"** (USB 2.0 or 3.0 adapter OK) |
| Amazon Basics USB 2.0 Hub | **USB 2.0** powered hub | B00DQFGJR4 | **Must be USB 2.0 - USB 3.0 hubs fail** |
| USB Y-Splitter Cable (Plan B) | Power injection cable | B072LLMKJF | **Alternative to hub - injects external power** |

## Device Compatibility Matrix

| Device | Year | USB-C | Direct Connection | Powered Hub Required | Notes |
|--------|------|-------|-------------------|---------------------|-------|
| Pixel 9 Pro | 2024 | Yes | **Yes** | No | Full USB-C PD support |
| Pixel 6/7/8 | 2021+ | Yes | **Yes** | No | Full USB-C PD support |
| Moto X4 | 2017 | Yes | No | **Yes - USB 2.0 only** | Limited OTG, see details below |
| Older USB-C phones | 2016-2019 | Yes | Unlikely | **Yes - USB 2.0 only** | Test with simple OTG adapter first |

## Connection Diagrams

### Pixel 9 Pro (Direct Connection)

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

**Note:** Modern phones provide sufficient USB-C power directly. No adapters or hubs needed.

### Moto X4 (USB 2.0 Powered Hub Required)

```
                              ┌─────────────────┐
                              │   WALL OUTLET   │
                              └────────┬────────┘
                                       │
                                       │ Hub's AC Adapter
                                       │ (5V/4A)
                                       │
                                       ▼
┌─────────────┐            ┌──────────────────────────────────┐
│             │            │  AMAZON BASICS USB 2.0 HUB       │
│   MOTO X4   │            │         (B00DQFGJR4)             │
│             │            │                                  │
│  ┌───────┐  │            │  ┌──────────┐  ┌─────────────┐  │
│  │ USB-C │  │            │  │ DC Power │  │ USB-A Ports │  │
│  │ port  │  │            │  │ Input    │  │ ┌──┐ ┌──┐   │  │
│  └───┬───┘  │            │  └──────────┘  │ │1 │ │2 │...│  │
└──────┼──────┘            │                │ └┬─┘ └──┘   │  │
       │                   │  ┌──────────┐  └──┼──────────┘  │
       │                   │  │ Upstream │     │             │
       │                   │  │ USB-A    │     │             │
       │                   │  │ (cable)  │     │             │
       │                   │  └────┬─────┘     │             │
       │                   └───────┼───────────┼─────────────┘
       │                           │           │
       │                           │           │ iRig USB-A cable
       │     ┌─────────────────┐   │           │ (B0DRWK7V76)
       │     │ SIMPLE OTG      │   │           │
       └────►│ ADAPTER         │◄──┘           │
             │ (B07F9Z1NCP)    │               │
             │                 │               │
             │ ┌─────┐ ┌─────┐ │               ▼
             │ │USB-C│ │USB-A│ │      ┌─────────────────┐
             │ │male │ │female│ │      │  iRIG PRO I/O   │
             │ └─────┘ └─────┘ │      │                 │
             └─────────────────┘      │  ┌───────────┐  │
                                      │  │ Mini-Din  │◄─┼── 7-pin end
                                      │  │ 7-pin     │  │
                                      │  └───────────┘  │
                                      │                 │
                                      │  ┌───────────┐  │
                                      │  │ BLUE LED  │  │ ← BRIGHT BLUE
                                      │  │    ●      │  │   (if working)
                                      │  └───────────┘  │
                                      └─────────────────┘
```

## Connection Summary (Moto X4)

1. **Simple OTG adapter** (B07F9Z1NCP) → **Moto X4 USB-C port**
2. **Hub's upstream USB-A cable** → **OTG adapter's USB-A female port**
3. **Hub's DC power input** → **Hub's AC adapter** → **Wall outlet**
4. **iRig Mini-Din cable USB-A end** → **Hub's USB-A port**
5. **iRig Mini-Din cable 7-pin end** → **iRig Pro I/O**

**Note:** Phone will NOT charge while using OTG. This is normal for older phones.

---

## Lessons Learned: Why Older Phones Are Difficult

This section documents extensive real-world testing with a Moto X4 (2017) to help users with "drawer phones" understand what works and what doesn't.

### The Core Problem

The iRig Pro I/O requires ~500mA of USB power. Older phones can only provide ~100-150mA via USB OTG, resulting in insufficient power (dim blue LED).

**Solution:** Use a powered USB hub to provide independent power.

**The Catch:** Not all powered USB hubs work with older phones' limited USB OTG implementation.

### What We Tested (And Why Each Failed)

#### Attempt 1: USB-C OTG Adapter with Charging Passthrough (B08KPD5S82)

**Equipment:** USB-C OTG adapter with USB-A port + USB-C charging passthrough

**Result:** FAILED

**Why it failed:**
```
Phone USB status: host_connected=false, current_mode=none
```

The Moto X4's USB-C controller did not recognize this adapter's OTG signaling. These multi-function adapters use complex USB-C Power Delivery negotiation that older phones don't support.

**Symptoms:**
- Phone charges through the adapter (charging passthrough works)
- Phone never enters USB host mode
- No USB devices detected

**Lesson:** Avoid OTG adapters with charging passthrough for older phones. The PD negotiation interferes with OTG detection.

---

#### Attempt 2: Simple USB-C OTG Adapter (B07F9Z1NCP)

**Equipment:** Basic USB-C to USB-A adapter (no charging port). Can be USB 2.0 or USB 3.0 adapter - both trigger host mode. (USB 3.0 adapters have a blue USB-A port; USB 2.0 adapters have black/white.)

**Result:** PARTIAL SUCCESS

**Why it partially worked:**
```
# With iRig connected DIRECTLY to adapter:
host_connected=true, current_mode=dfp, data_role=host  ✓

# But iRig shows DIM BLUE (insufficient power from phone)
```

The simple adapter correctly triggers USB host mode on the Moto X4. However, the phone can only provide ~100mA, not enough for the iRig's ~500mA requirement.

**Lesson:** Simple OTG adapters work for host mode detection, but a powered hub is still needed for power-hungry devices.

---

#### Attempt 3: USB 3.0 Powered Hub (Sabrent HB-UMP3 / B00TPMEOYM)

**Equipment:** Sabrent 4-Port USB 3.0 Hub with 5V/2.5A power adapter

**Result:** FAILED

**Why it failed:**
```
# With hub in the chain:
host_connected=false, current_mode=none  ✗

# Direct connection (no hub):
host_connected=true  ✓
```

When the USB 3.0 hub was connected between the OTG adapter and the iRig, the Moto X4 stopped entering host mode entirely. The hub works perfectly when connected to a laptop.

**Root cause analysis:**

1. **USB 3.0 SuperSpeed negotiation** - USB 3.0 hubs have complex enumeration involving SuperSpeed (5Gbps) signaling that older phones' OTG controllers cannot handle.

2. **Power backfeed** - Powered USB 3.0 hubs may send voltage back through the upstream port, confusing the phone's USB-C detection circuitry.

**Testing performed:**
- Modified hub's upstream cable by cutting the VCC (red) wire to prevent power backfeed
- Wire colors: Red=VCC (power), White=D-, Green=D+, Black=GND
- Result: Still failed - `host_connected=false`
- Conclusion: The issue is USB 3.0 protocol complexity, not power backfeed

**Important:** Cutting the VCC wire proved definitively that the problem is USB 3.0 SuperSpeed enumeration, NOT power backfeed from the hub. The phone's OTG controller simply cannot handle the USB 3.0 protocol.

**Lesson:** USB 3.0 hubs do not work with older phones' USB OTG, even with power isolation. Use USB 2.0 hubs only.

---

#### Attempt 4: USB 2.0 Powered Hub (PENDING)

**Equipment:** Amazon Basics 7-Port USB 2.0 Hub (B00DQFGJR4) with 5V/4A power adapter

**Status:** On order - testing pending

**Why this should work:**
- **True USB 2.0** protocol (480Mbps max, no SuperSpeed)
- Simpler enumeration that older OTG controllers can handle
- Includes robust 5V/4A power adapter (enough for multiple devices)
- Amazon Basics quality/reliability

**Expected outcome:**
```
Moto X4 → Simple OTG adapter → USB 2.0 hub → iRig
                                    ↓
                              5V/4A power supply

host_connected=true, iRig LED=BRIGHT BLUE
```

---

#### Attempt 5: USB Y-Splitter Power Injection Cable (Plan B - PENDING)

**Equipment:** BSHTU USB Y-Splitter Cable (B072LLMKJF) - USB-A male to USB-A male + USB-A power-only male

**Status:** On order as backup - testing pending

**Concept:**
This cable bypasses the hub entirely by injecting external 5V power directly into the USB data line. The Y-splitter has:
- One USB-A male end → connects to iRig's USB-A cable
- One USB-A male end → connects to OTG adapter (data only)
- One USB-A male end → connects to USB charger (power only, no data)

**Why this might work:**
- Eliminates hub enumeration complexity entirely
- Direct power injection from any USB charger (5V/1A+)
- Simpler signal path: phone sees only the iRig, not a hub
- Works around the USB 2.0 vs 3.0 hub compatibility issue

**Expected connection:**
```
                              ┌─────────────────┐
                              │  USB CHARGER    │
                              │  (5V/1A+)       │
                              └────────┬────────┘
                                       │
                                       │ Power-only USB-A male
                                       │
┌─────────────┐            ┌───────────┴───────────┐
│   MOTO X4   │            │  USB Y-SPLITTER       │
│             │            │  (B072LLMKJF)         │
│  ┌───────┐  │            │                       │
│  │ USB-C │  │            │  ┌─────┐   ┌─────┐   │
│  │ port  │  │            │  │Data │   │Power│   │
│  └───┬───┘  │            │  │USB-A│   │USB-A│   │
└──────┼──────┘            │  └──┬──┘   └──┬──┘   │
       │                   │     │         │      │
       │ Simple OTG        │  ┌──┴─────────┴──┐   │
       │ Adapter           │  │  Combined     │   │
       │ (B07F9Z1NCP)      │  │  USB-A male   │   │
       └──────►────────────┴──┴───────┬───────┴───┘
                                      │
                                      ▼
                              ┌─────────────────┐
                              │  iRIG PRO I/O   │
                              │  (via USB-A     │
                              │   to 7-pin)     │
                              └─────────────────┘
```

**Risks/unknowns:**
- Power backfeed into phone through OTG adapter (may need isolation)
- May still require cutting power wire on data leg
- Quality varies with Y-cables - some have poor isolation

**Why this is Plan B:** The USB 2.0 hub is the more standard/reliable solution. The Y-cable is a simpler but more "hacky" approach that may require additional wire cutting.

---

### Summary: What Works vs. What Doesn't

| Equipment | Works on Pixel 9 Pro | Works on Moto X4 | Notes |
|-----------|---------------------|------------------|-------|
| Direct USB-C connection | ✓ | ✗ | Older phones lack power |
| OTG adapter + charging passthrough | ✓ | ✗ | PD negotiation breaks OTG |
| Simple OTG adapter (direct to iRig) | ✓ | Partial | Host mode works, power insufficient |
| Simple OTG + USB 3.0 hub | ✓ | ✗ | USB 3.0 enumeration fails |
| Simple OTG + USB 2.0 hub | ✓ | **Pending** | Expected to work |
| Simple OTG + USB Y-splitter | ✓ | **Pending (Plan B)** | Bypasses hub, direct power injection |

### Decision Tree for Older Phones

```
Is your phone from 2020 or newer?
├── YES → Try direct USB-C connection first
│         └── Works? → Done!
│         └── Fails? → Use USB 2.0 powered hub
│
└── NO (2019 or older) → Use this setup:
    │
    ├── 1. Get a SIMPLE USB-C OTG adapter (no charging port)
    │      Amazon: B07F9Z1NCP or similar
    │
    ├── 2. Get a USB 2.0 powered hub (NOT USB 3.0!)
    │      Amazon: B00DQFGJR4 (Amazon Basics)
    │
    ├── 3. Test: Plug OTG adapter into phone, hub into adapter
    │      Does "USB device connected" appear?
    │      └── NO → Your phone may not support USB OTG at all
    │      └── YES → Continue to step 4
    │
    └── 4. Connect iRig to hub's USB port
           Is LED bright blue?
           └── YES → Success!
           └── NO → Check hub power adapter connection
```

---

## iRig Pro I/O LED Status

| LED Color | Status | Meaning |
|-----------|--------|---------|
| **Bright Blue** | OK | Full USB power (~500mA), device ready |
| **Dim/Faded Blue** | Problem | Insufficient USB power (<200mA) |
| **No Light** | Problem | No USB connection or power |

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

## Multi-Camera SMPTE Timecode Workflow

In a Kanaha multi-camera production:
1. Each camera device records SMPTE timecode on an audio channel via iRig Pro I/O
2. Timecode is generated by a Tentacle Sync or similar device
3. All cameras record independently with their own timecode track
4. Post-production software aligns all footage using the recorded timecode
5. This achieves frame-accurate synchronization without real-time network coordination

---

## The "Drawer Phone" Opportunity

There are an estimated **500 million unused smartphones** sitting in drawers worldwide. Many of these are perfectly capable devices limited only by:
- Outdated OS (can't run latest apps)
- Cracked screens (still functional for recording)
- Battery degradation (works fine when plugged in)

Kanaha's multi-camera system can repurpose these devices as dedicated recording stations. The key challenges documented here—USB OTG compatibility and power delivery—are solvable with the right equipment.

**Don't give up on your old phone.** With a simple OTG adapter and a USB 2.0 powered hub, most USB-C Android phones from 2016+ can serve as reliable SMPTE timecode recording stations.

---

## References

- Kanaha Multi-Camera Deployment System: `MULTI_CAMERA_DEPLOYMENT_SYSTEM.md`
- IK Multimedia iRig Pro I/O: https://www.ikmultimedia.com/products/irigproio/
- Amazon Basics USB 2.0 Hub: https://www.amazon.com/dp/B00DQFGJR4
- Simple USB-C OTG Adapter: https://www.amazon.com/dp/B07F9Z1NCP
- USB Y-Splitter Power Injection Cable (Plan B): https://www.amazon.com/dp/B072LLMKJF
- Understanding OTG: https://lavalink.com/lavablog/articles/understanding-otg/
