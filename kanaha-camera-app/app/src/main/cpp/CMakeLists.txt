# CMakeLists.txt for Kanaha Camera Control System
# Apache httpd + Axis2/C + OpenCamera Integration
#
# ARCHITECTURE: No JNI Bridge - Uses Internal Intent IPC Only
#
# This build system links pre-built cross-compiled Apache httpd and Axis2/C
# libraries that communicate with the Android Java layer via Internal Intent
# broadcasts, following the proven 2019 OpenCameraStudio pattern.

cmake_minimum_required(VERSION 3.18.1)

project("kanaha-camera-control")

# Configure C standard for Apache httpd compatibility
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ============================================================================
# Cross-compiled Dependencies Path
# ============================================================================
# These libraries were cross-compiled for Android ARM64 using the NDK.
# See docs/ANDROID_CROSS_COMPILATION.md for build instructions.

set(CROSS_COMPILED_DEPS "$ENV{HOME}/android-cross-builds/deps/arm64-v8a")

# Only link cross-compiled libs for arm64-v8a (Pixel 9 Pro target)
# armeabi-v7a builds will use stub implementation only
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(USE_CROSS_COMPILED_LIBS ON)
    message(STATUS "Building for arm64-v8a - linking cross-compiled Axis2/C libraries")
else()
    set(USE_CROSS_COMPILED_LIBS OFF)
    message(STATUS "Building for ${ANDROID_ABI} - using stub implementation only")
endif()

# ============================================================================
# Find Android System Libraries
# ============================================================================
find_library(log-lib log)
find_library(android-lib android)
find_library(z-lib z)

# ============================================================================
# Include Directories
# ============================================================================
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if(USE_CROSS_COMPILED_LIBS)
    include_directories(
        ${CROSS_COMPILED_DEPS}/include
        ${CROSS_COMPILED_DEPS}/include/apr-1
        ${CROSS_COMPILED_DEPS}/include/axis2-2.0.0
        ${CROSS_COMPILED_DEPS}/include/axis2-2.0.0/platforms/unix
        ${CROSS_COMPILED_DEPS}/include/json-c
        ${CROSS_COMPILED_DEPS}/include/nghttp2
        ${CROSS_COMPILED_DEPS}/include/openssl
    )
endif()

# ============================================================================
# Preprocessor Definitions
# ============================================================================
add_definitions(
    -DANDROID
    -DAPACHE_HTTPD_ANDROID
)

if(USE_CROSS_COMPILED_LIBS)
    add_definitions(
        -DAXIS2_JSON_ENABLED=1
        -DAXIS2_H2_JSON_ONLY_BUILD=1
        -DWITH_NGHTTP2=1
        -DWITH_OPENSSL=1
        -DUSE_CROSS_COMPILED_LIBS=1
    )
endif()

# ============================================================================
# Import Pre-built Cross-compiled Libraries (arm64-v8a only)
# ============================================================================
# All libraries are built with -fPIC for Android shared library compatibility.
# See docs/ANDROID_CROSS_COMPILATION.md for build instructions.
# ============================================================================
if(USE_CROSS_COMPILED_LIBS)
    set(DEPS_LIB_DIR ${CROSS_COMPILED_DEPS}/lib)

    # APR - Apache Portable Runtime
    add_library(apr-1 STATIC IMPORTED)
    set_target_properties(apr-1 PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libapr-1.a)

    add_library(aprutil-1 STATIC IMPORTED)
    set_target_properties(aprutil-1 PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libaprutil-1.a)

    add_library(expat STATIC IMPORTED)
    set_target_properties(expat PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libexpat.a)

    # OpenSSL - TLS/mTLS support
    add_library(ssl STATIC IMPORTED)
    set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libssl.a)

    add_library(crypto STATIC IMPORTED)
    set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libcrypto.a)

    # json-c - JSON parsing for JSON-RPC
    add_library(json-c STATIC IMPORTED)
    set_target_properties(json-c PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libjson-c.a)

    # nghttp2 - HTTP/2 protocol support
    add_library(nghttp2 STATIC IMPORTED)
    set_target_properties(nghttp2 PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libnghttp2.a)

    # Axis2/C libraries
    add_library(axutil STATIC IMPORTED)
    set_target_properties(axutil PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libaxutil.a)

    add_library(guththila STATIC IMPORTED)
    set_target_properties(guththila PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libguththila.a)

    add_library(axis2_axiom STATIC IMPORTED)
    set_target_properties(axis2_axiom PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libaxis2_axiom.a)

    add_library(axis2_parser STATIC IMPORTED)
    set_target_properties(axis2_parser PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libaxis2_parser.a)

    add_library(axis2_engine STATIC IMPORTED)
    set_target_properties(axis2_engine PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libaxis2_engine.a)

    add_library(axis2_http_common STATIC IMPORTED)
    set_target_properties(axis2_http_common PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libaxis2_http_common.a)

    add_library(axis2_http_util STATIC IMPORTED)
    set_target_properties(axis2_http_util PROPERTIES IMPORTED_LOCATION ${DEPS_LIB_DIR}/libaxis2_http_util.a)
endif()

# ============================================================================
# Source Files
# ============================================================================
set(KANAHA_SOURCES
    apache-httpd/apache_httpd_android.c
    axis2c/camera_control_service.c
)

# MCP source is only compiled for the full cross-compiled build (arm64)
# not the armeabi-v7a stub, because it requires json-c headers
if(USE_CROSS_COMPILED_LIBS)
    list(APPEND KANAHA_SOURCES axis2c/kanaha_mcp.c)
endif()

# ============================================================================
# Create Shared Library
# ============================================================================
add_library(
    kanaha-camera-control
    SHARED
    ${KANAHA_SOURCES}
)

# ============================================================================
# Compiler Flags
# ============================================================================
target_compile_options(kanaha-camera-control PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
    -ffunction-sections
    -fdata-sections
    -Wno-unused-parameter
    -Wno-unused-variable
)

# ============================================================================
# Link Libraries
# ============================================================================
if(USE_CROSS_COMPILED_LIBS)
    # Link all cross-compiled libraries (all built with -fPIC)
    target_link_libraries(
        kanaha-camera-control

        # Android system libraries
        ${log-lib}
        ${android-lib}
        ${z-lib}

        # Axis2/C libraries (order matters for static linking)
        axis2_engine
        axis2_http_common
        axis2_http_util
        axis2_axiom
        axis2_parser
        guththila
        axutil

        # APR - Apache Portable Runtime
        aprutil-1
        apr-1
        expat

        # JSON-RPC parsing
        json-c

        # HTTP/2 protocol
        nghttp2

        # TLS/mTLS support
        ssl
        crypto
    )

    # Linker flags
    # Android 15 (API 35) requires 16KB page aligned ELF segments
    # -z,max-page-size=16384: Set page size to 16KB
    # -z,separate-loadable-segments: Force each LOAD segment to start at page boundary
    target_link_options(kanaha-camera-control PRIVATE
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
        -Wl,-z,separate-loadable-segments
    )
else()
    # Stub build - minimal linking
    target_link_libraries(
        kanaha-camera-control
        ${log-lib}
        ${android-lib}
        ${z-lib}
    )

    # Android 15 (API 35) requires 16KB page aligned ELF segments
    target_link_options(kanaha-camera-control PRIVATE
        -Wl,--gc-sections
        -Wl,--strip-all
        -Wl,-z,max-page-size=16384
        -Wl,-z,separate-loadable-segments
    )
endif()

# ============================================================================
# Create Native Executable (for ProcessBuilder launch)
# ============================================================================
# The executable is named with lib*.so convention so Android includes it in APK.
# ApacheService.java launches this via ProcessBuilder.
# ============================================================================

if(USE_CROSS_COMPILED_LIBS)
    # Build the HTTP server executable
    # Named libkanaha_httpd.so so Android build system includes it in APK
    # (Android only packages lib*.so files from native library directory)
    add_executable(
        kanaha_httpd.so
        main.c
        ${KANAHA_SOURCES}
    )

    # Set output name to include 'lib' prefix (Android convention)
    set_target_properties(kanaha_httpd.so PROPERTIES
        OUTPUT_NAME "kanaha_httpd"
        PREFIX "lib"
        SUFFIX ".so"
    )

    # PIE is required for Android 5.0+
    set_target_properties(kanaha_httpd.so PROPERTIES
        POSITION_INDEPENDENT_CODE ON
    )

    target_compile_options(kanaha_httpd.so PRIVATE
        -Wall
        -Wextra
        -O2
        -fPIE
        -ffunction-sections
        -fdata-sections
        -Wno-unused-parameter
        -Wno-unused-variable
    )

    target_link_libraries(
        kanaha_httpd.so

        # Android system libraries
        ${log-lib}
        ${android-lib}
        ${z-lib}

        # Axis2/C libraries (order matters for static linking)
        axis2_engine
        axis2_http_common
        axis2_http_util
        axis2_axiom
        axis2_parser
        guththila
        axutil

        # APR - Apache Portable Runtime
        aprutil-1
        apr-1
        expat

        # JSON-RPC parsing
        json-c

        # HTTP/2 protocol
        nghttp2

        # TLS/mTLS support
        ssl
        crypto
    )

    # Android 15 (API 35) requires 16KB page aligned ELF segments
    target_link_options(kanaha_httpd.so PRIVATE
        -fPIE
        -pie
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
        -Wl,-z,separate-loadable-segments
    )

    # Install executable to the same location as shared libraries
    # This ensures it's included in the APK's lib/<abi>/ directory
    install(TARGETS kanaha_httpd.so DESTINATION lib/${ANDROID_ABI})

    # ========================================================================
    # MCP stdio binary — launched by Claude Desktop as a subprocess
    # Named libkanaha_mcp.so for Android APK packaging convention
    # ========================================================================
    add_executable(
        kanaha_mcp.so
        axis2c/kanaha_mcp_main.c
        axis2c/kanaha_mcp.c
        axis2c/camera_control_service.c
    )

    set_target_properties(kanaha_mcp.so PROPERTIES
        OUTPUT_NAME "kanaha_mcp"
        PREFIX "lib"
        SUFFIX ".so"
        POSITION_INDEPENDENT_CODE ON
    )

    target_compile_options(kanaha_mcp.so PRIVATE
        -Wall -Wextra -O2 -fPIE
        -ffunction-sections -fdata-sections
        -Wno-unused-parameter -Wno-unused-variable
    )

    target_link_libraries(
        kanaha_mcp.so
        ${log-lib}
        ${z-lib}
        json-c
    )

    target_link_options(kanaha_mcp.so PRIVATE
        -fPIE -pie
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
        -Wl,-z,separate-loadable-segments
    )

    install(TARGETS kanaha_mcp.so DESTINATION lib/${ANDROID_ABI})
endif()

# ============================================================================
# ARCHITECTURE NOTES
# ============================================================================
#
# This build system creates three targets:
# 1. libkanaha-camera-control.so - Shared library (for potential JNI use)
# 2. libkanaha_httpd.so - Native executable (launched via ProcessBuilder)
# 3. libkanaha_mcp.so - MCP stdio executable (launched by Claude Desktop)
#
# The executable approach is preferred as it provides:
# - Clean process isolation between HTTP server and Android layer
# - Graceful shutdown via signal handling
# - No JNI complexity
#
# The cross-compiled libraries include:
# - Apache APR/APR-util: Portable runtime for Apache httpd
# - OpenSSL: TLS/SSL for mTLS support
# - nghttp2: HTTP/2 protocol support
# - Axis2/C: SOAP/REST web services framework with JSON-RPC
# - json-c: JSON parsing for JSON-RPC messages
#
# Communication Flow:
# HTTP/2 JSON → Axis2/C → system("am broadcast") → CameraControlReceiver → OpenCamera
#
# See docs/ANDROID_CROSS_COMPILATION.md for library build instructions.
# ============================================================================
