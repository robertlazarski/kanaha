#!/bin/bash
#
# Kanaha Pixel 9 Pro Camera Control Script
# Single-camera recording workflow for Pixel 9 Pro
#
# Prerequisites:
#   - Phone powered on with Kanaha app running (camera preview active)
#   - Phone connected to WiFi network
#   - mTLS certificates deployed on phone
#
# Usage:
#   ./test-single-camera-workflow.sh [command] [options]
#
# Commands:
#   status           Get camera status (default)
#   record           Start recording
#   stop             Stop recording
#   list             List video files
#   transfer         Transfer files via SFTP
#   workflow         Run full recording workflow
#
# Options:
#   --duration <sec>   Recording duration for workflow (default: 10)
#   --clip <name>      Clip name (default: pixel9_<timestamp>)
#   --use-ip           Use IP address instead of mDNS
#   --verbose          Show full API responses
#
# SFTP Transfer Speed Notes:
#   Typical WiFi SFTP speed: ~2-3 MB/s (SSH overhead limits throughput)
#   Max transfer in 20 min timeout: ~2.4-3.6 GB
#
#   Recording bitrate estimates (varies by scene complexity):
#     1080p 30fps: ~10-20 MB/min  -> 10 min = 100-200 MB  (OK)
#     1080p 60fps: ~15-30 MB/min  -> 10 min = 150-300 MB  (OK)
#     4K 30fps:    ~40-80 MB/min  -> 10 min = 400-800 MB  (OK)
#     4K 60fps:    ~80-150 MB/min -> 10 min = 800MB-1.5GB (OK)
#     4K 60fps:    ~80-150 MB/min -> 20 min = 1.6-3GB     (OK)
#
#   For large files (>3GB), consider: adb pull, USB transfer, or chunked transfers
#

set -e

# Configuration
SSL=$HOME/repos/kanaha/kanaha-camera-app/app/src/main/assets/ssl
PIXEL_MDNS="pixel9pro.local"
PIXEL_IP="192.168.8.168"
PORT=8443

# Default settings
DURATION=10
CLIP_NAME=""
USE_IP=false
VERBOSE=false
COMMAND="status"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        status|record|stop|list|transfer|workflow)
            COMMAND="$1"
            shift
            ;;
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --clip)
            CLIP_NAME="$2"
            shift 2
            ;;
        --use-ip)
            USE_IP=true
            shift
            ;;
        --verbose|-v)
            VERBOSE=true
            shift
            ;;
        --help|-h)
            echo "Kanaha Pixel 9 Pro Camera Control"
            echo ""
            echo "Usage: $0 [command] [options]"
            echo ""
            echo "Commands:"
            echo "  status      Get camera status (default)"
            echo "  record      Start recording"
            echo "  stop        Stop recording"
            echo "  list        List video files"
            echo "  transfer    Transfer files via SFTP"
            echo "  workflow    Run full recording workflow"
            echo ""
            echo "Options:"
            echo "  --duration <sec>   Recording duration for workflow (default: 10)"
            echo "  --clip <name>      Clip name (default: pixel9_<timestamp>)"
            echo "  --use-ip           Use IP address instead of mDNS"
            echo "  --verbose, -v      Show full API responses"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Resolve camera address
get_address() {
    if $USE_IP; then
        echo "$PIXEL_IP"
        return
    fi

    # Try mDNS resolution
    if command -v avahi-resolve &> /dev/null; then
        if timeout 2 avahi-resolve -n "$PIXEL_MDNS" &>/dev/null; then
            echo "$PIXEL_MDNS"
            return
        fi
    fi

    # Fallback to IP
    echo "$PIXEL_IP"
}

# Make API call to camera
# Usage: api_call <endpoint> <json_data> [timeout_seconds]
api_call() {
    local endpoint=$1
    local data=$2
    local timeout=${3:-15}  # Default 15 seconds, override for long operations

    local response
    response=$(curl -sk --http2 --max-time "$timeout" \
        --cert "$SSL/client.crt" \
        --key "$SSL/client.key" \
        --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d "$data" \
        "https://${CAMERA_ADDR}:${PORT}/services/CameraControlService/${endpoint}" 2>&1)

    if $VERBOSE; then
        echo -e "${CYAN}Response:${NC}"
        echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
        echo ""
    fi

    echo "$response"
}

# Check camera connectivity
check_connection() {
    echo -n "Connecting to Pixel 9 Pro ($CAMERA_ADDR)... "

    if timeout 3 bash -c "echo >/dev/tcp/${CAMERA_ADDR}/${PORT}" 2>/dev/null; then
        echo -e "${GREEN}OK${NC}"
        return 0
    else
        echo -e "${RED}FAILED${NC}"
        echo ""
        echo "Make sure:"
        echo "  1. Kanaha app is running on the phone"
        echo "  2. Camera preview is active"
        echo "  3. Phone is on the same network"
        exit 1
    fi
}

# Command: Get status
cmd_status() {
    local response
    response=$(api_call "getStatus" '{"action":"getStatus"}')

    if echo "$response" | grep -q '"success": true'; then
        local device_name battery storage state is_recording resolution
        device_name=$(echo "$response" | grep -o '"device_name": *"[^"]*"' | head -1 | cut -d'"' -f4)
        battery=$(echo "$response" | grep -o '"battery_level": *[0-9]*' | head -1 | grep -o '[0-9]*')
        storage=$(echo "$response" | grep -o '"storage_available_mb": *[0-9]*' | head -1 | grep -o '[0-9]*')
        state=$(echo "$response" | grep -o '"state": *"[^"]*"' | head -1 | cut -d'"' -f4)
        is_recording=$(echo "$response" | grep -o '"is_recording": *[a-z]*' | head -1 | grep -o 'true\|false')
        resolution=$(echo "$response" | grep -o '"resolution": *"[^"]*"' | head -1 | cut -d'"' -f4)

        echo ""
        echo -e "${CYAN}Pixel 9 Pro Status${NC}"
        echo "  Device:     $device_name"
        echo "  State:      $state"
        echo "  Recording:  $is_recording"
        echo "  Resolution: $resolution"
        echo "  Battery:    ${battery}%"
        echo "  Storage:    ${storage}MB available"
        echo ""
    else
        echo -e "${RED}Failed to get status${NC}"
        echo "$response"
        return 1
    fi
}

# Command: Start recording
cmd_record() {
    local clip="${CLIP_NAME:-pixel9_$(date +%Y%m%d_%H%M%S)}"

    echo -n "Starting recording (clip: $clip)... "

    local response
    response=$(api_call "startRecording" "{\"action\":\"startRecording\",\"clip_name\":\"$clip\"}")

    if echo "$response" | grep -q '"success": true'; then
        echo -e "${GREEN}OK${NC}"
        echo ""
        echo "Recording started. Use '$0 stop' to stop recording."
    else
        echo -e "${RED}FAILED${NC}"
        echo "$response"
        return 1
    fi
}

# Command: Stop recording
cmd_stop() {
    echo -n "Stopping recording... "

    local response
    response=$(api_call "stopRecording" '{"action":"stopRecording"}')

    if echo "$response" | grep -q '"success": true'; then
        echo -e "${GREEN}OK${NC}"
        local filename
        filename=$(echo "$response" | grep -o '"filename": *"[^"]*"' | cut -d'"' -f4)
        if [ -n "$filename" ]; then
            echo ""
            echo "Saved: $filename"
        fi
    else
        echo -e "${RED}FAILED${NC}"
        echo "$response"
        return 1
    fi
}

# Command: List files
cmd_list() {
    local response
    response=$(api_call "listFiles" '{"action":"listFiles"}')

    if echo "$response" | grep -q '"success": true'; then
        local file_count total_size
        file_count=$(echo "$response" | grep -o '"file_count": *[0-9]*' | grep -o '[0-9]*' | head -1)
        total_size=$(echo "$response" | grep -o '"total_size": *[0-9]*' | grep -o '[0-9]*' | head -1)
        total_size_mb=$((total_size / 1024 / 1024))

        echo ""
        echo -e "${CYAN}Video Files on Pixel 9 Pro${NC}"
        echo "  Total: $file_count files (${total_size_mb}MB)"
        echo ""

        # List files
        echo "$response" | grep -o '"filename": *"[^"]*"' | while read -r line; do
            filename=$(echo "$line" | cut -d'"' -f4)
            echo "  - $filename"
        done
        echo ""
    else
        echo -e "${RED}Failed to list files${NC}"
        echo "$response"
        return 1
    fi
}

# Command: Transfer files
cmd_transfer() {
    local dest="/tmp/kanaha/pixel9pro_$(date +%Y%m%d_%H%M%S)"

    echo "Transferring files to: $dest"
    echo -n "Initiating SFTP transfer... "

    local response
    # Use 20 minute (1200s) timeout for SFTP transfer (4K videos can be several GB)
    response=$(api_call "sftpTransfer" "{\"action\":\"sftpTransfer\",\"storage_server_id\":\"control\",\"video_filename\":\"*.mp4\",\"destination_folder\":\"$dest\"}" 1200)

    if echo "$response" | grep -q '"success": true'; then
        echo -e "${GREEN}OK${NC}"
        echo ""
        echo "Transfer initiated. Check $dest for files."
    else
        echo -e "${YELLOW}Transfer may not be configured${NC}"
        echo "$response"
        return 1
    fi
}

# Command: Full workflow
cmd_workflow() {
    local clip="${CLIP_NAME:-pixel9_$(date +%Y%m%d_%H%M%S)}"

    echo ""
    echo -e "${CYAN}=============================================="
    echo "  Pixel 9 Pro Recording Workflow"
    echo "==============================================${NC}"
    echo ""
    echo "  Clip name: $clip"
    echo "  Duration:  ${DURATION}s"
    echo ""

    # Step 1: Get initial status
    echo -e "${BLUE}Step 1: Checking camera status...${NC}"
    cmd_status

    # Capture timestamp for OpenCamera filename pattern (VID_YYYYMMDD_HHMM*.mp4)
    # OpenCamera ignores our clip name and uses its own naming convention
    local vid_pattern="VID_$(date +%Y%m%d_%H%M)*.mp4"

    # Step 2: Start recording
    echo -e "${BLUE}Step 2: Starting recording...${NC}"
    CLIP_NAME="$clip" cmd_record

    # Step 3: Wait
    echo ""
    echo -e "${BLUE}Step 3: Recording for ${DURATION} seconds...${NC}"
    for ((i=DURATION; i>0; i--)); do
        echo -ne "\r  Time remaining: ${i}s   "
        sleep 1
    done
    echo -e "\r  Time remaining: 0s - ${GREEN}Done${NC}   "

    # Step 4: Stop recording
    echo ""
    echo -e "${BLUE}Step 4: Stopping recording...${NC}"
    cmd_stop

    # Step 5: Wait for file finalization
    echo ""
    echo "Waiting for file to finalize..."
    sleep 2

    # Step 6: List files
    echo ""
    echo -e "${BLUE}Step 5: Listing recorded files...${NC}"
    cmd_list

    # Step 7: Transfer files to control host
    echo -e "${BLUE}Step 6: Transferring files to control host...${NC}"
    local dest="/tmp"

    # Note: SFTP over WiFi typically achieves ~2-3 MB/s due to SSH overhead
    # 20-minute timeout allows ~2.4-3.6 GB max transfer
    echo "  Destination: $dest"
    echo "  Note: WiFi SFTP speed ~2-3 MB/s (max ~3 GB in 20 min timeout)"
    echo -n "  Transferring ${vid_pattern}... "

    local transfer_start=$(date +%s)
    local transfer_response
    # Use 20 minute (1200s) timeout for SFTP transfer (4K videos can be several GB)
    transfer_response=$(api_call "sftpTransfer" "{\"action\":\"sftpTransfer\",\"storage_server_id\":\"control\",\"video_filename\":\"${vid_pattern}\",\"destination_folder\":\"$dest\"}" 1200)
    local transfer_end=$(date +%s)
    local transfer_duration=$((transfer_end - transfer_start))

    if echo "$transfer_response" | grep -q '"success": true'; then
        echo -e "${GREEN}OK${NC} (${transfer_duration}s)"
        echo "  Files transferred to $dest on control host"

        # Step 7: Delete transferred files from camera
        echo ""
        echo -e "${BLUE}Step 7: Cleaning up transferred files...${NC}"
        echo -n "  Deleting ${vid_pattern} from camera... "

        local delete_response
        delete_response=$(api_call "deleteFiles" "{\"action\":\"deleteFiles\",\"pattern\":\"${vid_pattern}\"}")

        if echo "$delete_response" | grep -q '"success": true'; then
            local deleted_count
            deleted_count=$(echo "$delete_response" | grep -o '"files_deleted": *[0-9]*' | head -1 | grep -o '[0-9]*')
            echo -e "${GREEN}OK${NC} ($deleted_count files deleted)"
        else
            echo -e "${YELLOW}Could not delete${NC}"
            echo "  Response: ${delete_response:0:100}..."
        fi
    else
        echo -e "${YELLOW}Transfer failed${NC}"
        echo "  Response: ${transfer_response:0:100}..."
        echo ""
        echo -e "  ${YELLOW}Tip: For large files (>3GB), use USB transfer:${NC}"
        echo "    adb pull /storage/emulated/0/DCIM/OpenCamera/${vid_pattern} /tmp/"
    fi

    # Step 8: Final status
    echo ""
    echo -e "${BLUE}Step 8: Final status...${NC}"
    cmd_status

    echo -e "${CYAN}=============================================="
    echo "  Workflow Complete"
    echo "==============================================${NC}"
    echo ""
    echo "Video files transferred to: $dest"
    echo ""
}

# Main
echo ""
CAMERA_ADDR=$(get_address)
check_connection

case $COMMAND in
    status)
        cmd_status
        ;;
    record)
        cmd_record
        ;;
    stop)
        cmd_stop
        ;;
    list)
        cmd_list
        ;;
    transfer)
        cmd_transfer
        ;;
    workflow)
        cmd_workflow
        ;;
esac
