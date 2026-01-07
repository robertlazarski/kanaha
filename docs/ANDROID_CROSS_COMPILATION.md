# Android ARM64 Cross-Compilation Guide

This document provides comprehensive instructions for cross-compiling Apache httpd and its dependencies for Android ARM64 (aarch64). It includes all patches and workarounds discovered during development.

## Overview

The Kanaha Camera App requires Apache httpd with HTTP/2 (mod_h2) support compiled for Android ARM64. Since the Android NDK does not include pre-built versions of Apache httpd or its dependencies, you must cross-compile everything from source.

### What Gets Built

| Component | Purpose | Output |
|-----------|---------|--------|
| OpenSSL | TLS/SSL support | libssl.a, libcrypto.a |
| nghttp2 | HTTP/2 protocol | libnghttp2.a |
| expat | XML parsing (APR-util dep) | libexpat.a |
| PCRE2 | Regular expressions | libpcre2-8.a |
| APR | Apache Portable Runtime | libapr-1.a |
| APR-util | APR utilities | libaprutil-1.a |
| Apache httpd | HTTP server | libmain.a + modules |
| json-c | JSON parsing (Axis2/C dep) | libjson-c.a |
| Axis2/C | SOAP/REST web services | libaxis2_engine.a + many libs (~9MB total) |

### Build Directory Structure

```
~/android-cross-builds/
├── apache/              # Apache httpd build output
├── deps/
│   └── arm64-v8a/       # Cross-compiled libraries
│       ├── include/     # Header files
│       │   ├── apr-1/   # APR headers
│       │   ├── json-c/  # json-c headers
│       │   └── axis2-2.0.0/  # Axis2/C headers (256 files)
│       └── lib/         # Static libraries (.a files)
├── httpd-2.4.x/         # Extracted Apache source
├── apr-1.7.x/           # Extracted APR source
├── apr-util-1.6.x/      # Extracted APR-util source
├── openssl-3.x/         # Extracted OpenSSL source
├── nghttp2-1.x/         # Extracted nghttp2 source
├── expat-2.x/           # Extracted expat source
├── pcre2-10.x/          # Extracted PCRE2 source
└── json-c-x.xx/         # Extracted json-c source
```

> **Note:** Axis2/C must be compiled from source. Kanaha is a new project in rapid development, and we maintain patches and enhancements to Axis2/C (HTTP/2 transport, JSON-RPC services, Android compatibility) that are not yet upstream. Source: [github.com/apache/axis-axis2-c-core](https://github.com/apache/axis-axis2-c-core)

> **Important:** Do NOT place cross-compiled builds inside `~/Android/Sdk`. That directory is managed by Android Studio and SDK Manager.

---

## Prerequisites

### NDK Installation

Ensure Android NDK r28 (or latest) is installed:

```bash
# Install via Android Studio SDK Manager, or:
$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "ndk;28.0.12916984"

# Verify NDK
ls $HOME/Android/Sdk/ndk/28.0.12916984/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang
```

### Environment Variables

Add to `~/.bashrc`:

```bash
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984  # or latest
export NDK_ROOT=$ANDROID_NDK_HOME
export PATH=$ANDROID_NDK_HOME:$PATH
```

### Create Build Directory

```bash
mkdir -p ~/android-cross-builds/apache
mkdir -p ~/android-cross-builds/deps/arm64-v8a
cd ~/android-cross-builds
```

### Download Sources

> **Note:** Version numbers may be outdated. Check the official sites for latest versions.

```bash
cd ~/android-cross-builds

# Apache httpd (https://dlcdn.apache.org/httpd/)
curl -L -o httpd-2.4.66.tar.gz https://dlcdn.apache.org/httpd/httpd-2.4.66.tar.gz

# APR (https://dlcdn.apache.org/apr/)
curl -L -o apr-1.7.6.tar.gz https://dlcdn.apache.org/apr/apr-1.7.6.tar.gz

# APR-util
curl -L -o apr-util-1.6.3.tar.gz https://dlcdn.apache.org/apr/apr-util-1.6.3.tar.gz

# OpenSSL (https://www.openssl.org/source/)
curl -L -o openssl-3.2.0.tar.gz https://www.openssl.org/source/openssl-3.2.0.tar.gz

# nghttp2 (https://github.com/nghttp2/nghttp2/releases)
curl -L -o nghttp2-1.64.0.tar.gz https://github.com/nghttp2/nghttp2/releases/download/v1.64.0/nghttp2-1.64.0.tar.gz

# expat (https://github.com/libexpat/libexpat/releases)
curl -L -o expat-2.6.4.tar.gz https://github.com/libexpat/libexpat/releases/download/R_2_6_4/expat-2.6.4.tar.gz

# PCRE2 (https://github.com/PCRE2Project/pcre2/releases)
curl -L -o pcre2-10.44.tar.gz https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.44/pcre2-10.44.tar.gz

# json-c (https://github.com/json-c/json-c/releases)
curl -L -o json-c-0.18-20240915.tar.gz https://github.com/json-c/json-c/archive/refs/tags/json-c-0.18-20240915.tar.gz

# Verify downloads (should be gzip, not HTML error pages)
file *.tar.gz
```

> **Note:** Axis2/C source is not downloaded here - it's maintained in the `axis-axis2-c-core` git repository.

---

## Step-by-Step Build Instructions

### Step 1: Cross-Compile OpenSSL

OpenSSL uses `ANDROID_NDK_ROOT` (not `ANDROID_NDK_HOME`):

```bash
cd ~/android-cross-builds
tar -xzf openssl-3.2.0.tar.gz
cd openssl-3.2.0

# Set NDK environment (note: ANDROID_NDK_ROOT, not ANDROID_NDK_HOME)
export ANDROID_NDK_ROOT=$HOME/Android/Sdk/ndk/28.0.12916984
export PATH=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH

# Configure for Android ARM64
./Configure android-arm64 \
    --prefix=$HOME/android-cross-builds/deps/arm64-v8a \
    -D__ANDROID_API__=21 \
    no-shared \
    no-tests

# Build and install
make -j$(nproc)
make install_sw

# Verify
ls -la ~/android-cross-builds/deps/arm64-v8a/lib/libssl.a
ls -la ~/android-cross-builds/deps/arm64-v8a/lib/libcrypto.a
```

### Step 2: Cross-Compile nghttp2

```bash
cd ~/android-cross-builds
tar -xzf nghttp2-1.64.0.tar.gz
cd nghttp2-1.64.0

# Set NDK environment
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export CXX=$TOOLCHAIN/bin/aarch64-linux-android21-clang++
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib

# Configure
./configure \
    --host=aarch64-linux-android \
    --prefix=$HOME/android-cross-builds/deps/arm64-v8a \
    --disable-shared \
    --enable-static \
    --enable-lib-only

# Build and install
make -j$(nproc)
make install

# Verify
ls -la ~/android-cross-builds/deps/arm64-v8a/lib/libnghttp2.a
```

### Step 3: Cross-Compile expat

APR-util requires expat for XML support:

```bash
cd ~/android-cross-builds
tar -xzf expat-2.6.4.tar.gz
cd expat-2.6.4

# Set NDK environment
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib

# Configure
./configure \
    --host=aarch64-linux-android \
    --prefix=$HOME/android-cross-builds/deps/arm64-v8a \
    --disable-shared \
    --enable-static \
    --without-xmlwf \
    --without-examples \
    --without-tests

# Build and install
make -j$(nproc)
make install
```

### Step 4: Cross-Compile PCRE2

Apache httpd requires PCRE2 for regular expressions:

```bash
cd ~/android-cross-builds
tar -xzf pcre2-10.44.tar.gz
cd pcre2-10.44

# Set NDK environment
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib

# Configure
./configure \
    --host=aarch64-linux-android \
    --prefix=$HOME/android-cross-builds/deps/arm64-v8a \
    --disable-shared \
    --enable-static

# Build and install
make -j$(nproc)
make install
```

### Step 5: Cross-Compile APR (with Android patches)

APR requires patches for Android compatibility. See [APR Android Patches](#apr-android-patches) section below.

```bash
cd ~/android-cross-builds
tar -xzf apr-1.7.6.tar.gz
cd apr-1.7.6

# ============================================
# APPLY ANDROID PATCHES BEFORE CONFIGURING
# See "APR Android Patches" section below
# ============================================

# Set NDK environment
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib

# IMPORTANT: -fPIC is REQUIRED for linking into Android shared libraries
export CFLAGS="-fPIC"

# Configure with cross-compile cache variables
./configure \
    --host=aarch64-linux-android \
    --prefix=$HOME/android-cross-builds/deps/arm64-v8a \
    --disable-shared \
    --enable-static \
    --with-pic \
    ac_cv_file__dev_zero=yes \
    ac_cv_func_setpgrp_void=yes \
    apr_cv_process_shared_works=yes \
    apr_cv_mutex_robust_shared=no \
    apr_cv_tcp_nodelay_with_cork=yes \
    ac_cv_sizeof_struct_iovec=8

# Build and install
make -j$(nproc)
make install
```

### Step 6: Cross-Compile APR-util

```bash
cd ~/android-cross-builds
tar -xzf apr-util-1.6.3.tar.gz
cd apr-util-1.6.3

# Set NDK environment
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib

# IMPORTANT: -fPIC is REQUIRED for linking into Android shared libraries
export CFLAGS="-fPIC -I$HOME/android-cross-builds/deps/arm64-v8a/include"
export LDFLAGS="-L$HOME/android-cross-builds/deps/arm64-v8a/lib"

# Configure with APR and expat
./configure \
    --host=aarch64-linux-android \
    --prefix=$HOME/android-cross-builds/deps/arm64-v8a \
    --with-apr=$HOME/android-cross-builds/deps/arm64-v8a \
    --with-openssl=$HOME/android-cross-builds/deps/arm64-v8a \
    --with-expat=$HOME/android-cross-builds/deps/arm64-v8a \
    --disable-shared \
    --enable-static \
    --with-pic \
    --without-ldap \
    --without-pgsql \
    --without-mysql \
    --without-sqlite3 \
    --without-odbc

# Build and install
make -j$(nproc)
make install
```

### Step 7: Cross-Compile Apache httpd (with workarounds)

Apache httpd requires special handling for cross-compilation. See [Apache httpd Cross-Compilation Issues](#apache-httpd-cross-compilation-issues) section below.

```bash
cd ~/android-cross-builds
tar -xzf httpd-2.4.66.tar.gz
cd httpd-2.4.66

# Set environment
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export DEPS=$HOME/android-cross-builds/deps/arm64-v8a

# Configure (note: CC must be full path, not variable reference)
CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang \
AR=$TOOLCHAIN/bin/llvm-ar \
RANLIB=$TOOLCHAIN/bin/llvm-ranlib \
./configure \
    --host=aarch64-linux-android \
    --prefix=$HOME/android-cross-builds/apache \
    --with-apr=$DEPS \
    --with-apr-util=$DEPS \
    --with-ssl=$DEPS \
    --with-nghttp2=$DEPS \
    --with-pcre=$DEPS/bin/pcre2-config \
    --enable-ssl \
    --enable-http2 \
    --disable-ext-filter \
    --disable-pie \
    ap_cv_void_ptr_lt_long=no

# ============================================
# GENERATE test_char.h FOR HOST BEFORE BUILDING
# See "Apache httpd Cross-Compilation Issues" section below
# ============================================

# Build server directory first with host-generated test_char.h
cd server
gcc -DCROSS_COMPILE -I../include -I../os/unix gen_test_char.c -o gen_test_char_host
./gen_test_char_host > test_char.h
cp gen_test_char_host gen_test_char  # Replace ARM64 binary with host binary
cd ..

# Build
make -j$(nproc)
```

### Step 8: Cross-Compile json-c

json-c is required by Axis2/C for JSON-RPC support. It uses CMake with the Android toolchain file:

```bash
cd ~/android-cross-builds
tar -xzf json-c-0.18-20240915.tar.gz
cd json-c-json-c-0.18-20240915

# Create build directory
mkdir build && cd build

# Configure with Android toolchain
cmake \
    -DCMAKE_TOOLCHAIN_FILE=$HOME/Android/Sdk/ndk/28.0.12916984/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-21 \
    -DCMAKE_INSTALL_PREFIX=$HOME/android-cross-builds/deps/arm64-v8a \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_STATIC_LIBS=ON \
    -DBUILD_APPS=OFF \
    ..

# Build and install
make -j$(nproc)
make install

# Verify
ls -la ~/android-cross-builds/deps/arm64-v8a/lib/libjson-c.a
```

### Step 9: Cross-Compile Axis2/C (with Android patches)

Axis2/C requires several patches for Android compatibility. See [Axis2/C Android Patches](#axis2c-android-patches) section below.

```bash
cd ~/repos/axis-axis2-c-core

# ============================================
# APPLY ANDROID PATCHES BEFORE CONFIGURING
# See "Axis2/C Android Patches" section below
# ============================================

# Run autogen.sh to regenerate configure scripts after patching
./autogen.sh

# Set NDK environment
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export DEPS=$HOME/android-cross-builds/deps/arm64-v8a

# Configure for Android ARM64
# IMPORTANT: -fPIC is REQUIRED for linking into Android shared libraries
CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang \
AR=$TOOLCHAIN/bin/llvm-ar \
RANLIB=$TOOLCHAIN/bin/llvm-ranlib \
CFLAGS="-fPIC -std=gnu99 -I$DEPS/include -I$DEPS/include/apr-1" \
LDFLAGS="-L$DEPS/lib" \
LIBS="-lapr-1 -laprutil-1 -lexpat -lssl -lcrypto -lnghttp2 -ljson-c" \
./configure \
    --host=aarch64-linux-android \
    --prefix=$DEPS \
    --with-apache2=$HOME/android-cross-builds/httpd-2.4.66 \
    --with-apr=$DEPS \
    --enable-static \
    --disable-shared \
    --enable-http2 \
    --enable-json \
    --with-openssl=$DEPS

# Build (core libraries only - codegen tools require libxml2 which is optional)
make -j$(nproc)

# Install to deps directory
make install
```

> **Note:** The build may show an error at the very end in `tools/codegen/` about missing libxml2. This only affects optional code generation tools; all core Axis2/C libraries (~9MB) build successfully.

> **CRITICAL:** All libraries (APR, APR-util, Axis2/C) MUST be built with `-fPIC` flag. Without PIC (Position Independent Code), static libraries cannot be linked into Android shared libraries (.so), causing linker errors like "relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be used".

### Step 10: Install Axis2/C Libraries to deps Directory

After building Axis2/C, install the static libraries and headers to the cross-compilation deps directory:

```bash
cd ~/repos/axis-axis2-c-core
DEPS=$HOME/android-cross-builds/deps/arm64-v8a

# Create Axis2/C include directory
mkdir -p $DEPS/include/axis2-2.0.0/platforms/unix

# Copy all static libraries from build directories
for lib in $(find . -name "*.a" -path "*/.libs/*"); do
    cp -v "$lib" $DEPS/lib/
done

# Copy header files from all modules
cp include/*.h $DEPS/include/axis2-2.0.0/
cp util/include/*.h $DEPS/include/axis2-2.0.0/
cp axiom/include/*.h $DEPS/include/axis2-2.0.0/
cp guththila/include/*.h $DEPS/include/axis2-2.0.0/
cp neethi/include/*.h $DEPS/include/axis2-2.0.0/
cp util/include/platforms/unix/*.h $DEPS/include/axis2-2.0.0/platforms/unix/

# Verify installation
echo "Libraries installed: $(ls $DEPS/lib/libaxis2*.a $DEPS/lib/libaxutil.a $DEPS/lib/libguththila.a $DEPS/lib/libneethi.a $DEPS/lib/libmod_axis2.a | wc -l)"
echo "Headers installed: $(find $DEPS/include/axis2-2.0.0 -name '*.h' | wc -l)"
```

**Expected output:**
```
Libraries installed: 25
Headers installed: 259
```

**Output Libraries (~9MB total):**

| Library | Purpose | Size |
|---------|---------|------|
| libaxis2_engine.a | Main Axis2/C engine | ~3.0 MB |
| libaxis2_axiom.a | AXIOM XML object model | ~450 KB |
| libaxis2_parser.a | Guththila XML parser | ~180 KB |
| libaxis2_soap.a | SOAP support | ~180 KB |
| libaxis2_http_common.a | HTTP transport | ~200 KB |
| libaxis2_h2_transport.a | HTTP/2 transport | ~100 KB |
| libmod_axis2.a | Apache httpd module | ~600 KB |
| libaxutil.a | Utilities | ~320 KB |
| libneethi.a | WS-Policy support | ~300 KB |
| libguththila.a | Fast XML parser | ~80 KB |

---

## APR Android Patches

APR requires two patches for Android compatibility:

### Patch 1: union semun Redefinition

**File:** `include/arch/unix/apr_arch_proc_mutex.h`

**Problem:** Android NDK already defines `union semun` in `<sys/sem.h>`, causing a redefinition error.

**Fix:** Add `!defined(__ANDROID__)` guard:

```c
// Before (line ~86):
#if !APR_HAVE_UNION_SEMUN && defined(APR_HAS_SYSVSEM_SERIALIZE)
union semun {
    int val;
    struct semid_ds *buf;
    unsigned short *array;
};
#endif

// After:
#if !APR_HAVE_UNION_SEMUN && defined(APR_HAS_SYSVSEM_SERIALIZE) && !defined(__ANDROID__)
union semun {
    int val;
    struct semid_ds *buf;
    unsigned short *array;
};
#endif
```

### Patch 2: strerror_r Return Type

**File:** `misc/unix/errorcodes.c`

**Problem:** Android uses XSI-compliant `strerror_r` which returns `int`, not the GNU version which returns `char*`. The configure script doesn't detect this correctly for Android.

**Fix:** Add `__ANDROID__` to the XSI-compliant code path:

```c
// Before (line ~355):
#if defined(HAVE_STRERROR_R) && defined(STRERROR_R_RC_INT) && !defined(BEOS)
/* AIX and Tru64 style */

// After:
#if defined(HAVE_STRERROR_R) && (defined(STRERROR_R_RC_INT) || defined(__ANDROID__)) && !defined(BEOS)
/* AIX, Tru64, and Android style (XSI-compliant, returns int) */
```

### Applying the Patches

Create a patch file `apr-android.patch`:

```diff
--- a/include/arch/unix/apr_arch_proc_mutex.h
+++ b/include/arch/unix/apr_arch_proc_mutex.h
@@ -83,7 +83,7 @@
 #include <sys/sem.h>
 #endif

-#if !APR_HAVE_UNION_SEMUN && defined(APR_HAS_SYSVSEM_SERIALIZE)
+#if !APR_HAVE_UNION_SEMUN && defined(APR_HAS_SYSVSEM_SERIALIZE) && !defined(__ANDROID__)
 union semun {
     int val;
     struct semid_ds *buf;
--- a/misc/unix/errorcodes.c
+++ b/misc/unix/errorcodes.c
@@ -352,7 +352,7 @@
 #else
     char buf[MAX_STRING_LEN];
     char *msg;
-#if defined(HAVE_STRERROR_R) && defined(STRERROR_R_RC_INT) && !defined(BEOS)
+#if defined(HAVE_STRERROR_R) && (defined(STRERROR_R_RC_INT) || defined(__ANDROID__)) && !defined(BEOS)
     /* AIX and Tru64 style */
     if (strerror_r(statcode, buf, sizeof(buf)) < 0) {
         return stuffbuffer(buf, sizeof(buf), "APR does not understand this error code");
```

Apply with:

```bash
cd ~/android-cross-builds/apr-1.7.6
patch -p1 < /path/to/apr-android.patch
```

Or apply manually by editing the two files as shown above.

---

## Apache httpd Cross-Compilation Issues

### Issue 1: gen_test_char Binary

**Problem:** Apache httpd build generates `gen_test_char` which creates `test_char.h`. When cross-compiling, this binary is built for ARM64 and cannot run on the x86_64 build host.

**Solution:** Build `gen_test_char` for the host using the `-DCROSS_COMPILE` flag (Apache httpd supports this):

```bash
cd ~/android-cross-builds/httpd-2.4.66/server

# Build for host (x86_64)
gcc -DCROSS_COMPILE -I../include -I../os/unix gen_test_char.c -o gen_test_char_host

# Generate header
./gen_test_char_host > test_char.h

# Replace ARM64 binary so make doesn't regenerate it
cp gen_test_char_host gen_test_char
```

### Issue 2: mod_ext_filter Uses Unsupported APR Function

**Problem:** `mod_ext_filter` uses `apr_procattr_limit_set()` which is not available on Android.

**Solution:** Disable the module during configure:

```bash
./configure ... --disable-ext-filter
```

### Issue 3: Support Tools Require crypt()

**Problem:** Apache httpd support tools (htpasswd, htdigest, htdbm) use the Unix `crypt()` function which is not available on Android.

**Solution:** These tools are not needed for the Kanaha Camera App runtime. The core httpd server and modules build successfully without them.

### Issue 4: apr-1-config Has Embedded Paths

**Problem:** The `apr-1-config` script created during APR installation contains the cross-compiler path. When Apache httpd's configure runs, it extracts CC from this script but may truncate the path.

**Solution:** Always set CC explicitly with full path when running Apache httpd configure:

```bash
CC=/full/path/to/aarch64-linux-android21-clang ./configure ...
```

---

## Axis2/C Android Patches

Axis2/C requires several patches for Android NDK compatibility:

### Patch 1: sys/timeb.h Not Available

**Files:**
- `util/include/platforms/unix/axutil_unix.h`
- `util/include/platforms/unix/axutil_date_time_util_unix.h`
- `util/src/platforms/unix/date_time_util_unix.c`

**Problem:** Android NDK doesn't include `<sys/timeb.h>` or the `ftime()` function. This header is deprecated in POSIX and Android uses `clock_gettime()` instead.

**Fix in `axutil_unix.h`:** Add Android guard around the include and provide replacement:

```c
// Add after other includes:
#include <sys/time.h>
#ifndef __ANDROID__
#include <sys/timeb.h>
#endif

// Add Android replacement for timeb/ftime:
#ifdef __ANDROID__
/* Android doesn't have sys/timeb.h or ftime(), use clock_gettime() instead */
struct axis2_android_timeb {
    time_t time;
    unsigned short millitm;
    short timezone;
    short dstflag;
};
static inline void axis2_android_ftime(struct axis2_android_timeb *tb) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    tb->time = ts.tv_sec;
    tb->millitm = ts.tv_nsec / 1000000;
    tb->timezone = 0;
    tb->dstflag = 0;
}
#define AXIS2_PLATFORM_GET_TIME_IN_MILLIS axis2_android_ftime
#define AXIS2_PLATFORM_TIMEB axis2_android_timeb
#else
#define AXIS2_PLATFORM_GET_TIME_IN_MILLIS ftime
#define AXIS2_PLATFORM_TIMEB timeb
#endif
```

**Fix in `axutil_date_time_util_unix.h`:** Add guard:

```c
#ifndef __ANDROID__
#include <sys/timeb.h>
#endif
```

**Fix in `date_time_util_unix.c`:** Use `clock_gettime()` on Android:

```c
AXIS2_EXTERN int AXIS2_CALL
axis2_platform_get_milliseconds()
{
#ifdef __ANDROID__
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int)(ts.tv_nsec / 1000000);
#else
    struct timeb t_current;
    ftime(&t_current);
    return t_current.millitm;
#endif
}
```

### Patch 2: -ansi Flag Conflicts with NDK Headers

**Files:** All `configure.ac` files in the project:
- `configure.ac`
- `util/configure.ac`
- `axiom/configure.ac`
- `neethi/configure.ac`
- `guththila/configure.ac`
- `samples/configure.ac`
- `tools/tcpmon/configure.ac`
- `tools/md5/configure.ac`

**Problem:** The `-ansi` flag enforces C89/C90 which doesn't have the `inline` keyword. Android NDK headers (especially `linux/swab.h`) use `inline`, causing compilation errors.

**Fix:** Remove `-ansi` from CFLAGS in each configure.ac:

```autoconf
# Before:
if test "$GCC" = "yes"; then
    CFLAGS="$CFLAGS -ansi -Wall -Wno-implicit-function-declaration"
fi

# After:
if test "$GCC" = "yes"; then
    dnl Note: -ansi removed for Android NDK compatibility (inline keyword)
    CFLAGS="$CFLAGS -Wall -Wno-implicit-function-declaration"
fi
```

### Patch 3: pthread Linking

**File:** `util/configure.ac` and `util/src/Makefile.am`

**Problem:** Android includes pthread in libc (Bionic), so `-lpthread` fails with "unable to find library".

**Fix in `util/configure.ac`:** Make pthread check conditional:

```autoconf
dnl Skip pthread library check on Android (pthread is in libc)
case "$host" in
  *android*)
    dnl Android has pthread built into libc, no separate -lpthread needed
    PTHREADLIBS=""
    ;;
  *)
    AC_CHECK_LIB(pthread, pthread_key_create)
    PTHREADLIBS="-lpthread"
    ;;
esac
AC_SUBST(PTHREADLIBS)
```

**Fix in `util/src/Makefile.am`:** Use the variable:

```makefile
# Before:
libaxutil_la_LIBADD = $(top_builddir)/src/platforms/unix/libaxis2_unix.la \
                      -lpthread \
                      @ZLIBLIBS@

# After:
libaxutil_la_LIBADD = $(top_builddir)/src/platforms/unix/libaxis2_unix.la \
                      @PTHREADLIBS@ \
                      @ZLIBLIBS@
```

### Patch 4: backtrace() Not Available

**File:** `src/core/transport/http/server/apache2/mod_axis2.c`

**Problem:** The `backtrace()` and `backtrace_symbols()` functions are glibc-specific and not available in Android's Bionic libc.

**Fix:** Add Android guard around backtrace code:

```c
static void axis2_segfault_handler(int sig)
{
#ifndef __ANDROID__
    /* backtrace functions are not available on Android */
    void *array[10];
    size_t size;
    char **strings;
    size_t i;

    ap_log_error(APLOG_MARK, APLOG_CRIT, APR_SUCCESS, NULL,
        "[Axis2] CRASH DETECTED: Signal %d received", sig);

    size = backtrace(array, 10);
    strings = backtrace_symbols(array, size);
    // ... log backtrace ...
    free(strings);
#else
    /* Android: Simple crash logging without backtrace */
    ap_log_error(APLOG_MARK, APLOG_CRIT, APR_SUCCESS, NULL,
        "[Axis2] CRASH DETECTED: Signal %d received", sig);
#endif
    _exit(1);
}
```

### Applying Axis2/C Patches

After applying all patches, regenerate the configure scripts:

```bash
cd ~/repos/axis-axis2-c-core
./autogen.sh
```

---

## Verification

After completing all builds and installations, verify the output:

```bash
DEPS=$HOME/android-cross-builds/deps/arm64-v8a

echo "=== Cross-compiled dependency libraries ==="
ls $DEPS/lib/*.a | wc -l
echo "total static libraries in deps/arm64-v8a/lib/"

echo ""
echo "=== Core dependency library sizes ==="
du -h $DEPS/lib/libapr-1.a $DEPS/lib/libaprutil-1.a $DEPS/lib/libssl.a $DEPS/lib/libcrypto.a $DEPS/lib/libnghttp2.a $DEPS/lib/libjson-c.a

echo ""
echo "=== Axis2/C library sizes ==="
du -h $DEPS/lib/libaxis2_engine.a $DEPS/lib/libaxutil.a $DEPS/lib/libaxis2_http_common.a $DEPS/lib/libmod_axis2.a

echo ""
echo "=== Axis2/C headers ==="
find $DEPS/include/axis2-2.0.0 -name '*.h' | wc -l
echo "header files installed"

echo ""
echo "=== Apache httpd server library ==="
ls -la ~/android-cross-builds/httpd-2.4.66/server/.libs/libmain.a

echo ""
echo "=== Verify ARM64 architecture ==="
file $DEPS/lib/libapr-1.a
```

Expected output:
```
=== Cross-compiled dependency libraries ===
37
total static libraries in deps/arm64-v8a/lib/

=== Core dependency library sizes ===
1.6M    libapr-1.a
1.1M    libaprutil-1.a
1.8M    libssl.a
9.9M    libcrypto.a
1.3M    libnghttp2.a
380K    libjson-c.a

=== Axis2/C library sizes ===
3.0M    libaxis2_engine.a
324K    libaxutil.a
880K    libaxis2_http_common.a
572K    libmod_axis2.a

=== Axis2/C headers ===
259
header files installed

=== Apache httpd server library ===
libmain.a        (~3.4 MB)

=== Verify ARM64 architecture ===
libapr-1.a: current ar archive
```

### Complete Library Inventory

After all steps are complete, the deps directory should contain:

**Core Dependencies (8 libraries):**
| Library | Size | Purpose |
|---------|------|---------|
| libcrypto.a | 9.9 MB | OpenSSL cryptography |
| libssl.a | 1.8 MB | OpenSSL TLS/SSL |
| libapr-1.a | 1.6 MB | Apache Portable Runtime |
| libaprutil-1.a | 1.1 MB | APR utilities |
| libnghttp2.a | 1.3 MB | HTTP/2 protocol |
| libexpat.a | 0.8 MB | XML parsing |
| libpcre2-8.a | 0.4 MB | Regular expressions |
| libjson-c.a | 0.4 MB | JSON parsing |

**Axis2/C Libraries (28 libraries, ~9 MB total):**
| Library | Size | Purpose |
|---------|------|---------|
| libaxis2_engine.a | 3.0 MB | Main Axis2/C engine |
| libaxis2_http_common.a | 880 KB | HTTP transport |
| libaxis2_deployment.a | 684 KB | Service deployment |
| libmod_axis2.a | 572 KB | Apache httpd module |
| libaxis2_description.a | 564 KB | Service descriptions |
| libaxis2_axiom.a | 448 KB | XML object model |
| libneethi.a | 396 KB | WS-Policy support |
| libaxis2_clientapi.a | 368 KB | Client API |
| libaxutil.a | 324 KB | Utilities |
| libaxis2_context.a | 308 KB | Execution context |
| libaxis2_http_util.a | 268 KB | HTTP utilities |
| libguththila.a | 268 KB | XML parser |
| libaxis2_soap.a | 176 KB | SOAP support |
| + 15 more | ~2 MB | Supporting libraries |

---

## Troubleshooting

### "cannot execute binary file: Exec format error"

You're trying to run an ARM64 binary on x86_64. This typically happens with `gen_test_char`. See [Issue 1](#issue-1-gen_test_char-binary) above.

### "union semun redefined"

APR needs the Android patch. See [Patch 1](#patch-1-union-semun-redefinition) above.

### "incompatible integer to pointer conversion" with strerror_r

APR needs the Android patch. See [Patch 2](#patch-2-strerror_r-return-type) above.

### "apr_procattr_limit_set undeclared"

Disable mod_ext_filter: `./configure ... --disable-ext-filter`

### "call to undeclared function 'crypt'"

This only affects support tools (htpasswd, etc.) which are not needed for Android runtime.

### Configure fails with "C compiler cannot create executables"

The compiler path is being truncated. Use full explicit paths:

```bash
CC=/home/user/Android/Sdk/ndk/28.0.12916984/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang \
./configure ...
```

### OpenSSL configure fails with "ANDROID_NDK_ROOT is not defined"

OpenSSL uses `ANDROID_NDK_ROOT`, not `ANDROID_NDK_HOME`:

```bash
export ANDROID_NDK_ROOT=$HOME/Android/Sdk/ndk/28.0.12916984
```

### Axis2/C: "sys/timeb.h file not found"

Android NDK doesn't have this deprecated header. Apply the sys/timeb.h patch. See [Axis2/C Patch 1](#patch-1-systimebh-not-available).

### Axis2/C: "unknown type name 'inline'" in swab.h

The `-ansi` flag conflicts with NDK headers. Remove `-ansi` from all configure.ac files. See [Axis2/C Patch 2](#patch-2--ansi-flag-conflicts-with-ndk-headers).

### Axis2/C: "unable to find library -lpthread"

Android's pthread is built into libc. Apply the pthread patch. See [Axis2/C Patch 3](#patch-3-pthread-linking).

### Axis2/C: "incompatible integer to pointer conversion" with backtrace_symbols

Android doesn't have backtrace functions. Apply the backtrace patch. See [Axis2/C Patch 4](#patch-4-backtrace-not-available).

### Axis2/C: "json-c/json.h file not found"

json-c must be cross-compiled first. See [Step 8: Cross-Compile json-c](#step-8-cross-compile-json-c).

---

## Quick Reference: Environment Variables

```bash
# Standard NDK setup
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.12916984
export TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export CXX=$TOOLCHAIN/bin/aarch64-linux-android21-clang++
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export STRIP=$TOOLCHAIN/bin/llvm-strip

# For OpenSSL specifically
export ANDROID_NDK_ROOT=$HOME/Android/Sdk/ndk/28.0.12916984

# Output directories
export DEPS=$HOME/android-cross-builds/deps/arm64-v8a
```

---

## Quick Reference: Configure Options

| Library | Key Configure Flags |
|---------|-------------------|
| OpenSSL | `android-arm64 no-shared no-tests` |
| nghttp2 | `--disable-shared --enable-lib-only` |
| expat | `--disable-shared --without-xmlwf` |
| PCRE2 | `--disable-shared` |
| APR | `--disable-shared` + cache variables (see Step 5) |
| APR-util | `--disable-shared --with-apr=... --with-expat=...` |
| Apache httpd | `--disable-shared --disable-ext-filter --disable-pie` |
| json-c | CMake: `-DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON` |
| Axis2/C | `--disable-shared --enable-http2 --enable-json` + patches |
| Axis2/C Install | Copy `.libs/*.a` to deps + headers to `axis2-2.0.0/` |

---

## Step 11: Final Linking with Static Service Registry

The final httpd binary requires special linking to include all Axis2/C components, including the Android static service registry. **Libtool strips `--whole-archive` flags**, so direct linking is required.

### Why Direct Linking is Required

When linking static archives (.a files), the linker only includes object files that resolve undefined symbols. This causes problems for:

1. **Static service registry functions** - Not called from Apache httpd, only from Axis2/C internally
2. **Message receiver registrations** - Referenced at runtime, not link time
3. **Service implementations** - Invoked dynamically based on services.xml configuration

The `--whole-archive` flag forces inclusion of all objects, but libtool strips it. The solution is to bypass libtool entirely.

### Final Link Script

Create `~/android-cross-builds/link-httpd-axis2.sh`:

```bash
#!/bin/bash
set -e

cd /home/robert/android-cross-builds/httpd-2.4.66
DEPS=/home/robert/android-cross-builds/deps/arm64-v8a
CC=/home/robert/Android/Sdk/ndk/28.0.12916984/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang

# Direct link without libtool to preserve --whole-archive flags
# Note: libaxis2_engine.a contains updated receivers with Android static service support
$CC -fPIC -o httpd modules.o buildmark.o \
  -Wl,--export-dynamic \
  -L$DEPS/lib \
  server/.libs/libmain.a \
  modules/aaa/.libs/libmod_authz_core.a \
  modules/core/.libs/libmod_so.a \
  modules/http/.libs/libmod_http.a \
  modules/http/.libs/libmod_mime.a \
  modules/loggers/.libs/libmod_log_config.a \
  modules/metadata/.libs/libmod_headers.a \
  modules/ssl/.libs/libmod_ssl.a \
  modules/http2/.libs/libmod_http2.a \
  -lssl -ldl -lcrypto \
  modules/arch/unix/.libs/libmod_unixd.a \
  modules/mappers/.libs/libmod_dir.a \
  modules/mappers/.libs/libmod_rewrite.a \
  server/mpm/worker/.libs/libworker.a \
  os/unix/.libs/libos.a \
  $DEPS/lib/libmod_axis2.a \
  -Wl,--whole-archive $DEPS/lib/libaxis2_engine.a -Wl,--no-whole-archive \
  $DEPS/lib/libaxis2_deployment.a \
  $DEPS/lib/libaxis2_description.a \
  $DEPS/lib/libaxis2_context.a \
  $DEPS/lib/libaxis2_phaseresolver.a \
  $DEPS/lib/libaxis2_core_utils.a \
  $DEPS/lib/libaxis2_http_common.a \
  $DEPS/lib/libaxis2_http_util.a \
  $DEPS/lib/libaxis2_h2_transport.a \
  $DEPS/lib/libaxis2_h2_util.a \
  $DEPS/lib/libaxis2_h2_sender.a \
  $DEPS/lib/libaxis2_axiom.a \
  $DEPS/lib/libaxis2_axiom_util.a \
  $DEPS/lib/libaxis2_parser.a \
  $DEPS/lib/libaxis2_soap.a \
  $DEPS/lib/libaxis2_addr.a \
  $DEPS/lib/libaxis2_xpath.a \
  $DEPS/lib/libaxis2_unix.a \
  $DEPS/lib/libaxis2_attachments.a \
  $DEPS/lib/libaxis2_clientapi.a \
  $DEPS/lib/libaxutil.a \
  $DEPS/lib/libneethi.a \
  $DEPS/lib/libguththila.a \
  $DEPS/lib/libpcre2-8.a \
  -ljson-c \
  $DEPS/lib/libnghttp2.a \
  $DEPS/lib/libaprutil-1.a \
  $DEPS/lib/libexpat.a \
  $DEPS/lib/libapr-1.a \
  -lm -llog -pthread

echo "=== Link complete ==="
file httpd
```

### Key Linking Points

| Flag | Purpose |
|------|---------|
| `-Wl,--whole-archive` | Force inclusion of ALL objects from the archive |
| `-Wl,--no-whole-archive` | Resume normal archive linking |
| `-Wl,--export-dynamic` | Export symbols for dynamic lookups |
| `-fPIC` | Position-independent code for Android |
| `-pthread` | Android pthread (built into libc) |

### Updating libaxis2_engine.a with Service Implementations

When adding new statically-linked services, update the archive:

```bash
DEPS=/home/robert/android-cross-builds/deps/arm64-v8a/lib
SRC=/home/robert/repos/axis-axis2-c-core/src/core/receivers

# Remove old receiver objects
ar d $DEPS/libaxis2_engine.a \
    libaxis2_receivers_la-axis2_json_rpc_msg_recv.o \
    libaxis2_receivers_la-msg_recv.o \
    libaxis2_receivers_la-svr_callback.o

# Add updated receiver objects (including service implementations)
ar r $DEPS/libaxis2_engine.a \
    $SRC/libaxis2_receivers_la-axis2_json_rpc_msg_recv.o \
    $SRC/libaxis2_receivers_la-msg_recv.o \
    $SRC/libaxis2_receivers_la-svr_callback.o \
    $SRC/libaxis2_receivers_la-axis2_camera_control_service.o

# Rebuild archive index
ranlib $DEPS/libaxis2_engine.a
```

### Verifying Symbol Inclusion

After linking, verify critical symbols are present:

```bash
# Check for Android static service registry
nm httpd | grep -E "android_static|try_android|camera_control"

# Expected output (T = global text symbol):
# 00000000007b4998 T android_static_service_lookup
# 00000000007b3ae0 T camera_control_service_invoke_json
# 00000000007b49fc T try_android_static_service
```

### Strip and Deploy

```bash
# Strip debug symbols for production (reduces ~13MB to ~10MB)
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
    httpd -o /path/to/kanaha-camera-app/app/src/main/jniLibs/arm64-v8a/libhttpd.so

# Rebuild APK
cd /path/to/kanaha-camera-app
./gradlew assembleDebug

# Deploy
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Android Static Service Registry

### The Problem: No dlopen() on Android

Traditional Axis2/C services are loaded dynamically via `dlopen()`:

```c
// Traditional approach - DOES NOT WORK on Android with static linking
void *lib = dlopen("libcamera_control_service.so", RTLD_NOW);
invoke_func = dlsym(lib, "camera_control_service_invoke_json");
```

With static linking, there are no `.so` files to load. Services must be registered at compile time.

### Solution: Static Service Registry

Axis2/C includes an Android-specific static service registry that maps service names to function pointers:

**Registry in `axis2_json_rpc_msg_recv.c`:**
```c
#ifdef __ANDROID__
/* Forward declaration of statically linked service */
extern json_object* camera_control_service_invoke_json(
    const axutil_env_t *env, json_object *json_request);

typedef json_object* (*json_service_invoke_func_t)(
    const axutil_env_t *env, json_object *json_request);

/* Non-static to ensure linker includes this function from archives */
json_service_invoke_func_t
android_static_service_lookup(const char *service_name)
{
    if (strcmp(service_name, "CameraControlService") == 0) {
        return camera_control_service_invoke_json;
    }
    /* Add more services here */
    return NULL;
}

/* Non-static to ensure linker includes this function from archives */
axis2_bool_t
try_android_static_service(const axutil_env_t *env,
                           const char *service_name,
                           const char *json_request_str,
                           axis2_char_t **json_response_out)
{
    json_service_invoke_func_t service_invoke = android_static_service_lookup(service_name);
    if (!service_invoke) return AXIS2_FALSE;

    json_object *request = json_tokener_parse(json_request_str);
    json_object *response = service_invoke(env, request);
    *json_response_out = axutil_strdup(env, json_object_to_json_string(response));

    json_object_put(request);
    json_object_put(response);
    return AXIS2_TRUE;
}
#endif
```

### Why Functions Must Be Non-Static

**Critical:** Registry functions MUST be non-static for archive linking:

- **Static functions** (`static` keyword) have internal linkage
- When linking from archives, the linker only includes objects that resolve undefined symbols
- Static functions not called from exported code are optimized away
- **Non-static functions** are exported symbols that the linker preserves

```c
/* WRONG - will be optimized away during linking */
static json_service_invoke_func_t android_static_service_lookup(...) { }

/* CORRECT - preserved as global symbol */
json_service_invoke_func_t android_static_service_lookup(...) { }
```

### Adding New Services

To add a new statically-linked service:

1. **Create service implementation:**
   ```c
   // axis2_my_service.c
   json_object* my_service_invoke_json(
       const axutil_env_t *env, json_object *json_request)
   {
       // Handle JSON-RPC methods
       return json_response;
   }
   ```

2. **Add to Makefile.am:**
   ```makefile
   libaxis2_receivers_la_SOURCES = msg_recv.c \
                                   axis2_json_rpc_msg_recv.c \
                                   axis2_camera_control_service.c \
                                   axis2_my_service.c \
                                   svr_callback.c
   ```

3. **Register in static registry:**
   ```c
   // In axis2_json_rpc_msg_recv.c
   extern json_object* my_service_invoke_json(...);

   json_service_invoke_func_t android_static_service_lookup(const char *service_name)
   {
       if (strcmp(service_name, "CameraControlService") == 0)
           return camera_control_service_invoke_json;
       if (strcmp(service_name, "MyService") == 0)
           return my_service_invoke_json;
       return NULL;
   }
   ```

4. **Rebuild and update archive:**
   ```bash
   cd ~/repos/axis-axis2-c-core/src/core/receivers
   make clean && make
   # Update libaxis2_engine.a with new objects
   # Re-link httpd
   ```

---

## Version History

| Date | Changes |
|------|---------|
| 2026-01-03 | Added Step 11: Final linking with static service registry, Android static service registry documentation, direct linking bypassing libtool |
| 2025-12-31 | **CRITICAL FIX**: Added -fPIC requirement for APR, APR-util, and Axis2/C. Static libraries MUST be compiled with -fPIC to link into Android shared libraries. Updated build instructions for Steps 5, 6, 9 |
| 2025-12-31 | Added Step 10: Install Axis2/C libraries to deps directory, updated verification section with complete library inventory |
| 2025-12-31 | Added json-c and Axis2/C cross-compilation (Steps 8-9), Axis2/C Android patches section |
| 2025-12-31 | Initial document with all lessons learned from cross-compilation |

## Related Documentation

- [MULTI_CAMERA_DEPLOYMENT_SYSTEM.md](MULTI_CAMERA_DEPLOYMENT_SYSTEM.md) - Main deployment guide
- [Apache httpd Documentation](https://httpd.apache.org/docs/2.4/)
- [Android NDK Documentation](https://developer.android.com/ndk/guides)
- [Axis2/C Documentation](https://axis.apache.org/axis2/c/core/)
