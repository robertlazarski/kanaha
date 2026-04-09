#!/bin/bash
#
# Kanaha Camera Discovery - mDNS + network scanner for Kanaha cameras
# Usage: kanaha-discover.sh [--json] [--ip IP] [--scan]
#
# Discovery methods (in order of preference):
#   1. avahi-browse (mDNS) - instant, requires avahi-utils
#   2. Port scanning - scans local subnet, slower but always works
#

SSL_DIR="${KANAHA_SSL_DIR:-$HOME/repos/kanaha/kanaha-camera-app/app/src/main/assets/ssl}"
PORT=8443

# Parse args
JSON=false
SINGLE_IP=""
FORCE_SCAN=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --json) JSON=true; shift ;;
        --ip) SINGLE_IP="$2"; shift 2 ;;
        --scan) FORCE_SCAN=true; shift ;;
        -h|--help)
            echo "Usage: $0 [--json] [--ip IP] [--scan]"
            echo "  --json    Output JSON"
            echo "  --ip IP   Check single IP only"
            echo "  --scan    Force port scanning (skip mDNS)"
            exit 0 ;;
        *) shift ;;
    esac
done

# Check single camera via API
check_camera() {
    local ip="$1"
    curl -sk --connect-timeout 2 --max-time 3 \
        --cert "$SSL_DIR/client.crt" \
        --key "$SSL_DIR/client.key" \
        --cacert "$SSL_DIR/ca.crt" \
        -H "Content-Type: application/json" \
        -d '{"action":"get_status"}' \
        "https://$ip:$PORT/services/CameraControlService/getStatus" 2>/dev/null
}

# Discover cameras via avahi-browse (mDNS)
discover_via_avahi() {
    local tmpfile="$1"

    # Use avahi-browse with resolve to get full service info
    # Format: =;iface;proto;name;type;domain;hostname;addr;port;txt
    # Note: -rp (no -k) gives unescaped TXT records for easier parsing
    # Note: no -t flag - let it run for a few seconds to collect responses
    timeout 3 avahi-browse -rp _https._tcp 2>/dev/null | while IFS=';' read -r status iface proto name type domain hostname addr port txt rest; do
        [[ "$status" != "=" ]] && continue
        [[ -z "$addr" ]] && continue

        # Check if this is a Kanaha camera (api=kanaha-camera-control in TXT)
        [[ "$txt" != *"api=kanaha-camera-control"* ]] && continue

        # Extract TXT fields from avahi-browse -rp output.
        # Format: "key1=value1" "key2=value with spaces" "key3=value3"
        # Strip outer quotes from each pair, then extract by key name.
        txt_clean=$(echo "$txt" | sed 's/" "/\n/g; s/^"//; s/"$//')

        dev_name=$(echo "$txt_clean" | sed -n 's/^name=//p')
        [[ -z "$dev_name" ]] && dev_name="$name"

        model=$(echo "$txt_clean" | sed -n 's/^model=//p')
        [[ -z "$model" ]] && model="Unknown"

        manufacturer=$(echo "$txt_clean" | sed -n 's/^manufacturer=//p')
        [[ -z "$manufacturer" ]] && manufacturer="Unknown"

        # Get status via API for battery/storage
        result=$(check_camera "$addr")
        if echo "$result" | grep -q '"success".*true'; then
            state=$(echo "$result" | grep -oP '"state"\s*:\s*"\K[^"]+')
            battery=$(echo "$result" | grep -oP '"battery_level"\s*:\s*\K[0-9]+')
            storage=$(echo "$result" | grep -oP '"storage_available_mb"\s*:\s*\K[0-9]+')
            # Format: ip|hostname|name|model|manufacturer|state|battery|storage
            echo "$addr|$hostname|$dev_name|$model|$manufacturer|$state|$battery|$storage" >> "$tmpfile"
        fi
    done
}

# Single IP mode
if [[ -n "$SINGLE_IP" ]]; then
    result=$(check_camera "$SINGLE_IP")
    if echo "$result" | grep -q '"success".*true'; then
        if [[ "$JSON" = true ]]; then
            echo "[{\"ip\":\"$SINGLE_IP\",\"port\":$PORT,\"status\":$result}]"
        else
            echo "Found Kanaha camera at $SINGLE_IP:$PORT"
            echo "$result" | python3 -m json.tool 2>/dev/null || echo "$result"
        fi
        exit 0
    else
        [[ "$JSON" = true ]] && echo "[]" || echo "No camera at $SINGLE_IP"
        exit 1
    fi
fi

# Fast parallel scan using temp file
TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

# Port scanning function
scan_subnet() {
    local subnet="$1"
    [[ "$JSON" = false ]] && echo "Scanning $subnet.0/24..." >&2

    for i in $(seq 1 254); do
        (
            ip="$subnet.$i"
            # Quick port check
            timeout 0.5 bash -c "echo >/dev/tcp/$ip/$PORT" 2>/dev/null || exit 0
            # Verify it's Kanaha
            result=$(check_camera "$ip")
            if echo "$result" | grep -q '"success".*true'; then
                name=$(echo "$result" | grep -oP '"device_name"\s*:\s*"\K[^"]+')
                model=$(echo "$result" | grep -oP '"device_model"\s*:\s*"\K[^"]+')
                manufacturer=$(echo "$result" | grep -oP '"device_manufacturer"\s*:\s*"\K[^"]+')
                state=$(echo "$result" | grep -oP '"state"\s*:\s*"\K[^"]+')
                battery=$(echo "$result" | grep -oP '"battery_level"\s*:\s*\K[0-9]+')
                storage=$(echo "$result" | grep -oP '"storage_available_mb"\s*:\s*\K[0-9]+')
                # Format: ip|hostname|name|model|manufacturer|state|battery|storage
                # (hostname empty for port scan - mDNS not used)
                echo "$ip||$name|$model|$manufacturer|$state|$battery|$storage" >> "$TMPFILE"
            fi
        ) &
        # Limit to 50 parallel jobs
        (( i % 50 == 0 )) && wait
    done
    wait
}

# Try avahi-browse first (mDNS), then fall back to port scanning
USE_AVAHI=false
if [[ "$FORCE_SCAN" = false ]] && command -v avahi-browse &>/dev/null; then
    USE_AVAHI=true
    [[ "$JSON" = false ]] && echo "Discovering cameras via mDNS..." >&2
    discover_via_avahi "$TMPFILE"
fi

# Fall back to port scanning if avahi found nothing or isn't available
if [[ ! -s "$TMPFILE" ]]; then
    if [[ "$USE_AVAHI" = true && "$JSON" = false ]]; then
        echo "No cameras found via mDNS, falling back to port scan..." >&2
    fi

    SUBNET=$(ip route get 8.8.8.8 2>/dev/null | grep -oP 'src \K[0-9]+\.[0-9]+\.[0-9]+')
    if [[ -z "$SUBNET" ]]; then
        [[ "$JSON" = true ]] && echo "[]" || echo "Cannot determine subnet"
        exit 1
    fi
    scan_subnet "$SUBNET"
fi

# Deduplicate results (avahi may report same camera multiple times)
if [[ -s "$TMPFILE" ]]; then
    sort -t'|' -k1,1 -u "$TMPFILE" > "${TMPFILE}.dedup"
    mv "${TMPFILE}.dedup" "$TMPFILE"
fi

# Output results
if [[ ! -s "$TMPFILE" ]]; then
    if [[ "$JSON" = true ]]; then
        echo "[]"
    else
        echo "No Kanaha cameras found."
        echo ""
        echo "Troubleshooting:"
        echo "  - Make sure WiFi is turned ON on all camera phones"
        echo "    (phones on cellular-only have no local network address)"
        echo "  - Verify the phone and this computer are on the same WiFi network"
        echo "  - Open the Kanaha app and confirm the HTTP server is running"
        echo "  - Try: $(basename "$0") --ip <phone-ip>"
        echo "  - Fallback: adb forward tcp:18443 tcp:8443 (USB cable)"
    fi
    exit 1
fi

if [[ "$JSON" = true ]]; then
    echo "["
    first=true
    while IFS='|' read -r ip hostname name model manufacturer state battery storage; do
        [[ "$first" = true ]] || echo ","
        first=false
        # Use hostname for URL if available, otherwise IP
        url_host="${hostname:-$ip}"
        printf '  {"name":"%s","ip":"%s","hostname":"%s","port":%d,"model":"%s","manufacturer":"%s","state":"%s","battery":%s,"storage_mb":%s,"url":"https://%s:%d/services/CameraControlService"}' \
            "$name" "$ip" "$hostname" "$PORT" "$model" "$manufacturer" "$state" "$battery" "$storage" "$url_host" "$PORT"
    done < "$TMPFILE"
    echo -e "\n]"
else
    count=$(wc -l < "$TMPFILE")
    echo -e "\033[32mFound $count camera(s):\033[0m"
    echo ""
    while IFS='|' read -r ip hostname name model manufacturer state battery storage; do
        # Use hostname for URL if available, otherwise IP
        url_host="${hostname:-$ip}"
        echo "  $name ($manufacturer $model)"
        echo "    IP:       $ip"
        [[ -n "$hostname" ]] && echo "    Hostname: $hostname"
        echo "    State:    $state"
        echo "    Battery:  ${battery}%"
        echo "    Storage:  $storage MB"
        echo "    URL:      https://$url_host:$PORT/services/CameraControlService"
        echo ""
    done < "$TMPFILE"
fi
