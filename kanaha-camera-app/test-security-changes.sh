#!/bin/bash
#
# Kanaha Security Changes Test Script
# Tests all security features after APK deployment
#
# Prerequisites:
#   - Phones powered on and connected to WiFi (192.168.8.x network)
#   - ADB over WiFi previously enabled on phones
#
# Usage:
#   ./test-security-changes.sh [--install] [--pixel-only] [--moto-only]
#

set -e

# Configuration
ADB=$HOME/Android/Sdk/platform-tools/adb
SSL="${KANAHA_CERT_DIR:-$HOME/kanaha-ca}"   # operator.crt/.key + kanaha-ca.crt, or client.crt/.key + ca.crt
APK_DIR=$HOME/repos/kanaha/kanaha-camera-app/app/build/outputs/apk
PIXEL_APK=$APK_DIR/nano/debug/app-nano-debug.apk   # Pixel: nano flavor
MOTO_APK=$APK_DIR/foss/debug/app-foss-debug.apk    # Moto: foss flavor

PIXEL_IP="192.168.8.159"
MOTO_IP="192.168.8.174"
PIXEL_ADB="${PIXEL_IP}:5555"
MOTO_ADB="${MOTO_IP}:5555"

# Client certificate and CA, under either set of names, as in
# tools/kanaha-discover.sh; and the name the phones' certificates carry.
if [[ -f "$SSL/operator.crt" ]]; then
    CERT="$SSL/operator.crt"; KEY="$SSL/operator.key"; CA="$SSL/kanaha-ca.crt"
else
    CERT="$SSL/client.crt"; KEY="$SSL/client.key"; CA="$SSL/ca.crt"
fi
CAMERA_TLS_NAME="${KANAHA_CAMERA_TLS_NAME:-camera.local}"

# camera_curl ADDR PATH [curl args...]: HTTPS to a camera on 8443 with its
# certificate verified, never -k. By address first; if the certificate names
# the phone by DNS name instead (curl exit 51/60), again with --resolve to that
# name, as tools/kanaha-discover.sh does. Prints the last attempt's output.
camera_curl() {
    local addr=$1 path=$2 out rc=0
    shift 2
    local args=(-s --http2 --cert "$CERT" --key "$KEY" --cacert "$CA" "$@")
    out=$(curl "${args[@]}" "https://${addr}:8443${path}") || rc=$?
    if [[ $rc -eq 51 || $rc -eq 60 ]]; then
        rc=0
        out=$(curl "${args[@]}" --resolve "${CAMERA_TLS_NAME}:8443:${addr}" \
            "https://${CAMERA_TLS_NAME}:8443${path}") || rc=$?
    fi
    printf '%s\n' "$out"
    return $rc
}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Parse arguments
INSTALL_APK=false
TEST_PIXEL=true
TEST_MOTO=true

for arg in "$@"; do
    case $arg in
        --install)
            INSTALL_APK=true
            ;;
        --pixel-only)
            TEST_MOTO=false
            ;;
        --moto-only)
            TEST_PIXEL=false
            ;;
    esac
done

echo "=============================================="
echo "  Kanaha Security Changes Test Script"
echo "=============================================="
echo ""

# Function to test a single camera
test_camera() {
    local name=$1
    local ip=$2
    local adb_target=$3

    echo ""
    echo "=============================================="
    echo "  Testing: $name ($ip)"
    echo "=============================================="

    # Test HTTPS connectivity
    echo -n "Testing HTTPS connectivity... "
    if timeout 3 bash -c "echo >/dev/tcp/$ip/8443" 2>/dev/null; then
        echo -e "${GREEN}OK${NC}"
    else
        echo -e "${RED}FAILED - Camera not reachable on port 8443${NC}"
        echo "  Make sure the Kanaha app is running on the device"
        return 1
    fi

    # Test 1: getStatus
    echo ""
    echo "--- Test 1: getStatus ---"
    response=$(camera_curl "$ip" "/services/CameraControlService/getStatus" --max-time 10 \
        -H "Content-Type: application/json" \
        -d '{"action":"getStatus"}' 2>&1)

    if echo "$response" | grep -q '"success"'; then
        echo -e "${GREEN}PASS${NC}: getStatus returned success"
        echo "  Response: $response"
    else
        echo -e "${RED}FAIL${NC}: getStatus failed"
        echo "  Response: $response"
    fi

    # Test 2: listFiles
    echo ""
    echo "--- Test 2: listFiles ---"
    response=$(camera_curl "$ip" "/services/CameraControlService/listFiles" --max-time 10 \
        -H "Content-Type: application/json" \
        -d '{"action":"listFiles"}' 2>&1)

    if echo "$response" | grep -q '"success"'; then
        echo -e "${GREEN}PASS${NC}: listFiles returned success"
        file_count=$(echo "$response" | grep -o '"filename"' | wc -l)
        echo "  Found $file_count video file(s)"
    else
        echo -e "${YELLOW}WARN${NC}: listFiles response: $response"
    fi

    # Test 3: Security - Path Traversal Rejection
    echo ""
    echo "--- Test 3: Security - Path Traversal Rejection ---"
    response=$(camera_curl "$ip" "/services/CameraControlService/deleteFiles" --max-time 10 \
        -H "Content-Type: application/json" \
        -d '{"action":"deleteFiles","pattern":"../../../etc/passwd"}' 2>&1)

    if echo "$response" | grep -qi "traversal\|invalid\|rejected\|error"; then
        echo -e "${GREEN}PASS${NC}: Path traversal correctly rejected"
        echo "  Response: $response"
    else
        echo -e "${RED}FAIL${NC}: Path traversal may not be blocked!"
        echo "  Response: $response"
    fi

    # Test 4: Security - Invalid Characters Rejection
    echo ""
    echo "--- Test 4: Security - Invalid Characters Rejection ---"
    response=$(camera_curl "$ip" "/services/CameraControlService/deleteFiles" --max-time 10 \
        -H "Content-Type: application/json" \
        -d '{"action":"deleteFiles","pattern":"test; rm -rf /"}' 2>&1)

    if echo "$response" | grep -qi "invalid\|rejected\|error"; then
        echo -e "${GREEN}PASS${NC}: Shell injection pattern rejected"
        echo "  Response: $response"
    else
        echo -e "${RED}FAIL${NC}: Shell injection pattern may not be blocked!"
        echo "  Response: $response"
    fi

    # Test 5: Security - Script Injection Rejection
    echo ""
    echo "--- Test 5: Security - Script Injection Rejection ---"
    response=$(camera_curl "$ip" "/services/CameraControlService/deleteFiles" --max-time 10 \
        -H "Content-Type: application/json" \
        -d '{"action":"deleteFiles","pattern":"<script>alert(1)</script>"}' 2>&1)

    if echo "$response" | grep -qi "invalid\|rejected\|error\|injection"; then
        echo -e "${GREEN}PASS${NC}: Script injection correctly rejected"
    else
        echo -e "${RED}FAIL${NC}: Script injection may not be blocked!"
        echo "  Response: $response"
    fi

    # Test 6: Security - Oversized Input Rejection
    echo ""
    echo "--- Test 6: Security - Oversized Input Rejection ---"
    long_pattern=$(python3 -c "print('A' * 300)")
    response=$(camera_curl "$ip" "/services/CameraControlService/deleteFiles" --max-time 10 \
        -H "Content-Type: application/json" \
        -d "{\"action\":\"deleteFiles\",\"pattern\":\"$long_pattern\"}" 2>&1)

    if echo "$response" | grep -qi "too long\|invalid\|rejected\|error"; then
        echo -e "${GREEN}PASS${NC}: Oversized input correctly rejected"
    else
        echo -e "${YELLOW}WARN${NC}: Oversized input handling unclear"
        echo "  Response: ${response:0:100}..."
    fi

    # Test 7: User-Agent Filtering (should block scanner tools)
    echo ""
    echo "--- Test 7: User-Agent Filtering ---"
    response=$(camera_curl "$ip" "/services/CameraControlService/getStatus" --max-time 10 \
        -H "Content-Type: application/json" \
        -H "User-Agent: Nikto/2.1.6" \
        -d '{"action":"getStatus"}' 2>&1)

    if echo "$response" | grep -q "403\|Forbidden\|blocked"; then
        echo -e "${GREEN}PASS${NC}: Scanner user-agent correctly blocked"
    elif echo "$response" | grep -q '"success"'; then
        echo -e "${YELLOW}WARN${NC}: Scanner user-agent was NOT blocked (may be OK if UA filtering disabled)"
    else
        echo -e "${YELLOW}INFO${NC}: Response: $response"
    fi

    # Test 8: Check Audit Log
    echo ""
    echo "--- Test 8: Audit Log Check ---"
    if [ -n "$adb_target" ]; then
        echo "Pulling audit log via ADB..."
        audit_log=$($ADB -s "$adb_target" shell "run-as org.kanaha.camera cat files/apache/logs/audit.log 2>/dev/null | tail -5" 2>/dev/null || echo "")
        if [ -n "$audit_log" ]; then
            echo -e "${GREEN}PASS${NC}: Audit log exists and contains entries"
            echo "  Last entries:"
            echo "$audit_log" | sed 's/^/    /'
        else
            echo -e "${YELLOW}WARN${NC}: Could not read audit log (ADB issue or log empty)"
        fi
    else
        echo -e "${YELLOW}SKIP${NC}: ADB not connected, cannot check audit log"
    fi

    # Test 9: Rate Limiting Headers
    echo ""
    echo "--- Test 9: Rate Limiting (LimitRequestBody) ---"
    # Create a payload larger than 64KB
    large_payload=$(python3 -c "import json; print(json.dumps({'action':'getStatus','data':'A'*70000}))")
    response=$(camera_curl "$ip" "/services/CameraControlService/getStatus" --max-time 10 -w "%{http_code}" -o /dev/null \
        -H "Content-Type: application/json" \
        -d "$large_payload" 2>&1)

    if [ "$response" = "413" ]; then
        echo -e "${GREEN}PASS${NC}: Large request correctly rejected (413)"
    elif [ "$response" = "200" ]; then
        echo -e "${YELLOW}WARN${NC}: Large request was accepted (LimitRequestBody may not be active)"
    else
        echo -e "${YELLOW}INFO${NC}: HTTP status: $response"
    fi

    echo ""
    echo "--- $name Tests Complete ---"
}

# Connect to devices
echo "Step 1: Connecting to devices via ADB..."
echo ""

PIXEL_CONNECTED=false
MOTO_CONNECTED=false

if $TEST_PIXEL; then
    echo -n "Connecting to Pixel 10 Pro XL ($PIXEL_IP)... "
    if timeout 5 $ADB connect "$PIXEL_ADB" 2>&1 | grep -q "connected"; then
        echo -e "${GREEN}Connected${NC}"
        PIXEL_CONNECTED=true
    else
        echo -e "${YELLOW}Not connected (will try direct HTTPS)${NC}"
    fi
fi

if $TEST_MOTO; then
    echo -n "Connecting to Moto G Play 2024 ($MOTO_IP)... "
    if timeout 5 $ADB connect "$MOTO_ADB" 2>&1 | grep -q "connected"; then
        echo -e "${GREEN}Connected${NC}"
        MOTO_CONNECTED=true
    else
        echo -e "${YELLOW}Not connected (will try direct HTTPS)${NC}"
    fi
fi

# Install APK if requested
if $INSTALL_APK; then
    echo ""
    echo "Step 2: Installing APK..."

    for apk in "$PIXEL_APK" "$MOTO_APK"; do
        if [ ! -f "$apk" ]; then
            echo -e "${RED}ERROR: APK not found at $apk${NC}"
            echo "Run: ./gradlew assembleNanoDebug assembleFossDebug"
            exit 1
        fi
    done

    if $PIXEL_CONNECTED; then
        echo -n "Installing on Pixel 10 Pro XL... "
        if $ADB -s "$PIXEL_ADB" install -r "$PIXEL_APK" 2>&1 | grep -q "Success"; then
            echo -e "${GREEN}OK${NC}"
        else
            echo -e "${RED}FAILED${NC}"
        fi
    fi

    if $MOTO_CONNECTED; then
        echo -n "Installing on Moto G Play 2024... "
        if $ADB -s "$MOTO_ADB" install -r "$MOTO_APK" 2>&1 | grep -q "Success"; then
            echo -e "${GREEN}OK${NC}"
        else
            echo -e "${RED}FAILED${NC}"
        fi
    fi

    # Launch apps
    echo ""
    echo "Launching Kanaha app on devices..."

    if $PIXEL_CONNECTED; then
        $ADB -s "$PIXEL_ADB" shell am start -n org.kanaha.camera/net.sourceforge.opencamera.MainActivity 2>/dev/null &
    fi
    if $MOTO_CONNECTED; then
        $ADB -s "$MOTO_ADB" shell am start -n org.kanaha.camera/net.sourceforge.opencamera.MainActivity 2>/dev/null &
    fi

    echo "Waiting 10 seconds for Apache to start..."
    sleep 10
fi

# Run tests
echo ""
echo "Step 3: Running Security Tests..."

if $TEST_PIXEL; then
    if $PIXEL_CONNECTED; then
        test_camera "Pixel 10 Pro XL" "$PIXEL_IP" "$PIXEL_ADB"
    else
        test_camera "Pixel 10 Pro XL" "$PIXEL_IP" ""
    fi
fi

if $TEST_MOTO; then
    if $MOTO_CONNECTED; then
        test_camera "Moto G Play 2024" "$MOTO_IP" "$MOTO_ADB"
    else
        test_camera "Moto G Play 2024" "$MOTO_IP" ""
    fi
fi

# Summary
echo ""
echo "=============================================="
echo "  Test Summary"
echo "=============================================="
echo ""
echo "Security features tested:"
echo "  1. Basic API functionality (getStatus, listFiles)"
echo "  2. Path traversal rejection"
echo "  3. Shell injection rejection"
echo "  4. Script injection rejection"
echo "  5. Input length limits"
echo "  6. User-Agent filtering"
echo "  7. Audit logging"
echo "  8. Request size limits"
echo ""
echo "For manual recording test, run:"
echo "  SSL=$SSL"
echo "  curl -s --http2 --cert \"$CERT\" --key \"$KEY\" --cacert \"$CA\" \\"
echo "    --resolve $CAMERA_TLS_NAME:8443:$PIXEL_IP -H \"Content-Type: application/json\" \\"
echo "    -d '{\"action\":\"startRecording\"}' https://$CAMERA_TLS_NAME:8443/services/CameraControlService/startRecording"
echo ""
