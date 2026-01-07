# Android APK Building Guide

This document provides step-by-step instructions for building the Kanaha Camera App APK for Google Pixel 9 Pro (ARM64).

## Prerequisites

### 1. Android SDK and NDK

```bash
# Ensure Android SDK is installed
ls $HOME/Android/Sdk

# Install/verify NDK r28
$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "ndk;28.0.12916984"
```

### 2. Cross-compiled Dependencies

All dependencies must be cross-compiled for ARM64 with `-fPIC`:
- See [ANDROID_CROSS_COMPILATION.md](ANDROID_CROSS_COMPILATION.md)

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

### Step 1: Create local.properties

```bash
cd /home/robert/repos/kanaha/kanaha-camera-app

cat > local.properties << 'EOF'
sdk.dir=/home/robert/Android/Sdk
ndk.version=28.0.12916984
EOF
```

### Step 2: Build the APK

```bash
# Clean previous builds
./gradlew clean

# Build debug APK
./gradlew assembleDebug
```

### Step 3: Verify the APK

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

## Common Issues and Solutions

### Issue 1: libncurses.so.5 not found (RenderScript)

**Error:**
```
llvm-rs-cc: error while loading shared libraries: libncurses.so.5
```

**Solution:** Create wrapper script or symlinks:
```bash
# Create symlink (if libncurses.so.6 exists)
sudo ln -s /usr/lib/x86_64-linux-gnu/libncurses.so.6 /usr/lib/x86_64-linux-gnu/libncurses.so.5
```

### Issue 2: CMake version not found

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

### Issue 3: R class namespace mismatch

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

### Issue 4: MainActivity.getInstance() not found

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

### Issue 5: PIC relocation errors

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

### Issue 6: pthread not found on Android

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

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/jniLibs/arm64-v8a/libhttpd.so` | Prebuilt Apache httpd with Axis2/C (cross-compiled) |
| `app/src/main/cpp/axis2c/camera_control_service.c` | Camera control service (compiled into libhttpd.so) |
| `app/src/main/cpp/CMakeLists.txt` | Native build configuration |
| `app/src/main/java/org/kanaha/camera/CameraControlReceiver.java` | Intent receiver for camera operations |
| `app/src/main/java/org/kanaha/camera/ApacheService.java` | Service managing Apache httpd lifecycle |

**Note:** The `libhttpd.so` binary is built separately using the cross-compilation script at `~/android-cross-builds/link-httpd-axis2.sh`. Changes to `camera_control_service.c` require rebuilding this binary.

---

## Verification

### Check APK Contents

```bash
# List native libraries
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

### Check Library Dependencies

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

## Version History

| Date | Changes |
|------|---------|
| 2026-01-07 | Updated architecture docs, clarified libhttpd.so vs CMake artifacts |
| 2025-12-31 | Initial document with complete APK building guide and troubleshooting |

## Related Documentation

- [ANDROID_CROSS_COMPILATION.md](ANDROID_CROSS_COMPILATION.md) - Cross-compiling dependencies
- [MULTI_CAMERA_DEPLOYMENT_SYSTEM.md](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md) - System architecture
