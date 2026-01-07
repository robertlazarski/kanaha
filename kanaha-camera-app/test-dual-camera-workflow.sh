#!/bin/bash
#
# Kanaha Dual-Camera Workflow Test Script
# Tests simultaneous multi-camera recording as documented in MULTI_CAMERA_DEPLOYMENT_SYSTEM.md
#
# Prerequisites:
#   - Phones powered on with Kanaha app running (camera preview active)
#   - Phones connected to same WiFi network with mDNS enabled
#   - mTLS certificates deployed on phones
#
# Usage:
#   ./test-dual-camera-workflow.sh [options]
#
# Options:
#   --duration <seconds>   Recording duration (default: 10)
#   --clip-name <name>     Clip name prefix (default: dual_test)
#   --skip-transfer        Skip SFTP transfer step
#   --pixel-only           Only test Pixel 9 Pro
#   --moto-only            Only test Moto X4
#   --use-ip               Use IP addresses instead of mDNS names
#   --discover             Run mDNS discovery and exit
#

set -e

# Configuration
SSL=$HOME/repos/kanaha/kanaha-camera-app/app/src/main/assets/ssl

# mDNS hostnames (discovered via avahi)
PIXEL_MDNS="pixel9pro.local"
MOTO_MDNS="motox4.local"

# Fallback IP addresses
PIXEL_IP="192.168.8.168"
MOTO_IP="192.168.8.126"

# Default settings
RECORD_DURATION=10
CLIP_NAME="dual_test"
SKIP_TRANSFER=false
TEST_PIXEL=true
TEST_MOTO=true
USE_IP=false
DISCOVER_ONLY=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --duration)
            RECORD_DURATION="$2"
            shift 2
            ;;
        --clip-name)
            CLIP_NAME="$2"
            shift 2
            ;;
        --skip-transfer)
            SKIP_TRANSFER=true
            shift
            ;;
        --pixel-only)
            TEST_MOTO=false
            shift
            ;;
        --moto-only)
            TEST_PIXEL=false
            shift
            ;;
        --use-ip)
            USE_IP=true
            shift
            ;;
        --discover)
            DISCOVER_ONLY=true
            shift
            ;;
        --help)
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --duration <seconds>   Recording duration (default: 10)"
            echo "  --clip-name <name>     Clip name prefix (default: dual_test)"
            echo "  --skip-transfer        Skip SFTP transfer step"
            echo "  --pixel-only           Only test Pixel 9 Pro"
            echo "  --moto-only            Only test Moto X4"
            echo "  --use-ip               Use IP addresses instead of mDNS names"
            echo "  --discover             Run mDNS discovery and exit"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Function to discover cameras via mDNS
discover_cameras() {
    echo -e "${CYAN}=== mDNS Camera Discovery ===${NC}"
    echo ""

    if ! command -v avahi-browse &> /dev/null; then
        echo -e "${YELLOW}avahi-browse not found. Install with: sudo apt install avahi-utils${NC}"
        echo "Falling back to IP addresses..."
        return 1
    fi

    echo "Scanning for Kanaha cameras (_https._tcp)..."
    echo ""

    # Run avahi-browse with timeout
    local services
    services=$(timeout 5 avahi-browse -t -r _https._tcp 2>/dev/null || true)

    if [ -z "$services" ]; then
        echo -e "${YELLOW}No mDNS services found. Ensure cameras are running.${NC}"
        return 1
    fi

    echo "$services" | grep -E "hostname|address|port" | head -20
    echo ""

    # Try to resolve specific hostnames
    echo "Resolving camera hostnames..."

    if timeout 2 avahi-resolve -n "$PIXEL_MDNS" &>/dev/null; then
        local pixel_resolved
        pixel_resolved=$(avahi-resolve -n "$PIXEL_MDNS" 2>/dev/null | awk '{print $2}')
        echo -e "  Pixel 9 Pro: ${GREEN}$PIXEL_MDNS -> $pixel_resolved${NC}"
    else
        echo -e "  Pixel 9 Pro: ${YELLOW}$PIXEL_MDNS not found${NC}"
    fi

    if timeout 2 avahi-resolve -n "$MOTO_MDNS" &>/dev/null; then
        local moto_resolved
        moto_resolved=$(avahi-resolve -n "$MOTO_MDNS" 2>/dev/null | awk '{print $2}')
        echo -e "  Moto X4: ${GREEN}$MOTO_MDNS -> $moto_resolved${NC}"
    else
        echo -e "  Moto X4: ${YELLOW}$MOTO_MDNS not found${NC}"
    fi

    echo ""
    return 0
}

# Function to get camera address (mDNS or IP)
get_camera_address() {
    local mdns_name=$1
    local fallback_ip=$2

    if $USE_IP; then
        echo "$fallback_ip"
        return
    fi

    # Try mDNS resolution
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

    curl -sk --http2 --max-time 15 \
        --cert "$SSL/client.crt" \
        --key "$SSL/client.key" \
        --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d "$data" \
        "https://${camera}:8443/services/CameraControlService/${endpoint}" 2>&1
}

# Function to check camera connectivity
check_camera() {
    local name=$1
    local address=$2

    echo -n "  $name ($address)... "

    # Extract IP/hostname for TCP check
    local host="${address%:*}"

    if timeout 3 bash -c "echo >/dev/tcp/$host/8443" 2>/dev/null; then
        echo -e "${GREEN}OK${NC}"
        return 0
    else
        echo -e "${RED}NOT REACHABLE${NC}"
        return 1
    fi
}

# Function to get camera status
get_status() {
    local name=$1
    local address=$2

    local response
    response=$(api_call "$address" "getStatus" '{"action":"getStatus"}')

    if echo "$response" | grep -q '"success": true'; then
        local device_name battery storage state
        device_name=$(echo "$response" | grep -o '"device_name": *"[^"]*"' | cut -d'"' -f4)
        battery=$(echo "$response" | grep -o '"battery_level": *[0-9]*' | grep -o '[0-9]*')
        storage=$(echo "$response" | grep -o '"storage_available_mb": *[0-9]*' | grep -o '[0-9]*')
        state=$(echo "$response" | grep -o '"state": *"[^"]*"' | cut -d'"' -f4)
        is_recording=$(echo "$response" | grep -o '"is_recording": *[a-z]*' | grep -o 'true\|false')

        echo -e "  $name: ${GREEN}$state${NC} | Battery: ${battery}% | Storage: ${storage}MB | Recording: $is_recording"
        return 0
    else
        echo -e "  $name: ${RED}Failed to get status${NC}"
        echo "    Response: $response"
        return 1
    fi
}

# Function to start recording
start_recording() {
    local name=$1
    local address=$2
    local clip=$3

    local response
    response=$(api_call "$address" "startRecording" "{\"action\":\"startRecording\",\"clip_name\":\"$clip\"}")

    if echo "$response" | grep -q '"success": true'; then
        echo -e "  $name: ${GREEN}Recording started${NC}"
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
        local file_count total_size
        file_count=$(echo "$response" | grep -o '"file_count": *[0-9]*' | grep -o '[0-9]*')
        total_size=$(echo "$response" | grep -o '"total_size": *[0-9]*' | grep -o '[0-9]*')
        total_size_mb=$((total_size / 1024 / 1024))

        echo -e "  $name: ${GREEN}$file_count files${NC} (${total_size_mb}MB total)"

        # Show recent files
        echo "$response" | grep -o '"filename": *"[^"]*"' | head -3 | while read -r line; do
            filename=$(echo "$line" | cut -d'"' -f4)
            echo "    - $filename"
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

    local response
    response=$(api_call "$address" "sftpTransfer" "{\"action\":\"sftpTransfer\",\"storage_server_id\":\"control\",\"video_filename\":\"*.mp4\",\"destination_folder\":\"$destination\"}")

    if echo "$response" | grep -q '"success": true'; then
        echo -e "  $name: ${GREEN}Transfer initiated${NC}"
        return 0
    else
        echo -e "  $name: ${YELLOW}Transfer failed or not configured${NC}"
        echo "    Response: ${response:0:100}..."
        return 1
    fi
}

# Main script
echo ""
echo -e "${CYAN}=============================================="
echo "  Kanaha Dual-Camera Workflow Test"
echo "==============================================${NC}"
echo ""
echo "Configuration:"
echo "  Recording duration: ${RECORD_DURATION}s"
echo "  Clip name: ${CLIP_NAME}"
echo "  Using: $(if $USE_IP; then echo "IP addresses"; else echo "mDNS hostnames"; fi)"
echo ""

# Run discovery if requested
if $DISCOVER_ONLY; then
    discover_cameras
    exit 0
fi

# Step 0: Discover/resolve camera addresses
echo -e "${BLUE}Step 0: Resolving camera addresses...${NC}"
echo ""

PIXEL_ADDR=""
MOTO_ADDR=""

if $TEST_PIXEL; then
    PIXEL_ADDR=$(get_camera_address "$PIXEL_MDNS" "$PIXEL_IP")
fi

if $TEST_MOTO; then
    MOTO_ADDR=$(get_camera_address "$MOTO_MDNS" "$MOTO_IP")
fi

# Step 1: Check connectivity
echo -e "${BLUE}Step 1: Checking camera connectivity...${NC}"
echo ""

PIXEL_OK=false
MOTO_OK=false

if $TEST_PIXEL && [ -n "$PIXEL_ADDR" ]; then
    if check_camera "Pixel 9 Pro" "$PIXEL_ADDR"; then
        PIXEL_OK=true
    fi
fi

if $TEST_MOTO && [ -n "$MOTO_ADDR" ]; then
    if check_camera "Moto X4" "$MOTO_ADDR"; then
        MOTO_OK=true
    fi
fi

if ! $PIXEL_OK && ! $MOTO_OK; then
    echo ""
    echo -e "${RED}ERROR: No cameras reachable!${NC}"
    echo "Make sure the Kanaha app is running on the devices."
    exit 1
fi

# Step 2: Get initial status
echo ""
echo -e "${BLUE}Step 2: Getting camera status...${NC}"
echo ""

if $PIXEL_OK; then
    get_status "Pixel 9 Pro" "$PIXEL_ADDR"
fi

if $MOTO_OK; then
    get_status "Moto X4" "$MOTO_ADDR"
fi

# Step 3: Start recording simultaneously
echo ""
echo -e "${BLUE}Step 3: Starting recording on all cameras...${NC}"
echo ""

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
CLIP="${CLIP_NAME}_${TIMESTAMP}"

# Start both cameras as close together as possible
if $PIXEL_OK; then
    start_recording "Pixel 9 Pro" "$PIXEL_ADDR" "${CLIP}_pixel" &
    PIXEL_PID=$!
fi

if $MOTO_OK; then
    start_recording "Moto X4" "$MOTO_ADDR" "${CLIP}_moto" &
    MOTO_PID=$!
fi

# Wait for both to complete
wait ${PIXEL_PID:-} 2>/dev/null || true
wait ${MOTO_PID:-} 2>/dev/null || true

# Step 4: Wait for recording duration
echo ""
echo -e "${BLUE}Step 4: Recording for ${RECORD_DURATION} seconds...${NC}"
echo ""

# Show countdown
for ((i=RECORD_DURATION; i>0; i--)); do
    echo -ne "\r  Time remaining: ${i}s   "
    sleep 1
done
echo -e "\r  Time remaining: 0s - ${GREEN}Done${NC}   "

# Step 5: Stop recording simultaneously
echo ""
echo -e "${BLUE}Step 5: Stopping recording on all cameras...${NC}"
echo ""

# Stop both cameras as close together as possible
if $PIXEL_OK; then
    stop_recording "Pixel 9 Pro" "$PIXEL_ADDR" &
    PIXEL_PID=$!
fi

if $MOTO_OK; then
    stop_recording "Moto X4" "$MOTO_ADDR" &
    MOTO_PID=$!
fi

# Wait for both to complete
wait ${PIXEL_PID:-} 2>/dev/null || true
wait ${MOTO_PID:-} 2>/dev/null || true

# Give cameras time to finalize files
echo ""
echo "  Waiting for files to finalize..."
sleep 3

# Step 6: List files on both cameras
echo ""
echo -e "${BLUE}Step 6: Listing video files...${NC}"
echo ""

if $PIXEL_OK; then
    list_files "Pixel 9 Pro" "$PIXEL_ADDR"
fi

if $MOTO_OK; then
    list_files "Moto X4" "$MOTO_ADDR"
fi

# Step 7: Transfer files (optional)
if ! $SKIP_TRANSFER; then
    echo ""
    echo -e "${BLUE}Step 7: Transferring files via SFTP...${NC}"
    echo ""

    DEST_FOLDER="/tmp/kanaha/${CLIP}"

    if $PIXEL_OK; then
        transfer_files "Pixel 9 Pro" "$PIXEL_ADDR" "$DEST_FOLDER"
    fi

    if $MOTO_OK; then
        transfer_files "Moto X4" "$MOTO_ADDR" "$DEST_FOLDER"
    fi
else
    echo ""
    echo -e "${YELLOW}Step 7: SFTP transfer skipped (--skip-transfer)${NC}"
fi

# Summary
echo ""
echo -e "${CYAN}=============================================="
echo "  Test Complete"
echo "==============================================${NC}"
echo ""
echo "Results:"

if $PIXEL_OK; then
    echo -e "  Pixel 9 Pro ($PIXEL_ADDR): ${GREEN}SUCCESS${NC}"
else
    echo -e "  Pixel 9 Pro: ${RED}NOT TESTED${NC}"
fi

if $MOTO_OK; then
    echo -e "  Moto X4 ($MOTO_ADDR): ${GREEN}SUCCESS${NC}"
else
    echo -e "  Moto X4: ${RED}NOT TESTED${NC}"
fi

echo ""
echo "Clip name: $CLIP"
echo "Duration: ${RECORD_DURATION}s"
echo ""

# Show final status
echo -e "${BLUE}Final camera status:${NC}"
echo ""

if $PIXEL_OK; then
    get_status "Pixel 9 Pro" "$PIXEL_ADDR"
fi

if $MOTO_OK; then
    get_status "Moto X4" "$MOTO_ADDR"
fi

echo ""
