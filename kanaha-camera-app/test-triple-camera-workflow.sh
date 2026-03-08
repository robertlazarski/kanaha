#!/bin/bash
#
# Kanaha Triple-Camera Workflow Test Script
# Tests simultaneous 3-camera recording.
#
# Cameras:
#   - Pixel 9 Pro      (192.168.1.182 / Android_S7WQGJDR.local)
#   - Moto G 2025      (192.168.1.95  / Android_HKF1IJXK.local)
#   - Moto G 5G 2024   (192.168.1.170 / Android_MBWSDUFS.local)
#
# Prerequisites:
#   - All phones powered on with Kanaha app running (camera preview active)
#   - All phones connected to same WiFi network with mDNS enabled
#   - mTLS certificates deployed on phones
#
# Usage:
#   ./test-triple-camera-workflow.sh [options]
#
# Options:
#   --duration <seconds>   Recording duration (default: 10)
#   --clip-name <name>     Clip name prefix (default: triple_test)
#   --skip-transfer        Skip SFTP transfer step
#   --use-ip               Use IP addresses instead of mDNS names
#   --discover             Run mDNS discovery and exit
#   --pixel-only           Only test Pixel 9 Pro
#   --motog-only           Only test Moto G 2025
#   --motog5g-only         Only test Moto G 5G 2024
#
# SFTP Transfer Speed Notes:
#   Typical WiFi SFTP speed: ~2-3 MB/s (SSH overhead limits throughput)
#   Max transfer in 20 min timeout: ~2.4-3.6 GB per camera
#
#   Recording bitrate estimates (varies by scene complexity):
#     1080p 30fps: ~10-20 MB/min  -> 10 min = 100-200 MB  (OK)
#     4K 30fps:    ~40-80 MB/min  -> 10 min = 400-800 MB  (OK)
#     4K 60fps:    ~80-150 MB/min -> 10 min = 800MB-1.5GB (OK)
#
#   For large files (>3GB), use --skip-transfer and: adb pull <path> /tmp/
#

set -e

# Configuration
SSL=$HOME/repos/kanaha/kanaha-camera-app/app/src/main/assets/ssl

# mDNS hostnames (discovered via avahi)
PIXEL_MDNS="Android_S7WQGJDR.local"
MOTOG_MDNS="Android_HKF1IJXK.local"
MOTOG5G_MDNS="Android_MBWSDUFS.local"

# Fallback IP addresses
PIXEL_IP="192.168.1.182"
MOTOG_IP="192.168.1.95"
MOTOG5G_IP="192.168.1.170"

# ADB serial addresses (for sidecar pull — sidecar is in app's external files dir,
# not DCIM, so ADB is simpler than sftpTransfer API)
PIXEL_ADB="192.168.1.182:5555"
MOTOG_ADB="192.168.1.95:36181"
MOTOG5G_ADB="192.168.1.170:37797"
SIDECAR_DEVICE_PATH="/storage/emulated/0/Android/data/org.kanaha.camera/files/kanaha_recording_start.json"

# Human-readable names
PIXEL_NAME="Pixel 9 Pro"
MOTOG_NAME="Moto G 2025"
MOTOG5G_NAME="Moto G 5G 2024"

# Default settings
RECORD_DURATION=60
CLIP_NAME="triple_test"
SKIP_TRANSFER=false
TEST_PIXEL=true
TEST_MOTOG=true
TEST_MOTOG5G=true
USE_IP=false
DISCOVER_ONLY=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --duration)    RECORD_DURATION="$2"; shift 2 ;;
        --clip-name)   CLIP_NAME="$2"; shift 2 ;;
        --skip-transfer) SKIP_TRANSFER=true; shift ;;
        --use-ip)      USE_IP=true; shift ;;
        --discover)    DISCOVER_ONLY=true; shift ;;
        --pixel-only)  TEST_MOTOG=false; TEST_MOTOG5G=false; shift ;;
        --motog-only)  TEST_PIXEL=false; TEST_MOTOG5G=false; shift ;;
        --motog5g-only) TEST_PIXEL=false; TEST_MOTOG=false; shift ;;
        --help)
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --duration <seconds>   Recording duration (default: 10)"
            echo "  --clip-name <name>     Clip name prefix (default: triple_test)"
            echo "  --skip-transfer        Skip SFTP transfer step"
            echo "  --use-ip               Use IP addresses instead of mDNS names"
            echo "  --discover             Run mDNS discovery and exit"
            echo "  --pixel-only           Only test Pixel 9 Pro"
            echo "  --motog-only           Only test Moto G 2025"
            echo "  --motog5g-only         Only test Moto G 5G 2024"
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# Function to discover cameras via mDNS
discover_cameras() {
    echo -e "${CYAN}=== mDNS Camera Discovery ===${NC}"
    echo ""

    if ! command -v avahi-browse &> /dev/null; then
        echo -e "${YELLOW}avahi-browse not found. Install with: sudo apt install avahi-utils${NC}"
        return 1
    fi

    echo "Scanning for Kanaha cameras (_https._tcp)..."
    echo ""

    local results
    results=$((timeout 5 avahi-browse -rp _https._tcp 2>/dev/null || true) | grep "^=")

    if [ -z "$results" ]; then
        echo -e "${YELLOW}No mDNS services found. Ensure cameras are running.${NC}"
        return 1
    fi

    echo "$results" | while IFS=';' read -r status iface proto name type domain hostname addr port txt; do
        [[ "$txt" != *"api=kanaha-camera-control"* ]] && continue
        dev_name=$(echo "$txt" | grep -oP 'name=\K[^\s"]+' | head -1)
        model=$(echo "$txt" | grep -oP 'model=\K[^\s"]+' | head -1)
        echo -e "  ${GREEN}${dev_name}${NC} | ${model} | ${addr} | ${hostname}"
    done

    echo ""
}

# Function to get camera address (mDNS or IP fallback)
get_camera_address() {
    local mdns_name=$1
    local fallback_ip=$2

    if $USE_IP; then
        echo "$fallback_ip"
        return
    fi

    if timeout 2 avahi-resolve -n "$mdns_name" &>/dev/null; then
        echo "$mdns_name"
    else
        echo "$fallback_ip"
    fi
}

# Function to make API call
api_call() {
    local camera=$1
    local endpoint=$2
    local data=$3
    local timeout=${4:-30}

    curl -sk --http2 --max-time "$timeout" \
        --cert "$SSL/client.crt" \
        --key "$SSL/client.key" \
        --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d "$data" \
        "https://${camera}:8443/services/CameraControlService/${endpoint}" 2>&1
}

# Function to check camera TCP connectivity
check_camera() {
    local name=$1
    local address=$2

    echo -n "  $name ($address)... "
    if timeout 5 bash -c "echo >/dev/tcp/${address%:*}/8443" 2>/dev/null; then
        echo -e "${GREEN}OK${NC}"
        return 0
    else
        echo -e "${RED}NOT REACHABLE${NC}"
        return 1
    fi
}

# Function to get and print camera status
get_status() {
    local name=$1
    local address=$2

    local response
    response=$(api_call "$address" "getStatus" '{"action":"getStatus"}')

    if echo "$response" | grep -q '"success": true'; then
        local battery storage state is_recording gps_age
        battery=$(echo "$response" | grep -o '"battery_level": *[0-9]*' | grep -o '[0-9]*') || true
        storage=$(echo "$response" | grep -o '"storage_available_mb": *[0-9]*' | grep -o '[0-9]*') || true
        state=$(echo "$response" | grep -o '"state": *"[^"]*"' | cut -d'"' -f4) || true
        is_recording=$(echo "$response" | grep -o '"is_recording": *[a-z]*' | grep -o 'true\|false') || true
        gps_age=$(echo "$response" | grep -o '"gps_age_ms": *[0-9]*' | grep -o '[0-9]*') || true
        local gps_info=""
        if [[ -n "$gps_age" ]]; then
            if [[ $gps_age -lt 5000 ]]; then
                gps_info=" | ${GREEN}GPS ${gps_age}ms${NC}"
            elif [[ $gps_age -lt 30000 ]]; then
                gps_info=" | ${YELLOW}GPS ${gps_age}ms${NC}"
            else
                gps_info=" | GPS stale(${gps_age}ms)"
            fi
        fi
        echo -e "  $name: ${GREEN}$state${NC} | Battery: ${battery}% | Storage: ${storage}MB | Recording: $is_recording${gps_info}"
        return 0
    else
        echo -e "  $name: ${RED}Failed to get status${NC}"
        echo "    Response: $response"
        return 1
    fi
}

# Function to start recording
# Usage: start_recording <name> <address> <clip> [start_at_ms]
# start_at_ms: optional UTC epoch ms for scheduled simultaneous start
start_recording() {
    local name=$1
    local address=$2
    local clip=$3
    local start_at=${4:-0}

    local payload="{\"action\":\"startRecording\",\"clip_name\":\"$clip\""
    if [[ $start_at -gt 0 ]]; then
        payload="${payload},\"start_at\":$start_at"
    fi
    payload="${payload}}"

    local response
    response=$(api_call "$address" "startRecording" "$payload")

    if echo "$response" | grep -q '"success": true'; then
        if echo "$response" | grep -q '"scheduled": true'; then
            local delay
            delay=$(echo "$response" | grep -o '"delay_ms": *[0-9]*' | grep -o '[0-9]*') || true
            echo -e "  $name: ${GREEN}Recording scheduled${NC} (fires in ${delay}ms)"
        else
            echo -e "  $name: ${GREEN}Recording started${NC}"
        fi
        return 0
    else
        echo -e "  $name: ${RED}Failed to start recording${NC}"
        echo "    Response: $response"
        return 1
    fi
}

# Function to stop recording
stop_recording() {
    local name=$1
    local address=$2

    local response
    response=$(api_call "$address" "stopRecording" '{"action":"stopRecording"}')

    if echo "$response" | grep -q '"success": true'; then
        echo -e "  $name: ${GREEN}Recording stopped${NC}"
        return 0
    else
        echo -e "  $name: ${RED}Failed to stop recording${NC}"
        echo "    Response: $response"
        return 1
    fi
}

# Function to list files
list_files() {
    local name=$1
    local address=$2

    local response
    response=$(api_call "$address" "listFiles" '{"action":"listFiles"}')

    if echo "$response" | grep -q '"success": true'; then
        local file_count total_size total_size_mb
        file_count=$(echo "$response" | grep -o '"file_count": *[0-9]*' | grep -o '[0-9]*') || true
        total_size=$(echo "$response" | grep -o '"total_size": *[0-9]*' | grep -o '[0-9]*') || true
        total_size_mb=$((total_size / 1024 / 1024))
        echo -e "  $name: ${GREEN}$file_count files${NC} (${total_size_mb}MB total)"
        echo "$response" | grep -o '"filename": *"[^"]*"' | head -3 | while read -r line; do
            echo "    - $(echo "$line" | cut -d'"' -f4)"
        done
        return 0
    else
        echo -e "  $name: ${YELLOW}Could not list files${NC}"
        return 1
    fi
}

# Function to transfer files via SFTP
transfer_files() {
    local name=$1
    local address=$2
    local destination=$3
    local pattern=${4:-"*.mp4"}

    local response
    response=$(api_call "$address" "sftpTransfer" \
        "{\"action\":\"sftpTransfer\",\"storage_server_id\":\"control\",\"video_filename\":\"$pattern\",\"destination_folder\":\"$destination\"}" \
        1200)

    if echo "$response" | grep -q '"success": true'; then
        return 0
    else
        return 1
    fi
}

# Play software slate: send a synchronized tone to all active cameras + laptop speaker.
# All cameras receive playTone(start_at=T) simultaneously; laptop plays at the same T.
# T is written to /tmp/kanaha_slate_at.txt so parseWithoutLTC.sh can use it for
# ~1–5 ms onset detection instead of the ~100–200 ms NTP clock correction.
#
# Requires PIXEL_OK/MOTOG_OK/MOTOG5G_OK and *_ADDR to be set (done in Step 1).
# Call AFTER recording has started so the tone appears inside each file.
#
# Usage: play_slate_all [frequency_hz] [duration_ms] [lead_ms]
#   frequency_hz  tone frequency (default 1000)
#   duration_ms   tone duration  (default 500)
#   lead_ms       schedule the tone this far in the future (default 3000)
play_slate_all() {
    local freq=${1:-1000}
    local dur=${2:-500}
    local lead=${3:-3000}

    local start_at=$(( $(date +%s%3N) + lead ))
    echo "$start_at" > /tmp/kanaha_slate_at.txt

    local dur_s
    dur_s=$(awk "BEGIN {printf \"%.3f\", $dur / 1000.0}")

    echo -e "  Slate: ${freq}Hz for ${dur}ms — fires in ${lead}ms (start_at=${start_at})"

    local payload="{\"action\":\"playTone\",\"frequency\":${freq},\"duration_ms\":${dur},\"start_at\":${start_at}}"
    $PIXEL_OK   && api_call "$PIXEL_ADDR"   "playTone" "$payload" 5 >/dev/null &
    $MOTOG_OK   && api_call "$MOTOG_ADDR"   "playTone" "$payload" 5 >/dev/null &
    $MOTOG5G_OK && api_call "$MOTOG5G_ADDR" "playTone" "$payload" 5 >/dev/null &

    # Play the same tone on the laptop speaker at the same scheduled moment
    local now
    now=$(date +%s%3N)
    local delay_s
    delay_s=$(awk "BEGIN {printf \"%.3f\", ($start_at - $now) / 1000.0}")
    if command -v ffplay &>/dev/null; then
        (sleep "$delay_s" && ffplay -nodisp -autoexit -loglevel quiet \
            -f lavfi -i "sine=frequency=${freq}:duration=${dur_s}") &
    else
        echo -e "  ${YELLOW}ffplay not found — laptop speaker slate skipped (cameras still slated)${NC}"
    fi

    wait
    echo -e "  ${GREEN}Slate dispatched — /tmp/kanaha_slate_at.txt written${NC}"
}

# Transfer the sync sidecar JSON from a camera to the local destination directory.
# kanaha_recording_start.json is written by CameraControlReceiver.writeSyncSidecar()
# at the moment recording starts — the Kanaha analog of the BWF Time Reference in
# broadcast WAV files (parseLTC.sh f8_start_timecode). parseWithoutLTC.sh reads it
# for ms-precision start times instead of 1-second filename timestamps.
transfer_sidecar() {
    local adb_serial=$1
    local destination=$2
    # Sidecar is in app's external files dir (not DCIM) due to scoped storage —
    # use ADB pull instead of sftpTransfer API.
    adb -s "$adb_serial" pull "$SIDECAR_DEVICE_PATH" "$destination/" >/dev/null 2>&1 || true
}

# ─── Main ───────────────────────────────────────────────────────────────────

echo ""
echo -e "${CYAN}================================================"
echo "  Kanaha Triple-Camera Workflow Test"
echo "================================================${NC}"
echo ""
echo "Configuration:"
echo "  Recording duration: ${RECORD_DURATION}s"
echo "  Clip name prefix:   ${CLIP_NAME}"
echo "  Addressing:         $(if $USE_IP; then echo "IP"; else echo "mDNS"; fi)"
echo "  Cameras:            $(if $TEST_PIXEL; then echo -n "Pixel 9 Pro  "; fi)$(if $TEST_MOTOG; then echo -n "Moto G 2025  "; fi)$(if $TEST_MOTOG5G; then echo -n "Moto G 5G 2024"; fi)"
echo ""

if $DISCOVER_ONLY; then
    discover_cameras
    exit 0
fi

# Step 0: Resolve addresses
echo -e "${BLUE}Step 0: Resolving camera addresses...${NC}"
echo ""

PIXEL_ADDR="";  MOTOG_ADDR="";  MOTOG5G_ADDR=""

$TEST_PIXEL   && PIXEL_ADDR=$(get_camera_address   "$PIXEL_MDNS"   "$PIXEL_IP")
$TEST_MOTOG   && MOTOG_ADDR=$(get_camera_address   "$MOTOG_MDNS"   "$MOTOG_IP")
$TEST_MOTOG5G && MOTOG5G_ADDR=$(get_camera_address "$MOTOG5G_MDNS" "$MOTOG5G_IP")

$TEST_PIXEL   && echo "  $PIXEL_NAME   -> $PIXEL_ADDR"
$TEST_MOTOG   && echo "  $MOTOG_NAME     -> $MOTOG_ADDR"
$TEST_MOTOG5G && echo "  $MOTOG5G_NAME -> $MOTOG5G_ADDR"

# Step 1: Check connectivity
echo ""
echo -e "${BLUE}Step 1: Checking camera connectivity...${NC}"
echo ""

PIXEL_OK=false; MOTOG_OK=false; MOTOG5G_OK=false

$TEST_PIXEL   && [ -n "$PIXEL_ADDR" ]   && check_camera "$PIXEL_NAME"   "$PIXEL_ADDR"   && PIXEL_OK=true   || true
$TEST_MOTOG   && [ -n "$MOTOG_ADDR" ]   && check_camera "$MOTOG_NAME"   "$MOTOG_ADDR"   && MOTOG_OK=true   || true
$TEST_MOTOG5G && [ -n "$MOTOG5G_ADDR" ] && check_camera "$MOTOG5G_NAME" "$MOTOG5G_ADDR" && MOTOG5G_OK=true || true

if ! $PIXEL_OK && ! $MOTOG_OK && ! $MOTOG5G_OK; then
    echo ""
    echo -e "${RED}ERROR: No cameras reachable!${NC}"
    echo "Make sure the Kanaha app is running on the devices."
    exit 1
fi

# Step 2: Get initial status
echo ""
echo -e "${BLUE}Step 2: Getting camera status...${NC}"
echo ""

$PIXEL_OK   && get_status "$PIXEL_NAME"   "$PIXEL_ADDR"
$MOTOG_OK   && get_status "$MOTOG_NAME"   "$MOTOG_ADDR"
$MOTOG5G_OK && get_status "$MOTOG5G_NAME" "$MOTOG5G_ADDR"

# Step 3: Schedule simultaneous recording start on all cameras.
# start_at ensures all cameras fire at the exact same UTC millisecond regardless
# of network delivery timing. The sidecar JSON captures ms-precision start time.
echo ""
echo -e "${BLUE}Step 3: Starting recording on all cameras simultaneously...${NC}"
echo ""

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
CLIP="${CLIP_NAME}_${TIMESTAMP}"
VID_PATTERN="VID_$(date +%Y%m%d)*.mp4"
REC_START_AT=$(( $(date +%s%3N) + 2000 ))   # fire in 2s — enough for requests to arrive

PIXEL_PID=""; MOTOG_PID=""; MOTOG5G_PID=""

$PIXEL_OK   && start_recording "$PIXEL_NAME"   "$PIXEL_ADDR"   "${CLIP}_pixel"   "$REC_START_AT" & PIXEL_PID=$!
$MOTOG_OK   && start_recording "$MOTOG_NAME"   "$MOTOG_ADDR"   "${CLIP}_motog"   "$REC_START_AT" & MOTOG_PID=$!
$MOTOG5G_OK && start_recording "$MOTOG5G_NAME" "$MOTOG5G_ADDR" "${CLIP}_motog5g" "$REC_START_AT" & MOTOG5G_PID=$!

wait ${PIXEL_PID:-}   2>/dev/null || true
wait ${MOTOG_PID:-}   2>/dev/null || true
wait ${MOTOG5G_PID:-} 2>/dev/null || true

sleep 2   # wait for the scheduled start to fire — cameras are now rolling

# Step 3 (sync slate + action warning):
# Fires a 1 kHz sync tone on all cameras + laptop speaker early in the recording.
# Two purposes:
#   1. Sync reference — parseWithoutLTC.sh detects the onset in each file for ~1–5 ms
#      inter-camera alignment (SLATE MODE), far better than NTP-only ~100–200 ms.
#   2. Action warning — the tone signals crew/talent that ACTION is in 10 seconds.
echo ""
echo -e "${BLUE}Step 3 (sync slate): Firing sync tone — ACTION in 10 seconds...${NC}"
echo ""
play_slate_all   # schedules tone 3s from now on all cameras + laptop, then returns

# 10-second action countdown (tone has fired, crew has time to get ready)
for ((i=10; i>0; i--)); do
    echo -ne "\r  ACTION IN: ${YELLOW}${i}s${NC}   "
    sleep 1
done
echo -e "\r  ${GREEN}*** ACTION! ***${NC}                    "
echo ""

# Step 4: Filming — record for the requested duration after the action cue
echo -e "${BLUE}Step 4: Filming for ${RECORD_DURATION} seconds...${NC}"
echo ""

for ((i=RECORD_DURATION; i>0; i--)); do
    echo -ne "\r  Recording: ${i}s remaining   "
    sleep 1
done
echo -e "\r  Recording: 0s - ${GREEN}Done${NC}   "

# Step 5: Stop recording simultaneously
echo ""
echo -e "${BLUE}Step 5: Stopping recording on all cameras simultaneously...${NC}"
echo ""

PIXEL_PID=""; MOTOG_PID=""; MOTOG5G_PID=""

$PIXEL_OK   && stop_recording "$PIXEL_NAME"   "$PIXEL_ADDR"   & PIXEL_PID=$!
$MOTOG_OK   && stop_recording "$MOTOG_NAME"   "$MOTOG_ADDR"   & MOTOG_PID=$!
$MOTOG5G_OK && stop_recording "$MOTOG5G_NAME" "$MOTOG5G_ADDR" & MOTOG5G_PID=$!

wait ${PIXEL_PID:-}   2>/dev/null || true
wait ${MOTOG_PID:-}   2>/dev/null || true
wait ${MOTOG5G_PID:-} 2>/dev/null || true

echo ""
echo "  Waiting for files to finalize..."
sleep 3

# Step 6: List files
echo ""
echo -e "${BLUE}Step 6: Listing video files...${NC}"
echo ""

$PIXEL_OK   && list_files "$PIXEL_NAME"   "$PIXEL_ADDR"
$MOTOG_OK   && list_files "$MOTOG_NAME"   "$MOTOG_ADDR"
$MOTOG5G_OK && list_files "$MOTOG5G_NAME" "$MOTOG5G_ADDR"

# Step 7: Transfer files
if ! $SKIP_TRANSFER; then
    echo ""
    echo -e "${BLUE}Step 7: Transferring files to control host...${NC}"
    echo ""
    echo "  Note: WiFi SFTP speed ~2-3 MB/s (max ~3 GB in 20 min timeout)"
    echo ""

    PIXEL_DEST="/tmp/pixel9pro"
    MOTOG_DEST="/tmp/motog2025"
    MOTOG5G_DEST="/tmp/motog5g2024"
    TRANSFER_FAILED=false

    if $PIXEL_OK; then
        echo -n "  $PIXEL_NAME: Transferring ${VID_PATTERN} to ${PIXEL_DEST}... "
        START=$(date +%s)
        if transfer_files "$PIXEL_NAME" "$PIXEL_ADDR" "$PIXEL_DEST" "$VID_PATTERN"; then
            echo -e "${GREEN}OK${NC} ($(($(date +%s) - START))s)"
            transfer_sidecar "$PIXEL_ADB" "$PIXEL_DEST"
        else
            echo -e "${RED}FAILED${NC}"; TRANSFER_FAILED=true
        fi
    fi

    if $MOTOG_OK; then
        echo -n "  $MOTOG_NAME: Transferring ${VID_PATTERN} to ${MOTOG_DEST}... "
        START=$(date +%s)
        if transfer_files "$MOTOG_NAME" "$MOTOG_ADDR" "$MOTOG_DEST" "$VID_PATTERN"; then
            echo -e "${GREEN}OK${NC} ($(($(date +%s) - START))s)"
            transfer_sidecar "$MOTOG_ADB" "$MOTOG_DEST"
        else
            echo -e "${RED}FAILED${NC}"; TRANSFER_FAILED=true
        fi
    fi

    if $MOTOG5G_OK; then
        echo -n "  $MOTOG5G_NAME: Transferring ${VID_PATTERN} to ${MOTOG5G_DEST}... "
        START=$(date +%s)
        if transfer_files "$MOTOG5G_NAME" "$MOTOG5G_ADDR" "$MOTOG5G_DEST" "$VID_PATTERN"; then
            echo -e "${GREEN}OK${NC} ($(($(date +%s) - START))s)"
            transfer_sidecar "$MOTOG5G_ADB" "$MOTOG5G_DEST"
        else
            echo -e "${RED}FAILED${NC}"; TRANSFER_FAILED=true
        fi
    fi

    echo ""
    if $TRANSFER_FAILED; then
        echo -e "  ${YELLOW}Some transfers failed. For large files (>3GB), use ADB:${NC}"
        echo "    adb -s 192.168.1.182:5555 pull /storage/emulated/0/DCIM/OpenCamera/${VID_PATTERN} /tmp/pixel9pro/"
        echo "    adb -t 1                  pull /storage/emulated/0/DCIM/OpenCamera/${VID_PATTERN} /tmp/motog2025/"
        echo "    adb -t 16                 pull /storage/emulated/0/DCIM/OpenCamera/${VID_PATTERN} /tmp/motog5g2024/"
    else
        echo "  Files transferred to:"
        $PIXEL_OK   && echo "    - $PIXEL_NAME:   ${PIXEL_DEST}"
        $MOTOG_OK   && echo "    - $MOTOG_NAME:     ${MOTOG_DEST}"
        $MOTOG5G_OK && echo "    - $MOTOG5G_NAME: ${MOTOG5G_DEST}"

        # Step 8: Delete transferred files from cameras
        echo ""
        echo -e "${BLUE}Step 8: Cleaning up transferred files from cameras...${NC}"
        echo ""

        delete_cam() {
            local name=$1 addr=$2
            echo -n "  $name: Deleting ${VID_PATTERN}... "
            local resp
            resp=$(api_call "$addr" "deleteFiles" "{\"action\":\"deleteFiles\",\"pattern\":\"${VID_PATTERN}\"}" 30) || true
            if echo "$resp" | grep -q '"success": true'; then
                local count
                count=$(echo "$resp" | grep -o '"files_deleted": *[0-9]*' | grep -o '[0-9]*') || true
                echo -e "${GREEN}OK${NC} ($count files)"
            else
                echo -e "${YELLOW}Could not delete${NC}"
            fi
            return 0
        }
        $PIXEL_OK   && delete_cam "$PIXEL_NAME"   "$PIXEL_ADDR"   || true
        $MOTOG_OK   && delete_cam "$MOTOG_NAME"   "$MOTOG_ADDR"   || true
        $MOTOG5G_OK && delete_cam "$MOTOG5G_NAME" "$MOTOG5G_ADDR" || true
    fi
else
    echo ""
    echo -e "${YELLOW}Step 7: SFTP transfer skipped (--skip-transfer)${NC}"
fi

# Summary
echo ""
echo -e "${CYAN}================================================"
echo "  Test Complete"
echo "================================================${NC}"
echo ""
echo "Results:"
$PIXEL_OK   && echo -e "  $PIXEL_NAME   ($PIXEL_ADDR):   ${GREEN}SUCCESS${NC}" || { $TEST_PIXEL   && echo -e "  $PIXEL_NAME:   ${RED}NOT TESTED${NC}"; }
$MOTOG_OK   && echo -e "  $MOTOG_NAME     ($MOTOG_ADDR):     ${GREEN}SUCCESS${NC}" || { $TEST_MOTOG   && echo -e "  $MOTOG_NAME:     ${RED}NOT TESTED${NC}"; }
$MOTOG5G_OK && echo -e "  $MOTOG5G_NAME ($MOTOG5G_ADDR): ${GREEN}SUCCESS${NC}" || { $TEST_MOTOG5G && echo -e "  $MOTOG5G_NAME: ${RED}NOT TESTED${NC}"; }

echo ""
echo "Clip name: $CLIP"
echo "Duration:  ${RECORD_DURATION}s"
echo ""

echo -e "${BLUE}Final camera status:${NC}"
echo ""
$PIXEL_OK   && get_status "$PIXEL_NAME"   "$PIXEL_ADDR"
$MOTOG_OK   && get_status "$MOTOG_NAME"   "$MOTOG_ADDR"
$MOTOG5G_OK && get_status "$MOTOG5G_NAME" "$MOTOG5G_ADDR"
echo ""
