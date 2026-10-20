# Android APK Building Guide

This document provides comprehensive step-by-step instructions for building the Kanaha Camera App APK from source and installing it on any ARM64 Android device running API 23+ (Android 6.0 Marshmallow or later; the `nano` flavour needs API 26). The same APK works across a wide range of devices—tested on hardware from a 2017 Moto X4 (since retired) to a 2025 Pixel 10 Pro XL with no device-specific modifications.

---

## Prerequisites

### Development Environment

The following instructions assume **Ubuntu 25.10** as the build environment.

### Step 1: Install Base Development Tools

```bash
# Update package lists
sudo apt update

# Install essential build tools
sudo apt install -y \
    build-essential \
    git \
    curl \
    wget \
    unzip \
    zip \
    cmake \
    ninja-build \
    pkg-config \
    autoconf \
    automake \
    libtool \
    m4

# Install Java JDK 17 (required for Android builds)
# Note: openjdk 21 will also work, use that if you already have it installed
sudo apt install -y openjdk-17-jdk openjdk-17-jre

# Verify Java installation
java -version
# Should show: openjdk version "17.0.x"

# Set JAVA_HOME environment variable
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

### Step 2: Install Android Studio

Option A - Using Snap (Recommended for Ubuntu 25.10):
```bash
# Install Android Studio via snap
sudo snap install android-studio --classic

# Launch Android Studio to complete first-run setup
android-studio
```

Option B - Manual Installation:
```bash
# Download Android Studio (replace with latest version)
cd ~/Downloads
curl -L -o android-studio.tar.gz dl.google.com

# Extract to /opt
sudo tar -xzf android-studio.tar.gz -C /opt

# Create desktop entry
cat > ~/.local/share/applications/android-studio.desktop <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=Android Studio
Icon=/opt/android-studio/bin/studio.png
Exec=/opt/android-studio/bin/studio.sh
Comment=Android Development IDE
Categories=Development;IDE;
Terminal=false
EOF

# Launch Android Studio
/opt/android-studio/bin/studio.sh
```

### Step 3: Install Android SDK, NDK, and Build Tools

Android Studio installs the SDK and command line tools during first-run setup. After completing the Android Studio wizard:

```bash
# Set environment variables (the SDK is already installed at ~/Android/Sdk)
echo 'export ANDROID_SDK_ROOT=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH' >> ~/.bashrc
echo 'export PATH=$ANDROID_SDK_ROOT/platform-tools:$PATH' >> ~/.bashrc
source ~/.bashrc

# Start a new terminal, then install additional SDK components
# The sdkmanager command is in the PATH set above
sdkmanager --install "platforms;android-35"
sdkmanager --install "build-tools;35.0.0"
sdkmanager --install "ndk;28.0.12916984"
sdkmanager --install "cmake;3.22.1"

# Verify installations
sdkmanager --list_installed
```

> **Note:** For headless/CI environments without Android Studio, download the command line tools manually from https://developer.android.com/studio#command-tools

### Step 4: Install the Native Build Tools

The native libraries are built from source by kanaha-android-deps (see Build Steps below). It needs only build tools on the host, no `-dev` packages and no cross-compiler package: the NDK supplies the compiler.

```bash
sudo apt install -y build-essential make cmake autoconf automake libtool perl curl git
```

`build-essential` provides the host gcc that httpd's build uses for one tool it runs during the build.

### Step 5: Configure Android NDK Environment

```bash
# Set NDK environment variables
echo 'export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984' >> ~/.bashrc
echo 'export NDK_ROOT=$ANDROID_NDK_HOME' >> ~/.bashrc
echo 'export PATH=$ANDROID_NDK_HOME:$PATH' >> ~/.bashrc
source ~/.bashrc

# Verify NDK installation
ls -la $ANDROID_NDK_HOME
# Should show NDK directory structure with toolchains/

# Verify NDK compiler
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang --version
```

### Step 6: Install ADB and USB Debugging Tools

```bash
# Install Android Debug Bridge (adb)
sudo apt install -y android-tools-adb android-tools-fastboot

# Configure USB permissions for Android devices
sudo tee /etc/udev/rules.d/51-android.rules > /dev/null <<EOF
# Google Pixel devices
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"

# Motorola devices
SUBSYSTEM=="usb", ATTR{idVendor}=="22b8", MODE="0666", GROUP="plugdev"

# Samsung devices
SUBSYSTEM=="usb", ATTR{idVendor}=="04e8", MODE="0666", GROUP="plugdev"

# OnePlus devices
SUBSYSTEM=="usb", ATTR{idVendor}=="2a70", MODE="0666", GROUP="plugdev"
EOF

# Reload udev rules
sudo udevadm control --reload-rules
sudo udevadm trigger

# Add user to plugdev group
sudo usermod -aG plugdev $USER

# Verify ADB installation
adb --version
# Should show: Android Debug Bridge version 1.0.41 or later
```

### Step 7: Verify Complete Environment

```bash
# Check all required tools are installed
echo "=== Development Environment Verification ==="

echo "Java Version:"
java -version 2>&1 | head -1
# Expected: openjdk version "17.x.x" or "21.x.x" or later

echo -e "\nAndroid SDK Location:"
echo "${ANDROID_SDK_ROOT:-NOT SET - run: export ANDROID_SDK_ROOT=\$HOME/Android/Sdk}"

echo -e "\nAndroid NDK Location:"
echo "${ANDROID_NDK_HOME:-NOT SET - see troubleshooting below}"

echo -e "\nCMake Version:"
cmake --version 2>/dev/null | head -1 || echo "NOT INSTALLED"

echo -e "\nGit Version:"
git --version

echo -e "\nADB Version:"
adb --version 2>/dev/null || $HOME/Android/Sdk/platform-tools/adb --version 2>/dev/null || echo "NOT INSTALLED - see troubleshooting"

echo -e "\nInstalled SDK Platforms:"
sdkmanager --list_installed 2>/dev/null | grep "platforms;" || echo "Run sdkmanager from Android Studio terminal"

echo -e "\nInstalled Build Tools:"
sdkmanager --list_installed 2>/dev/null | grep "build-tools;" || echo "Check Android Studio SDK Manager"

echo -e "\nInstalled NDK:"
sdkmanager --list_installed 2>/dev/null | grep "ndk;" || echo "NDK not installed - see troubleshooting"

echo -e "\nNDK Directory Check:"
ls -d $HOME/Android/Sdk/ndk/*/ 2>/dev/null || echo "No NDK found in ~/Android/Sdk/ndk/"
```

**Required Tool Versions Summary:**
- Ubuntu 25.10 (base OS)
- Java JDK 17 or later (JDK 21 also works)
- Android Studio (latest stable - Ladybug or later)
- Android SDK Platform 35 or 36
- Android NDK 28.0.12916984, the version kanaha-android-deps builds with
- Build Tools 35.0.0 or 36.1.0
- CMake 3.22.1 or later
- Git 2.40 or later
- ADB (Android Debug Bridge)

### Cross-compiled Dependencies

All dependencies must be cross-compiled for ARM64 with `-fPIC`. See [ANDROID_CROSS_COMPILATION.md](ANDROID_CROSS_COMPILATION.md) for complete instructions.

Verify libraries exist:
```bash
ls ~/android-cross-builds/deps/arm64-v8a/lib/*.a
```

Expected libraries:
- `libapr-1.a` - Apache Portable Runtime
- `libaprutil-1.a` - APR utilities
- `libssl.a`, `libcrypto.a` - OpenSSL TLS
- `libnghttp2.a` - HTTP/2 protocol
- `libjson-c.a` - JSON parsing
- `libexpat.a` - XML parsing
- `libaxis2_*.a` - Axis2/C web services (~20 libraries)
- `libaxutil.a`, `libguththila.a` - Axis2/C utilities

---

## Build Steps

The APK carries two native executables. `libhttpd.so`, Apache httpd with mod_axis2 and the camera service, is linked by `build-android.sh` at the repository root against libraries that kanaha-android-deps builds. Gradle builds only the MCP binary, `libkanaha_mcp.so`, and packages `libhttpd.so` from `jniLibs`.

### Step 1: Build the Native Dependencies

```bash
cd ~/repos
git clone https://github.com/robertlazarski/kanaha-android-deps.git
cd kanaha-android-deps
./build-all.sh
```

This builds OpenSSL, nghttp2, APR, APR-util, PCRE2, expat, json-c, Apache httpd and Axis2/C, at the versions pinned in its `versions.env`, into `~/android-cross-builds/deps/arm64-v8a/`. It records what it built in `BUILD-INFO` there, which the next step reads. The same build serves Kanaha Audio and Kanaha Calcs, so it only needs running again when its pins change.

### Step 2: Clone Kanaha and Link libhttpd.so

```bash
cd ~/repos
git clone https://github.com/robertlazarski/kanaha.git
cd kanaha
bash build-android.sh
```

`build-android.sh` compiles the camera service and the Axis2/C static service adapter, links them into httpd, strips the result and installs it as `kanaha-camera-app/app/src/main/jniLibs/arm64-v8a/libhttpd.so`. It stops if `BUILD-INFO` is missing.

### Step 3: Configure the Gradle Build

```bash
cd kanaha-camera-app
cat > local.properties <<EOF
sdk.dir=$HOME/Android/Sdk
ndk.version=28.0.12916984
EOF
```

### Step 4: Build the APK

```bash
# Both product flavours
./gradlew assembleDebug

# Or one flavour at a time
./gradlew assembleFossDebug     # distributable build, no proprietary client
./gradlew assembleNanoDebug     # adds the ML Kit description client, private use only

# Release APKs need a signing configuration
./gradlew assembleRelease

# Output, with the flavour in the path:
# Debug:   app/build/outputs/apk/foss/debug/app-foss-debug.apk
#          app/build/outputs/apk/nano/debug/app-nano-debug.apk
# Release: app/build/outputs/apk/foss/release/app-foss-release.apk
```

The `foss` flavour is the default (`isDefault = true`) and the one to publish.
The `nano` flavour links Google's proprietary ML Kit GenAI client for
`describeClip`; it is for private use only under GPL v3. See
[GOOGLE_NANO_INTEGRATION.md](GOOGLE_NANO_INTEGRATION.md).

### Step 5: Verify the APK

```bash
# About 15 MB
ls -lh app/build/outputs/apk/foss/debug/app-foss-debug.apk

# Exactly two native libraries: libhttpd.so (about 10 MB) and libkanaha_mcp.so (about 120 KB)
unzip -l app/build/outputs/apk/foss/debug/app-foss-debug.apk | grep "\.so$"
```

---

## Build Configuration

### app/build.gradle Key Settings

```gradle
android {
    namespace 'net.sourceforge.opencamera'  // Must match R class imports

    defaultConfig {
        minSdk 21
        targetSdk 34
        ndk {
            abiFilters 'arm64-v8a'  // Pixel 9 Pro target
        }
    }

    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            // CMake version NOT specified - uses system cmake
        }
    }
}
```

### CMakeLists.txt

Gradle's CMake build produces one binary, `libkanaha_mcp.so`, the MCP server. It compiles `kanaha_mcp_main.c`, `kanaha_mcp.c` and `camera_control_service.c`, and links json-c from `~/android-cross-builds/deps/arm64-v8a`. It stops with an error for any ABI but arm64-v8a. The HTTP/2 server is not built here; see below.

---

## Device Installation

### Step 1: Enable Developer Options on Android Device

```bash
# On the device:
1. Settings → About phone
2. Tap "Build number" 7 times rapidly
3. Enter PIN/password to confirm
4. "Developer options" now appears in Settings → System

# Enable USB debugging:
5. Settings → System → Developer options
6. Enable "USB debugging"
7. Enable "Install via USB" (for APK installation)
```

### Step 2: Connect Device and Install APK

```bash
# Connect Android device via USB cable
# Verify device connection
adb devices
# Should show: <serial>    device

# Install debug APK
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk

# Or install release APK
adb install -r app/build/outputs/apk/release/app-release.apk

# Verify installation
adb shell pm list packages | grep kanaha
# Should show: package:org.kanaha.camera
```

**Installation Options:**
- `-r` - Reinstall app, keeping data
- `-d` - Allow version code downgrade
- `-g` - Grant all runtime permissions

### Step 3: Launch and Verify

```bash
# Launch Kanaha app
adb shell am start -n org.kanaha.camera/.MainActivity

# Check Apache httpd service status
adb shell dumpsys activity services | grep ApacheService

# Monitor logs for startup issues
adb logcat -s KanahaCamera:V ApacheService:V
```

### Step 4: Configure Camera Settings

Once installed, configure Kanaha for multi-camera operation:

1. **Open Kanaha app** on the device
2. **Grant permissions**: Camera, Storage, Network
3. **Configure mTLS certificates**: Settings → Security → Import CA Certificate
4. **Set device IP**: Settings → Network → Set Static IP (e.g., 192.168.10.10)
5. **Start Apache service**: Settings → Services → Start HTTP/2 Server
6. **Verify connectivity**: Test from control station using `curl` with mTLS

---

## Native Library Architecture

### Communication Flow

```
   HTTPS Client (curl/control station)
       |
       v (mTLS on port 8443)
   [Apache httpd - libhttpd.so]
       |
       v
   [mod_axis2 / JSON-RPC Handler]
       |
       v
   [camera_control_service.c]
       |
       v (Intent broadcast via fork/execvp)
   [CameraControlReceiver.java]
       |
       v
   [OpenCamera MainActivity API]
       |
       v (response file)
   [camera_control_service.c → HTTP response]
```

### What Gets Packaged in the APK

Two native executables, both for arm64-v8a only:

```
libhttpd.so (~10 MB, from jniLibs, linked by build-android.sh)
    ├── Apache httpd core
    ├── mod_ssl (SSL/TLS support)
    ├── mod_http2 (HTTP/2 support)
    ├── mod_axis2 (Axis2/C web services)
    ├── the camera service
    └── All dependencies (OpenSSL, nghttp2, etc.)

libkanaha_mcp.so (~120 KB, built by Gradle's CMake)
    └── the MCP server and the camera service
```

**Runtime Behavior:**
- **No system Apache required**: Kanaha APK is self-contained
- **No dynamic loading**: All modules statically linked at build time
- **No LoadModule directives**: httpd binary has modules compiled in
- **Portable**: Works on any Android 8.0+ ARM64 device

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/jniLibs/arm64-v8a/libhttpd.so` | Prebuilt Apache httpd with Axis2/C (cross-compiled) |
| `app/src/main/cpp/axis2c/camera_control_service.c` | Camera control service (compiled into libhttpd.so) |
| `app/src/main/cpp/CMakeLists.txt` | Native build configuration |
| `app/src/main/java/org/kanaha/camera/CameraControlReceiver.java` | Intent receiver for camera operations |
| `app/src/main/java/org/kanaha/camera/ApacheService.java` | Service managing Apache httpd lifecycle |

**Note:** `libhttpd.so` is built separately, by `build-android.sh` at the repository root. Changes to `camera_control_service.c` need that script run as well as the Gradle build: Gradle rebuilds only the MCP binary.

### Verification

```bash
# List native libraries in APK
unzip -l app/build/outputs/apk/foss/debug/app-foss-debug.apk | grep "\.so$"

# Should show exactly two:
# lib/arm64-v8a/libhttpd.so        (~10MB) - Apache httpd with Axis2/C, started via ProcessBuilder
# lib/arm64-v8a/libkanaha_mcp.so   (~120KB) - the MCP server, copied to files/kanaha-camera-mcp
```

```bash
# Extract and check dependencies of the main httpd binary
cd /tmp
unzip ~/repos/kanaha/kanaha-camera-app/app/build/outputs/apk/foss/debug/app-foss-debug.apk lib/arm64-v8a/libhttpd.so
readelf -d lib/arm64-v8a/libhttpd.so | grep NEEDED
```

Expected output should show only Android system libraries (liblog.so, libandroid.so, libz.so, libm.so, libc.so, libdl.so). All other dependencies (OpenSSL, APR, Axis2/C, etc.) are statically linked.

---

## Rebuilding libhttpd.so

`camera_control_service.c` is compiled into both executables. After changing it, or any other code linked into `libhttpd.so`, run `build-android.sh` again before the Gradle build; Gradle on its own rebuilds only the MCP binary and would package the old server:

```bash
cd ~/repos/kanaha
bash build-android.sh
cd kanaha-camera-app
./gradlew assembleFossDebug
```

After a kanaha-android-deps update, run its `build-all.sh` first.

---

## Troubleshooting

### Build Failures

```bash
# Clear Gradle cache
./gradlew clean
rm -rf ~/.gradle/caches
./gradlew assembleDebug

# NDK version mismatch
# Verify NDK version matches project configuration in build.gradle
android {
    ndkVersion "28.0.12916984"
}
```

### Installation Failures

```bash
# Check available storage
adb shell df /data

# Uninstall existing version
adb uninstall org.kanaha.camera

# Reinstall fresh
adb install app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

### Runtime Issues

```bash
# Check Apache httpd startup
adb logcat -s ApacheService:V | grep "httpd started"

# Verify native library loading
adb logcat | grep "dlopen"

# Check certificate configuration
adb shell ls -la /data/data/org.kanaha.camera/files/certificates/
```

### Common Build Issues and Solutions

#### Issue 1: libncurses.so.5 not found (RenderScript)

**Error:**
```
llvm-rs-cc: error while loading shared libraries: libncurses.so.5
```

**Solution:** Create wrapper script or symlinks:
```bash
# Create symlink (if libncurses.so.6 exists)
sudo ln -s /usr/lib/x86_64-linux-gnu/libncurses.so.6 /usr/lib/x86_64-linux-gnu/libncurses.so.5
```

#### Issue 2: CMake version not found

**Error:**
```
CMake '3.18.1' was not found
```

**Solution:** Remove cmake version requirement from build.gradle:
```gradle
externalNativeBuild {
    cmake {
        path "src/main/cpp/CMakeLists.txt"
        // Remove: version "3.18.1"
    }
}
```

#### Issue 3: R class namespace mismatch

**Error:**
```
cannot find symbol import net.sourceforge.opencamera.R
```

**Solution:** Ensure namespace in build.gradle matches source imports:
```gradle
android {
    namespace 'net.sourceforge.opencamera'  // NOT 'org.kanaha.camera'
}
```

#### Issue 4: MainActivity.getInstance() not found

**Error:**
```
error: cannot find symbol method getInstance()
```

**Solution:** Add singleton pattern to MainActivity.java:
```java
public class MainActivity extends Activity {
    private static MainActivity instance;

    public static MainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        // ...
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
    }
}
```

#### Issue 5: Link errors in build-android.sh

Errors such as `relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be used against symbol` or `unable to find library -lpthread` came from dependencies built by hand. kanaha-android-deps builds every library with `-fPIC` for Android's linker, so rebuild them with its `build-all.sh` rather than patching individual libraries.

### Prerequisites Troubleshooting

**Issue: ANDROID_NDK_HOME is empty**
```bash
# Find installed NDK version
ls ~/Android/Sdk/ndk/
# Example output: 28.0.12916984

# Set to your installed version (update version number as needed)
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/$(ls ~/Android/Sdk/ndk/ | tail -1)
echo "export ANDROID_NDK_HOME=$ANDROID_NDK_HOME" >> ~/.bashrc

# If no NDK installed, install via Android Studio:
# Tools → SDK Manager → SDK Tools tab → NDK (Side by side) → Apply
```

**Issue: ADB not in PATH**
```bash
# Recommended: Use SDK platform-tools (already installed with Android Studio)
export PATH=$HOME/Android/Sdk/platform-tools:$PATH
echo 'export PATH=$HOME/Android/Sdk/platform-tools:$PATH' >> ~/.bashrc
source ~/.bashrc

# Verify
adb --version
# Should show: Android Debug Bridge version 35.x.x or similar

# Alternative: Install system package (older version)
# sudo apt install adb
```

**Issue: sdkmanager XML version warnings**
```
Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered.
```
This warning occurs when command-line tools are older than Android Studio. It's harmless for most operations. To fix:
```bash
# Update command-line tools via Android Studio:
# Tools → SDK Manager → SDK Tools tab → Android SDK Command-line Tools → Update
```

**Issue: sdkmanager command not found**
```bash
# Ensure cmdline-tools path is set
export PATH=$HOME/Android/Sdk/cmdline-tools/latest/bin:$PATH
echo 'export PATH=$HOME/Android/Sdk/cmdline-tools/latest/bin:$PATH' >> ~/.bashrc

# Alternative: Use Android Studio's embedded terminal which has paths pre-configured
```

---

## Device Considerations

The installation process is identical on every device. The same APK has run without modification on phones from 2017 (the Moto X4, since retired) through current flagships. The current rig is a Pixel 10 Pro XL and a Moto G Play 2024.

### Tested Devices

| Device | Year | Android | ADB Tests | WiFi Tests | SFTP Transfer | Last verified | Notes |
|--------|------|---------|-----------|------------|---------------|---------------|-------|
| Google Pixel 10 Pro XL | 2025 | 17 (API 37) | Pass | Pass | Pass (2026-10-16) | 2026-10-19 | A-camera; `nano` flavour, `describeClip` with Gemini Nano passes. Records 4K H.264 by default, 10-bit HLG with `hlg10`, open gate at 2560×1920; `setExposure`/`getExposure`, white balance lock and the RAW probe all verified. Writes limited-range BT.709. Fixed frame rates 15/24/30/60 |
| Motorola Moto G Play 2024 | 2024 | 14 (API 34) | Pass | Pass (API calls) | Not tested | 2026-10-19 | B-camera. Switch Settings > Camera API to Camera2 for manual controls. A 1080p camera: its encoder takes 1920×1088 at most, so `quality: "4K"` records 1920×1080; open gate 1600×1200; no 10-bit. Writes full-range BT.601, which needs converting before cutting with the Pixel. Refuses `am` from apps (exit 255), so camera control runs through the request-file fallback (`CameraIpcPoller`); its camera HAL can take over 500 ms to reopen when switching to video, which `startRecording` waits for. First frame about 1.1 s after `start_at` |
| Motorola Moto X4 | 2017 | 9 (API 28) | Pass | Pass | Pass | 2026-09-05 | Retired: the phone died during a battery replacement in October 2026. Results kept as the oldest device the APK ran on |
| Google Pixel 9 Pro | 2024 | 14 | Pass | Pass | Pass | 2026-01 (device traded in) | Superseded by the 10 Pro XL |

**Operations verified on the tested devices (via ADB and WiFi):**
- `getStatus` - Camera status and device info
- `startRecording` / `stopRecording` - Video recording control
- `listFiles` - List video files on device
- `deleteFiles` - Delete video files
- `sftpTransfer` - SFTP file transfer with PKI authentication

Testing showed Kanaha working across **8 years of Android devices** (the 2017 Moto X4 to the 2025 Pixel 10 Pro XL) with the same APK and no code modifications.

### Recording size and the video encoder

A camera offers far more video sizes than its hardware encoder can take, and a size the
encoder refuses makes the recording fail to start at all. Under Camera2 the Moto G Play 2024
offers 4080×1836 as its nearest match to 4K; its encoder (`c2.qti.avc.encoder`) is declared
for 1920×1088 and refused the width, so before 2026-10-19 every default recording on it failed.
Kanaha now chooses, for `quality` and for open gate, only sizes it expects the encoder to take,
and `startRecording` reports the size it records as `resolution`.

**The rule, and how far to trust it.** Android's own check,
`MediaCodecInfo.VideoCapabilities.isSizeSupported()`, applies the declared width and height
limits literally and refused 1600×1200 on the Moto, which records it fine. Kanaha instead
accepts a size when its long side is within the encoder's largest dimension and its 16×16
blocks are within the largest frame's (`encoderSupports()` in `CameraControlReceiver.java`).
That rule is inferred from two phones, not documented anywhere:

| Phone | Encoder limit (declared) | Accepted | Refused |
|---|---|---|---|
| Pixel 10 Pro XL | 3840×2160 (H.264), 3840×2176 (HEVC) | 3840×2160, 2560×1920 | 4032×3024 |
| Moto G Play 2024 | 1920×1088 | 1920×1080, 1600×1200 (taller than declared), 1440×1080, 1280×720 | 4080×1836, 3264×1468 |

**Recheck it on the next new phone** before relying on it. Read the encoder's declared limit
(the vendor `c2.*` entries; not `c2.android.*`, Android's software encoder, and not the old
`OMX.*` ones, which on the Moto listed 4096×2176 for an encoder not in use):

```bash
adb shell 'cat /vendor/etc/media_codecs*.xml' | awk '/MediaCodec name="c2\.[a-z]+\.(avc|hevc)\.encoder"/ {n=$2} /name="size"/ && n {if (n !~ /c2\.android/) print n, $3, $4; n=""}'
```

On the Pixel 10 Pro XL this prints `c2.google.avc.encoder ... max="3840x2160"` and
`c2.google.hevc.encoder ... max="3840x2176"`; on the Moto G Play 2024,
`c2.qti.avc.encoder ... max="1920x1088"`.

Then record with `quality` set to `"4K"`, `"1080p"` and `"720p"` and with `open_gate`, and
compare each response's `resolution` with the file (`ffprobe -v error -select_streams v:0
-show_entries stream=width,height -of csv=p=0 take.mp4`). A take that fails to start, or a
`resolution` that does not match the file, means the rule is wrong for that phone.

### Hardware Capability Differences

While the APK is identical, hardware capabilities vary by device:

| Feature | Modern Flagship | Older Devices |
|---------|-----------------|---------------|
| Max Video Resolution | 4K@60fps | 4K@30fps or 1080p |
| RAM | 8-16GB | 3-4GB |
| HTTP/2 Responsiveness | < 100ms | < 200ms |
| Battery Life | 5000mAh+ | 3000mAh |

The C-based server handles these differences gracefully—the same code runs efficiently regardless of available RAM or CPU speed.

### Tweaks for Serious Use on Older Hardware

Kanaha ships one configuration for every phone deliberately — the defaults are
not tuned per device. Everything below is optional and applies only to sustained
production use on older hardware. Verified on the Moto X4 (2017, API 28, 2.8 GB
RAM, since retired) on 2026-09-05.

**Switch to the Camera2 API if you want manual controls.** Open Camera only
auto-selects Camera2 for Google (API 31+), Nokia (API 28+), Samsung (API 31+) and
OnePlus (API 34+). Motorola is not on that list, so a fresh install runs the
**legacy Camera1 API** — even on the X4, which reports Camera2 hardware
`LEVEL_3`, the highest tier. Recording, the HTTP API and SFTP all work fine on
Camera1; what you lose is manual exposure/focus/white balance and RAW.
Settings > Camera API > Camera2 API (the app restarts). On the Moto G Play 2024 under Camera2,
manual ISO, shutter, kelvin, focus and the white balance lock all work (2026-10-19).

**Bring the app to the foreground before calling `startRecording`.** The status
fields `camera_available` and `preview_active` are `false` while the activity is
backgrounded, and `startRecording` fails at once with
`{"success": false, "error": "Kanaha Camera is not in the foreground. ..."}`. This is not
age-related, but it is the first thing to check when a scripted shoot fails to
start. Poll `getStatus` and confirm `camera_available` before issuing the start.

**Memory: measured, and not the problem people expect.** `mod_axis2` uses an
allocator whose `free` has no body, and `httpd.conf` ships
`MaxConnectionsPerChild 0`, so the httpd child never recycles and its memory only
grows. Measured on the X4 over 200 authenticated JSON requests:

| | |
|---|---|
| Growth per request | ~7 kB |
| Requests to grow 256 MB | ~37,600 |
| At 1 request/second, continuously | ~10.5 hours |

For camera control — dozens of commands per shoot — this never matters. Note
this figure is a **floor, not a worst case**: it was measured on lightweight
status calls, and heavier operations allocate more.

**Do not "fix" this with `MaxConnectionsPerChild`.** The app launches httpd with
`-X` (single-process mode), so there is no parent to recycle the child: on
reaching a non-zero connection limit the one process simply exits and the service
dies with no replacement. Leave it at `0`. The real mitigation for a genuinely
long-running session is to restart the app, which is why the value is kept
identical across every device rather than tuned per phone.

**Storage is the real constraint.** The X4 under test had 2174 MB free and
records 4K. That is roughly four minutes of 4K footage. Check
`storage_available_mb` from `getStatus` before a shoot, and use `deleteFiles` or
`sftpTransfer` between takes rather than after.

**USB on eight-year-old hardware.** A worn or partially-seated connector gives
power but not data: the phone reports "charging slowly", offers a USB mode in its
settings, and never appears in `lsusb` on the host at all. If the host sees no
device, the problem is physical — no adb configuration will help. Try a cable
known to carry data, then clean the port (powered off, wooden toothpick or
plastic SIM tool, never metal). Android 9 has no wireless-debugging pairing, so
`adb tcpip 5555` still has to be enabled over USB at least once.

**Developer note: `.probe` builds cannot serve camera control.**
`camera_control_service.c` hardcodes its broadcast target as
`org.kanaha.camera`, so a build carrying `applicationIdSuffix ".probe"` sends the
intent to a receiver that is not there and the request **blocks until the client
times out** rather than failing fast. The suffix is opt-in
(`./gradlew assembleDebug -PprobeSuffix`) and should be used only for testing the
camera and preview path.

### Tips for Legacy Hardware

**Storage Limitations:**
```bash
# Older devices may have limited storage (32-64GB)
# Monitor available space:
adb shell df -h /data | grep /data

# Consider microSD card for video storage if supported:
adb shell sm list-disks
```

**Battery Optimization for Long Sessions:**
```bash
# Essential for extended recording:
1. Disable battery saver mode during production
2. Keep device plugged in for extended shoots
3. Reduce screen brightness to minimum
4. Enable "Stay awake" in Developer options
5. Disable background sync and unused apps
```

**USB Connection Tips:**
- Windows may require manufacturer drivers (Linux/macOS work automatically)
- Use high-quality USB cables; older devices with worn ports may be finicky
- ADB over WiFi (`adb tcpip 5555`) is often more reliable than USB for older devices

**HTTP/2 Configuration:** The `H2MaxSessionStreams 1` setting in `http2-performance.conf` ensures stable operation on all devices regardless of age—this single-stream mode has worked on every device tested, from the Moto X4 (2017) to the Pixel 10 Pro XL (2025).

### Installation Command Summary (All Devices)

```bash
# Complete installation flow (works on any ARM64 Android device):
adb devices                                                  # Verify device connected
adb install -r -d app/build/outputs/apk/foss/debug/app-foss-debug.apk  # -d allows downgrade
adb shell am start -n org.kanaha.camera/.MainActivity
adb logcat -s KanahaCamera:V                                 # Monitor startup

# Verify Apache httpd service
adb shell dumpsys activity services | grep ApacheService
```

### Recommendation for Multi-Camera Setups

For productions with mixed hardware, assign roles based on capabilities:
- **Primary camera**: Use newest device for highest quality/resolution
- **Secondary cameras**: Older devices work well for coverage angles

All cameras receive the same API commands and produce compatible footage.

---

## Device Compatibility Assessment

Kanaha is a standard Android application that uses standard Android APIs. It works on **any Android device** that meets the minimum requirements, regardless of manufacturer.

### Compatibility Requirements

| Requirement | Details |
|-------------|---------|
| **Android Version** | 6.0+ (API 23); `nano` flavour 8.0+ (API 26) |
| **Camera API** | Camera2 API support |
| **Permissions** | Camera, Microphone, Storage, Internet |
| **Developer Options** | USB Debugging enabled |

**No special device modifications required** - no root access, no bootloader unlock, no custom ROM.

### Verified Manufacturers

| Manufacturer | Tested Device | Android Version | ADB | WiFi | SFTP |
|--------------|---------------|-----------------|-----|------|------|
| **Google** | Pixel 10 Pro XL (2025) | Android 17 | Pass | Pass | Pass |
| **Motorola** | Moto G Play 2024 | Android 14 | Pass | Pass (API calls) | Not tested |
| **Motorola** | Moto X4 (2017, retired) | Android 9 | Pass | Pass | Pass |
| **Samsung** | Not yet tested | - | - | - | - |
| **OnePlus** | Not yet tested | - | - | - | - |
| **Other** | Any Android 6.0+ | - | Expected to work | Expected to work | Expected to work |

### Why Google and Motorola Were Tested

Google and Motorola have traditionally been more developer-friendly with open documentation, stock Android experiences, and accessible Developer Options. The app uses standard APIs, with one exception that does depend on the device: the native server reaches the camera code by running `am broadcast`, and whether an app may run `am` is up to the device's build. The Moto X4 (Android 9) and the Pixels (Android 14 and 17) allow it; the Moto G Play 2024 (Android 14) refuses it with exit code 255. On such a device the native server falls back to writing the request to a file that `CameraIpcPoller` in the app sends as the same broadcast, adding up to half a second per call (the poll interval); that path needs the app running, and fails after 5 s when it is not. "Expected to work" below therefore means expected, not verified.

Tests on the Moto X4 (2017, since retired), the Moto G Play 2024 and the Pixel 10 Pro XL (2025) showed compatibility across:
- **8 years** of Android development (2017-2025)
- **Two different manufacturers** (Motorola, Google)
- **Multiple Android versions** (Android 9 / API 28 to Android 17 / API 37)
- **Different hardware capabilities** (2.8 GB RAM to 16 GB RAM)

### Device Selection Recommendations

**For Production Deployments:**

1. **Primary Camera:** Use your best available device (highest resolution, best stabilization)
2. **Secondary Cameras:** Any working Android 6.0+ device
3. **Budget Fleet:** Used devices from any manufacturer work fine

**Practical Considerations:**
- Larger batteries = longer recording sessions
- More storage = more footage before transfer needed
- Faster WiFi = quicker file transfers
- Device age doesn't matter if it runs Android 6.0+

---

## Post-Installation Notes

- **Reboot required** after completing installation to apply udev rules and group membership
- **Disk space**: Allocate ~50GB for Android SDK/NDK and build artifacts
- **RAM**: Minimum 16GB recommended for Android builds (32GB optimal)
- **Shell restart**: Run `source ~/.bashrc` or open a new terminal after setting environment variables

**Device Requirements:**
- Any ARM64 Android device running API 23+ (Android 6.0 Marshmallow or later)
- USB debugging enabled
- Developer options unlocked
- Minimum 2GB free storage for installation

**Performance Optimization:**
- Modern devices (Pixel 9 Pro, etc.) support full 4K@60fps recording
- Enable "High Performance Mode" in Developer Options if available
- Disable battery optimization for Kanaha app: Settings → Battery → Battery optimization → Kanaha → Don't optimize

---

## Version History

| Date | Changes |
|------|---------|
| 2026-10-16 | Build Steps rewritten for kanaha-android-deps and `build-android.sh`; removed the hand-built httpd and Axis2/C steps and the host `-dev` packages they needed |
| 2026-01-31 | Fixed Android 15 16KB page alignment warning: added `-Wl,-z,max-page-size=16384 -Wl,-z,separate-loadable-segments` to all native library link flags, switched to c++_static STL. See [ANDROID_CROSS_COMPILATION.md](ANDROID_CROSS_COMPILATION.md#android-15-16kb-page-alignment) for details |
| 2026-01-31 | New build with Axis2/C AXIS2C-1708 security hardening (TLS 1.2+, XXE protection, JSON limits, NTLM removed) |
| 2026-01-22 | Consolidated installation guide from MULTI_CAMERA_DEPLOYMENT_SYSTEM.md |
| 2026-01-07 | Updated architecture docs, clarified libhttpd.so vs CMake artifacts |
| 2025-12-31 | Initial document with complete APK building guide and troubleshooting |

## Related Documentation

- [ANDROID_CROSS_COMPILATION.md](ANDROID_CROSS_COMPILATION.md) - Cross-compiling dependencies
- [MULTI_CAMERA_DEPLOYMENT_SYSTEM.md](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md) - System architecture and multi-camera features
