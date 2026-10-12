# CMakeLists.txt for Kanaha Camera: the MCP stdio binary
#
# Gradle builds one native target here, libkanaha_mcp.so: the MCP server that
# Claude reaches over adb stdio. ApacheService copies it out of nativeLibraryDir
# to files/kanaha-camera-mcp. It is an executable named lib*.so only so that
# Android packages and extracts it.
#
# The HTTP/2 server, libhttpd.so, is not built here. It is real Apache httpd
# with mod_axis2, linked by build-android.sh at the repository root against the
# libraries kanaha-android-deps builds, and Gradle packages it from jniLibs.

cmake_minimum_required(VERSION 3.18.1)

project("kanaha-camera-control")

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Kanaha Camera builds for arm64-v8a only, not ${ANDROID_ABI}")
endif()

# json-c, from kanaha-android-deps.
set(CROSS_COMPILED_DEPS "$ENV{HOME}/android-cross-builds/deps/arm64-v8a")
add_library(json-c STATIC IMPORTED)
set_target_properties(json-c PROPERTIES
    IMPORTED_LOCATION ${CROSS_COMPILED_DEPS}/lib/libjson-c.a)

find_library(log-lib log)
find_library(z-lib z)

# The MCP server calls the camera service directly, without Axis2/C.
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

target_include_directories(kanaha_mcp.so PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CROSS_COMPILED_DEPS}/include
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

# Android 15 (API 35) requires 16 KB page-aligned ELF segments.
target_link_options(kanaha_mcp.so PRIVATE
    -fPIE -pie
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    -Wl,-z,separate-loadable-segments
)

install(TARGETS kanaha_mcp.so DESTINATION lib/${ANDROID_ABI})
