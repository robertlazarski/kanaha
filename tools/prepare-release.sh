#!/bin/bash
#
# Prepare APK for GitHub release
# Usage: ./tools/prepare-release.sh v1.0.0
#

set -e

VERSION="${1:-v1.0.0}"
APK_SOURCE="kanaha-camera-app/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_DIR="release"
APK_NAME="kanaha-${VERSION}-arm64.apk"

echo "=== Preparing Kanaha $VERSION Release ==="
echo ""

# Check APK exists
if [ ! -f "$APK_SOURCE" ]; then
    echo "ERROR: APK not found at $APK_SOURCE"
    echo "Run: cd kanaha-camera-app && ./gradlew assembleDebug"
    exit 1
fi

# Create release directory
mkdir -p "$RELEASE_DIR"

# Copy and rename APK
cp "$APK_SOURCE" "$RELEASE_DIR/$APK_NAME"
echo "Copied APK to: $RELEASE_DIR/$APK_NAME"

# Generate SHA256 checksum
cd "$RELEASE_DIR"
sha256sum "$APK_NAME" > "${APK_NAME}.sha256"
echo "Generated checksum: ${APK_NAME}.sha256"

# Show results
echo ""
echo "=== Release Files ==="
ls -lh "$APK_NAME"*
echo ""
echo "=== SHA256 Checksum ==="
cat "${APK_NAME}.sha256"
echo ""
echo "=== Upload Instructions ==="
echo "1. Go to GitHub repo → Releases → Create new release"
echo "2. Tag: $VERSION"
echo "3. Title: Kanaha $VERSION"
echo "4. Upload these files:"
echo "   - $RELEASE_DIR/$APK_NAME"
echo "   - $RELEASE_DIR/${APK_NAME}.sha256"
echo "5. Copy release notes from RELEASE_NOTES_${VERSION}.md"
echo ""
