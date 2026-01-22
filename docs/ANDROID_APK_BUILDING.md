# Android APK Building Guide

This document provides comprehensive step-by-step instructions for building the Kanaha Camera App APK from source and installing it on any ARM64 Android device running API 21+ (Android 5.0 Lollipop or later). The same APK works across a wide range of devices—tested on hardware from 2017 Moto X4 to 2024 Pixel 9 Pro with no device-specific modifications.

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

### Step 4: Install Additional Dependencies for Axis2/C HTTP/2 Build

```bash
# Install libraries required for Apache httpd + Axis2/C native compilation
sudo apt install -y \
    libssl-dev \
    libcurl4-openssl-dev \
    libnghttp2-dev \
    libxml2-dev \
    libz-dev \
    libapr1-dev \
    libaprutil1-dev \
    libpcre2-dev

# Install cross-compilation tools for Android NDK
sudo apt install -y \
    gcc-aarch64-linux-gnu \
    g++-aarch64-linux-gnu \
    binutils-aarch64-linux-gnu
```

**Why We Don't Install apache2/apache2-dev**

Notice that we did **NOT** install `apache2` or `apache2-dev` packages. Here's why:

```bash
# DO NOT RUN THIS:
# sudo apt install apache2 apache2-dev

# Reason: Ubuntu 25.10's Apache package lacks HTTP/2 support
ls /usr/lib/apache2/modules/mod_h2.so
# Result: No such file or directory
```

**Why Ubuntu 25.10 Apache Won't Work:**
- Default apache2 package (2.4.64) does **not include mod_h2** (HTTP/2 module)
- No `libapache2-mod-h2` package available in Ubuntu 25.10 repositories
- Using system Apache would result in HTTP/1.1-only builds (unusable for Kanaha)

**Solution**: We compile Apache httpd from source with HTTP/2 explicitly enabled via `--enable-http2` configure flag. This ensures mod_h2 is built and statically linked into the Android native library.

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

# Motorola devices (including Moto X4)
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
- Android NDK r27 or r28
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

## Local C Code Verification (Without Android NDK)

Before building the full APK, you can verify that native C code compiles correctly using standard GCC on your Ubuntu development machine. This is useful for:

- **Quick syntax checking** during C code development
- **CI/CD pipelines** that don't have Android NDK installed
- **Contributors** who want to verify C changes without full Android Studio setup

### Prerequisites for Local Verification

Only `build-essential` is required (installed in Step 1 above):

```bash
# Verify GCC is available
gcc --version
# Should show: gcc (Ubuntu 14.x.x) or similar
```

### Running Local Verification

The `build-tools/` directory contains a Makefile and Android API stubs for local compilation:

```bash
cd kanaha-camera-app/build-tools

# Quick syntax check (fastest - no object files created)
make check

# Build shared objects (deeper verification)
make

# Build and inspect exported symbols
make test

# Clean build artifacts
make clean
```

**Example Output:**
```
$ make check
Syntax checking: camera_control_service.c
gcc -fsyntax-only -std=c11 -Wall -Wextra -O2 -fPIC -D_GNU_SOURCE -DANDROID ...
OK: camera_control_service.c

Syntax checking: apache_httpd_android.c
gcc -fsyntax-only -std=c11 -Wall -Wextra -O2 -fPIC -D_GNU_SOURCE -DANDROID ...
OK: apache_httpd_android.c

All syntax checks passed!
```

### How It Works

The local build uses **stub headers** that mock Android-specific APIs:

```
build-tools/
├── Makefile              # Build commands
├── stubs/
│   └── android/
│       └── log.h         # Stub for <android/log.h> - prints to stderr
└── build/                # Generated .so files (gitignored)
    ├── libcamera_control_service.so
    └── libapache_httpd_android.so
```

The stub `android/log.h` provides dummy implementations of `__android_log_print()` and related functions that print to stderr instead of Android's logcat. This allows the C code to compile and even run basic tests on Linux.

### What Local Verification Checks

| Aspect | Local Verify | Full NDK Build |
|--------|--------------|----------------|
| C syntax correctness | ✓ | ✓ |
| Header includes | ✓ | ✓ |
| Function signatures | ✓ | ✓ |
| Symbol exports | ✓ | ✓ |
| Android NDK compatibility | ✗ | ✓ |
| ARM64 cross-compilation | ✗ | ✓ |
| APK packaging | ✗ | ✓ |

### GitHub Actions CI

Pull requests automatically run local verification via GitHub Actions. The workflow is defined in `.github/workflows/verify-native-code.yml` and triggers on changes to `app/src/main/cpp/**`.

Contributors receive automatic feedback on C code changes without needing any local Android development setup.

---

## Build Steps

### Step 1: Clone and Prepare Source

```bash
# Clone the Kanaha repository
cd ~/repos
git clone https://github.com/your-org/kanaha.git
cd kanaha

# Initialize submodules (includes Axis2/C HTTP/2, OpenCamera)
git submodule update --init --recursive

# Verify NDK is configured
echo "NDK Location: $ANDROID_NDK_HOME"
# Should point to: ~/Android/Sdk/ndk/28.0.12916984 or similar
```

### Step 2: Configure Build Environment

```bash
cd kanaha-camera-app

# Set up local.properties for Android SDK/NDK paths
cat > local.properties <<EOF
sdk.dir=$HOME/Android/Sdk
ndk.dir=$HOME/Android/Sdk/ndk/28.0.12916984
cmake.dir=$HOME/Android/Sdk/cmake/3.22.1
EOF

# Verify Gradle wrapper
./gradlew --version
```

### Step 3: Build Apache httpd with HTTP/2 for Android

**CRITICAL: Apache httpd Dependency**

Kanaha requires **Apache httpd 2.4.x compiled from source** with HTTP/2 support (mod_h2). This is a **build-time dependency only** - the compiled httpd binary is statically linked into the Android APK and ships with the app.

**Why System Apache Won't Work:**
```bash
# Testing Ubuntu 25.10 system Apache for HTTP/2 support:
sudo apt install apache2 apache2-dev

# Check for mod_h2:
ls /usr/lib/apache2/modules/mod_h2.so
# Result: No such file or directory

# Try to enable mod_h2:
sudo a2enmod h2
# Result: ERROR: Module h2 does not exist!
```

**Solution: Build Apache httpd from Source for Android**

You must compile Apache httpd from source with HTTP/2 enabled, then cross-compile it for Android ARM64 using the NDK.

**Step 3a: Compile Apache httpd from Source (x86_64 Build Host)**

First, build Apache for your Ubuntu development machine:

```bash
# Install build dependencies
sudo apt install -y build-essential libtool-bin \
    libpcre3-dev libssl-dev libnghttp2-dev \
    libexpat1-dev libapr1-dev libaprutil1-dev wget

# Create build directory
mkdir -p ~/apache-android-build
cd ~/apache-android-build

# Download Apache httpd 2.4.64 (or latest)
wget https://archive.apache.org/dist/httpd/httpd-2.4.64.tar.gz
tar -xzf httpd-2.4.64.tar.gz
cd httpd-2.4.64

# Configure with HTTP/2 support (x86_64 host)
./configure \
    --prefix=$HOME/apache-httpd-x86 \
    --enable-so \
    --enable-ssl \
    --enable-http2 \
    --enable-headers \
    --enable-rewrite \
    --with-ssl \
    --with-nghttp2 \
    --enable-mods-shared=all

# Compile
make -j$(nproc)

# Install to local directory
make install

# Verify HTTP/2 support
$HOME/apache-httpd-x86/bin/httpd -M | grep h2
# Should show: http2_module (shared)
```

**Step 3b: Cross-Compile Dependencies for Android ARM64**

Cross-compiling Apache httpd and its dependencies for Android requires building several libraries from source with specific patches for Android compatibility.

> **See [ANDROID_CROSS_COMPILATION.md](ANDROID_CROSS_COMPILATION.md) for complete instructions.**

The cross-compilation guide includes:
- Step-by-step build instructions for OpenSSL, nghttp2, expat, PCRE2, APR, APR-util, and Apache httpd
- **Required APR patches** for Android (union semun, strerror_r issues)
- **Apache httpd workarounds** (gen_test_char, mod_ext_filter)
- Troubleshooting for common errors
- Environment variable reference

**Quick Summary:**

| Component | Output Location |
|-----------|----------------|
| OpenSSL, nghttp2, APR, etc. | `~/android-cross-builds/deps/arm64-v8a/lib/` |
| Apache httpd | `~/android-cross-builds/apache/` |

**Prerequisites Check:**
```bash
# Verify NDK is installed
ls $HOME/Android/Sdk/ndk/28.0.12916984/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang

# Create build directory (NOT inside ~/Android/Sdk)
mkdir -p ~/android-cross-builds/deps/arm64-v8a
```

After completing the cross-compilation guide, continue with Step 3c below.

**Step 3c: Build Axis2/C with Android Apache httpd**

Now build Axis2/C against the Android cross-compiled Apache httpd:

```bash
# Set environment variables
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export DEPS_DIR=$HOME/android-cross-builds/deps/arm64-v8a
export APACHE_DIR=$HOME/android-cross-builds/apache/arm64-v8a

# Navigate to Kanaha native code directory
cd /home/robert/repos/kanaha/kanaha-camera-app/app/src/main/cpp

# Configure Axis2/C with HTTP/2 support
./configure \
    --host=aarch64-linux-android \
    --enable-http2 \
    --enable-json-rpc \
    --with-apache=$APACHE_DIR \
    --with-apr=$DEPS_DIR \
    --with-nghttp2=$DEPS_DIR \
    --with-ssl=$DEPS_DIR \
    --prefix=$PWD/build/arm64-v8a \
    CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang \
    CXX=$TOOLCHAIN/bin/aarch64-linux-android21-clang++ \
    CFLAGS="-I$DEPS_DIR/include" \
    LDFLAGS="-L$DEPS_DIR/lib"

# Build native components
make clean
make -j$(nproc)
make install

# Return to project root
cd ../../../..
```

### Step 4: Build APK

```bash
# Clean previous builds
./gradlew clean

# Build debug APK for testing
./gradlew assembleDebug

# Or build release APK (requires signing configuration)
./gradlew assembleRelease

# APK output location:
# Debug: app/build/outputs/apk/debug/app-debug.apk
# Release: app/build/outputs/apk/release/app-release.apk
```

**Build Variants:**
- `debug` - Includes debugging symbols, not optimized, allows USB debugging
- `release` - Optimized, requires signing key, production-ready

### Step 5: Verify the APK

```bash
# Check APK size (should be 10-15MB)
ls -lh app/build/outputs/apk/debug/app-debug.apk

# Check native library size (should be 5-7MB with all libraries linked)
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libkanaha
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

### CMakeLists.txt Key Settings

```cmake
# Cross-compiled dependencies path
set(CROSS_COMPILED_DEPS "$ENV{HOME}/android-cross-builds/deps/arm64-v8a")

# Only use cross-compiled libs for arm64-v8a
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(USE_CROSS_COMPILED_LIBS ON)
endif()

# Import static libraries
add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libssl.a)
# ... repeat for other libraries

# Link all libraries
target_link_libraries(kanaha-camera-control
    ${log-lib}
    ${android-lib}
    ${z-lib}
    json-c
    nghttp2
    ssl
    crypto
    apr-1
    aprutil-1
    expat
    # Axis2/C libraries...
)
```

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
adb install -r app/build/outputs/apk/debug/app-debug.apk

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

The Android build process creates a **single statically-linked native library** that includes:

```
libkanaha-camera-control.so (arm64-v8a)
    ├── Apache httpd core (with mod_h2 compiled in)
    ├── mod_ssl (SSL/TLS support)
    ├── mod_http2 (HTTP/2 support)
    ├── mod_axis2 (Axis2/C web services)
    ├── Axis2/C runtime
    └── All dependencies (OpenSSL, nghttp2, etc.)
```

**Size:** ~15-20MB for arm64-v8a architecture

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

**Note:** The `libhttpd.so` binary is built separately using the cross-compilation script at `~/android-cross-builds/link-httpd-axis2.sh`. Changes to `camera_control_service.c` require rebuilding this binary.

### Verification

```bash
# List native libraries in APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so$"

# Should show:
# lib/arm64-v8a/libhttpd.so              (~10MB) - Apache httpd with Axis2/C (prebuilt)
# lib/arm64-v8a/libkanaha-camera-control.so      - CMake build artifact (unused)
# lib/arm64-v8a/libkanaha_httpd.so               - CMake executable target (unused)
# lib/arm64-v8a/libc++_shared.so                 - C++ runtime
#
# Note: Only libhttpd.so is actually used - it's launched via ProcessBuilder.
# The other .so files are CMake build artifacts that could be removed.
```

```bash
# Extract and check dependencies of the main httpd binary
cd /tmp
unzip ~/repos/kanaha/kanaha-camera-app/app/build/outputs/apk/debug/app-debug.apk lib/arm64-v8a/libhttpd.so
readelf -d lib/arm64-v8a/libhttpd.so | grep NEEDED
```

Expected output should show only Android system libraries (liblog.so, libandroid.so, libz.so, libm.so, libc.so, libdl.so). All other dependencies (OpenSSL, APR, Axis2/C, etc.) are statically linked.

---

## Rebuilding libhttpd.so

If you modify `camera_control_service.c` or other native code compiled into `libhttpd.so`, you must rebuild it separately:

```bash
# Rebuild Apache httpd with Axis2/C and camera service
~/android-cross-builds/link-httpd-axis2.sh

# Copy to jniLibs
cp ~/android-cross-builds/httpd-2.4.66/httpd \
   ~/repos/kanaha/kanaha-camera-app/app/src/main/jniLibs/arm64-v8a/libhttpd.so

# Rebuild APK
./gradlew assembleDebug
```

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
adb install app/build/outputs/apk/debug/app-debug.apk
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

#### Issue 5: PIC relocation errors

**Error:**
```
ld.lld: error: relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be used against symbol
```

**Cause:** Static libraries compiled without `-fPIC` cannot be linked into shared libraries.

**Solution:** Rebuild ALL dependencies (APR, APR-util, Axis2/C) with:
```bash
export CFLAGS="-fPIC"
./configure --with-pic ...
```

#### Issue 6: pthread not found on Android

**Error:**
```
ld.lld: error: unable to find library -lpthread
```

**Cause:** Android's Bionic libc includes pthread, no separate library needed.

**Solution:** Modify Axis2/C configure.ac:
```autoconf
case "$host" in
  *android*)
    PTHREADLIBS=""
    ;;
  *)
    AC_CHECK_LIB(pthread, pthread_key_create)
    PTHREADLIBS="-lpthread"
    ;;
esac
AC_SUBST(PTHREADLIBS)
```

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

## Legacy Device Considerations (Moto X4 and Similar)

The installation process is identical for older devices. The same APK runs without modification on devices from 2017 (Moto X4) through current flagships. The following notes apply to legacy hardware:

### Tested Devices

| Device | Year | Android | ADB Tests | WiFi Tests | SFTP Transfer | Notes |
|--------|------|---------|-----------|------------|---------------|-------|
| Google Pixel 9 Pro | 2024 | 15 (API 36) | Pass | Pass | Pass | Primary development device |
| Motorola Moto X4 | 2017 | 9 (API 28) | Pass | Pass | Pass | No adjustments needed |

**Tests Verified on Both Devices (via ADB and WiFi):**
- `getStatus` - Camera status and device info
- `startRecording` / `stopRecording` - Video recording control
- `listFiles` - List video files on device
- `deleteFiles` - Delete video files
- `sftpTransfer` - SFTP file transfer with PKI authentication

Testing demonstrated that Kanaha works across **7 years of Android development** (2017-2024) with the exact same APK and no code modifications.

### Hardware Capability Differences

While the APK is identical, hardware capabilities vary by device:

| Feature | Modern Flagship | Older Devices |
|---------|-----------------|---------------|
| Max Video Resolution | 4K@60fps | 4K@30fps or 1080p |
| RAM | 8-16GB | 3-4GB |
| HTTP/2 Responsiveness | < 100ms | < 200ms |
| Battery Life | 5000mAh+ | 3000mAh |

The C-based server handles these differences gracefully—the same code runs efficiently regardless of available RAM or CPU speed.

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

**HTTP/2 Configuration:** The `H2MaxSessionStreams 1` setting in `http2-performance.conf` ensures stable operation on all devices regardless of age—this single-stream mode works reliably from Moto X4 (2017) through Pixel 9 Pro (2024).

### Installation Command Summary (All Devices)

```bash
# Complete installation flow (works on any ARM64 Android device):
adb devices                                                  # Verify device connected
adb install -r -d app/build/outputs/apk/debug/app-debug.apk  # -d allows downgrade
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
| **Android Version** | 5.0+ (API 21) |
| **Camera API** | Camera2 API support |
| **Permissions** | Camera, Microphone, Storage, Internet |
| **Developer Options** | USB Debugging enabled |

**No special device modifications required** - no root access, no bootloader unlock, no custom ROM.

### Verified Manufacturers

| Manufacturer | Tested Device | Android Version | ADB | WiFi | SFTP |
|--------------|---------------|-----------------|-----|------|------|
| **Google** | Pixel 9 Pro (2024) | Android 15 | Pass | Pass | Pass |
| **Motorola** | Moto X4 (2017) | Android 9 | Pass | Pass | Pass |
| **Samsung** | Not yet tested | - | - | - | - |
| **OnePlus** | Not yet tested | - | - | - | - |
| **Other** | Any Android 5.0+ | - | Expected to work | Expected to work | Expected to work |

### Why Google and Motorola Were Tested

Google and Motorola have traditionally been more developer-friendly with open documentation, stock Android experiences, and accessible Developer Options. However, **for Kanaha specifically, there is no functional difference between manufacturers** - the app uses standard APIs available on all Android devices.

The successful test on Moto X4 (2017) and Pixel 9 Pro (2024) demonstrates compatibility across:
- **7 years** of Android development (2017-2024)
- **Two different manufacturers** (Motorola, Google)
- **Multiple Android versions** (Android 9 to Android 15)
- **Different hardware capabilities** (3GB RAM to 16GB RAM)

### Device Selection Recommendations

**For Production Deployments:**

1. **Primary Camera:** Use your best available device (highest resolution, best stabilization)
2. **Secondary Cameras:** Any working Android 5.0+ device
3. **Budget Fleet:** Used devices from any manufacturer work fine

**Practical Considerations:**
- Larger batteries = longer recording sessions
- More storage = more footage before transfer needed
- Faster WiFi = quicker file transfers
- Device age doesn't matter if it runs Android 5.0+

---

## Post-Installation Notes

- **Reboot required** after completing installation to apply udev rules and group membership
- **Disk space**: Allocate ~50GB for Android SDK/NDK and build artifacts
- **RAM**: Minimum 16GB recommended for Android builds (32GB optimal)
- **Shell restart**: Run `source ~/.bashrc` or open a new terminal after setting environment variables

**Device Requirements:**
- Any ARM64 Android device running API 21+ (Android 5.0 Lollipop or later)
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
| 2026-01-22 | Consolidated installation guide from MULTI_CAMERA_DEPLOYMENT_SYSTEM.md |
| 2026-01-07 | Updated architecture docs, clarified libhttpd.so vs CMake artifacts |
| 2025-12-31 | Initial document with complete APK building guide and troubleshooting |

## Related Documentation

- [ANDROID_CROSS_COMPILATION.md](ANDROID_CROSS_COMPILATION.md) - Cross-compiling dependencies
- [MULTI_CAMERA_DEPLOYMENT_SYSTEM.md](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md) - System architecture and multi-camera features
