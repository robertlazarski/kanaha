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
SSL=$HOME/repos/kanaha/kanaha-camera-app/app/src/main/assets/ssl
APK=$HOME/repos/kanaha/kanaha-camera-app/app/build/outputs/apk/debug/app-debug.apk

PIXEL_IP="192.168.8.168"
MOTO_IP="192.168.8.126"
PIXEL_ADB="${PIXEL_IP}:5555"
MOTO_ADB="${MOTO_IP}:5555"

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
    response=$(curl -sk --http2 --max-time 10 \
        --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d '{"action":"getStatus"}' \
        "https://$ip:8443/services/CameraControlService/getStatus" 2>&1)

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
    response=$(curl -sk --http2 --max-time 10 \
        --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d '{"action":"listFiles"}' \
        "https://$ip:8443/services/CameraControlService/listFiles" 2>&1)

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
    response=$(curl -sk --http2 --max-time 10 \
        --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d '{"action":"deleteFiles","pattern":"../../../etc/passwd"}' \
        "https://$ip:8443/services/CameraControlService/deleteFiles" 2>&1)

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
    response=$(curl -sk --http2 --max-time 10 \
        --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d '{"action":"deleteFiles","pattern":"test; rm -rf /"}' \
        "https://$ip:8443/services/CameraControlService/deleteFiles" 2>&1)

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
    response=$(curl -sk --http2 --max-time 10 \
        --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d '{"action":"deleteFiles","pattern":"<script>alert(1)</script>"}' \
        "https://$ip:8443/services/CameraControlService/deleteFiles" 2>&1)

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
    response=$(curl -sk --http2 --max-time 10 \
        --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d "{\"action\":\"deleteFiles\",\"pattern\":\"$long_pattern\"}" \
        "https://$ip:8443/services/CameraControlService/deleteFiles" 2>&1)

    if echo "$response" | grep -qi "too long\|invalid\|rejected\|error"; then
        echo -e "${GREEN}PASS${NC}: Oversized input correctly rejected"
    else
        echo -e "${YELLOW}WARN${NC}: Oversized input handling unclear"
        echo "  Response: ${response:0:100}..."
    fi

    # Test 7: User-Agent Filtering (should block scanner tools)
    echo ""
    echo "--- Test 7: User-Agent Filtering ---"
    response=$(curl -sk --http2 --max-time 10 \
        --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -H "User-Agent: Nikto/2.1.6" \
        -d '{"action":"getStatus"}' \
        "https://$ip:8443/services/CameraControlService/getStatus" 2>&1)

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
    response=$(curl -sk --http2 --max-time 10 -w "%{http_code}" -o /dev/null \
        --cert "$SSL/client.crt" --key "$SSL/client.key" --cacert "$SSL/ca.crt" \
        -H "Content-Type: application/json" \
        -d "$large_payload" \
        "https://$ip:8443/services/CameraControlService/getStatus" 2>&1)

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
    echo -n "Connecting to Pixel 9 Pro ($PIXEL_IP)... "
    if timeout 5 $ADB connect "$PIXEL_ADB" 2>&1 | grep -q "connected"; then
        echo -e "${GREEN}Connected${NC}"
        PIXEL_CONNECTED=true
    else
        echo -e "${YELLOW}Not connected (will try direct HTTPS)${NC}"
    fi
fi

if $TEST_MOTO; then
    echo -n "Connecting to Moto X4 ($MOTO_IP)... "
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

    if [ ! -f "$APK" ]; then
        echo -e "${RED}ERROR: APK not found at $APK${NC}"
        echo "Run: ./gradlew assembleDebug"
        exit 1
    fi

    if $PIXEL_CONNECTED; then
        echo -n "Installing on Pixel 9 Pro... "
        if $ADB -s "$PIXEL_ADB" install -r "$APK" 2>&1 | grep -q "Success"; then
            echo -e "${GREEN}OK${NC}"
        else
            echo -e "${RED}FAILED${NC}"
        fi
    fi

    if $MOTO_CONNECTED; then
        echo -n "Installing on Moto X4... "
        if $ADB -s "$MOTO_ADB" install -r "$APK" 2>&1 | grep -q "Success"; then
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
        test_camera "Pixel 9 Pro" "$PIXEL_IP" "$PIXEL_ADB"
    else
        test_camera "Pixel 9 Pro" "$PIXEL_IP" ""
    fi
fi

if $TEST_MOTO; then
    if $MOTO_CONNECTED; then
        test_camera "Moto X4" "$MOTO_IP" "$MOTO_ADB"
    else
        test_camera "Moto X4" "$MOTO_IP" ""
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
echo "  curl -sk --http2 --cert \"\$SSL/client.crt\" --key \"\$SSL/client.key\" --cacert \"\$SSL/ca.crt\" \\"
echo "    -H \"Content-Type: application/json\" -d '{\"action\":\"startRecording\"}' \\"
echo "    https://$PIXEL_IP:8443/services/CameraControlService/startRecording"
echo ""
