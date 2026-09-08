#!/bin/bash
# kanaha-camera: Cross-compile the Axis2/C CameraControlService for Android arm64-v8a
#
# Prerequisites:
#   - NDK at ~/Android/Sdk/ndk/28.0.12916984
#   - Cross-compiled deps at ~/android-cross-builds/deps/arm64-v8a/
#   - Axis2/C source at ~/repos/axis-axis2-c-core/
#   - Apache httpd build at ~/android-cross-builds/httpd-2.4.66/
#
# Output: kanaha-camera-app/app/src/main/jniLibs/arm64-v8a/libhttpd.so
#
# Usage: cd $HOME/repos/kanaha && bash build-android.sh
#
# Lived at ~/android-cross-builds/link-httpd-axis2.sh until September 2026.
# Outside the repo it was unversioned, which is how an Axis2/C service registry
# entry once survived only in a built binary and vanished on the next clean
# rebuild. Keep it here.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NDK=$HOME/Android/Sdk/ndk/28.0.12916984
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang
AR=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar
STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip

DEPS=$HOME/android-cross-builds/deps/arm64-v8a
HTTPD_DIR=$HOME/android-cross-builds/httpd-2.4.66
AXIS2_SRC=$HOME/repos/axis-axis2-c-core

KANAHA_APP=$SCRIPT_DIR/kanaha-camera-app/app/src/main/cpp
AXIS2_INCLUDE=$AXIS2_SRC/include
UTIL_INCLUDE=$AXIS2_SRC/util/include
OUTPUT_DIR=$SCRIPT_DIR/kanaha-camera-app/app/src/main/jniLibs/arm64-v8a

cd "$HTTPD_DIR"

echo "=== Building static service adapter and implementation ==="

# Compile the Axis2/C static service adapter (provides camera_control_service_invoke_json)
# -fsigned-char: axis2_char_t is plain char, signed on x86 and unsigned on
# arm64. The Axis2/C libraries are built with -fsigned-char (see its
# configure.ac), so these service sources must match or the same char
# behaves differently either side of the call.
$CC -fPIC -fsigned-char -D__ANDROID__ -c \
    -I$DEPS/include \
    -I$DEPS/include/apr-1 \
    -I$AXIS2_INCLUDE \
    -I$UTIL_INCLUDE \
    -I/usr/include/json-c \
    $KANAHA_APP/axis2c/axis2_static_service_adapter.c \
    -o axis2_static_service_adapter.o

# Compile the Kanaha camera service implementation (provides camera_control_service_invoke_json_impl)
$CC -fPIC -fsigned-char -D__ANDROID__ -c \
    -I$DEPS/include \
    $KANAHA_APP/axis2c/camera_control_service.c \
    -o camera_control_service.o

# Create static library for services
$AR rcs libkanaha_services.a axis2_static_service_adapter.o camera_control_service.o

echo "=== Static services built: libkanaha_services.a ==="

# Direct link without libtool
# Note: Services are provided by libkanaha_services.a which overrides weak symbols
# Android 15 (API 35) requires 16KB page aligned ELF segments:
# -z,max-page-size=16384: Set page size to 16KB
# -z,separate-loadable-segments: Force each LOAD segment to start at page boundary
$CC -fPIC -o httpd modules.o buildmark.o \
  -Wl,--export-dynamic \
  -Wl,-z,max-page-size=16384 \
  -Wl,-z,separate-loadable-segments \
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
  -Wl,--whole-archive libkanaha_services.a -Wl,--no-whole-archive \
  $DEPS/lib/libaxis2_deployment.a \
  $DEPS/lib/libaxis2_description.a \
  $DEPS/lib/libaxis2_context.a \
  $DEPS/lib/libaxis2_phaseresolver.a \
  $DEPS/lib/libaxis2_core_utils.a \
  $DEPS/lib/libaxis2_http_common.a \
  $DEPS/lib/libaxis2_http_util.a \
  $DEPS/lib/libaxis2_h2_transport.a \
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

# Strip and install into jniLibs, where ApacheService.java launches it from
# (nativeLibDir + "/libhttpd.so"). Without this the app keeps running whatever
# .so was there before and a successful link looks like a successful deploy --
# this binary sat at March while the script was being re-run.
"$STRIP" httpd
mkdir -p "$OUTPUT_DIR"
cp httpd "$OUTPUT_DIR/libhttpd.so"
echo "=== Installed: $OUTPUT_DIR/libhttpd.so ==="
ls -la "$OUTPUT_DIR/libhttpd.so"
