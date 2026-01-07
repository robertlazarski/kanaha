# Local Build Verification Makefile
# Compiles native C sources without Android NDK for syntax/compile verification
#
# Usage:
#   make              - Build all .so files
#   make check        - Syntax check only (fast)
#   make clean        - Remove build artifacts
#   make test         - Build and run symbol inspection
#
# Requirements: gcc (Ubuntu: sudo apt install build-essential)

CC = gcc
CFLAGS = -std=c11 -Wall -Wextra -O2 -fPIC -D_GNU_SOURCE -DANDROID

# Paths
PROJECT_ROOT = ..
CPP_DIR = $(PROJECT_ROOT)/app/src/main/cpp
STUBS_DIR = stubs
BUILD_DIR = build

# Include paths
INCLUDES = -I$(STUBS_DIR) -I$(CPP_DIR) -I$(CPP_DIR)/axis2c/include -I$(CPP_DIR)/apache-httpd/include

# Source files
CAMERA_SERVICE_SRC = $(CPP_DIR)/axis2c/camera_control_service.c
APACHE_HTTPD_SRC = $(CPP_DIR)/apache-httpd/apache_httpd_android.c

# Output files
CAMERA_SERVICE_SO = $(BUILD_DIR)/libcamera_control_service.so
APACHE_HTTPD_SO = $(BUILD_DIR)/libapache_httpd_android.so
COMBINED_SO = $(BUILD_DIR)/libkanaha_camera_control.so

# Default target
all: $(BUILD_DIR) $(CAMERA_SERVICE_SO) $(APACHE_HTTPD_SO)
	@echo ""
	@echo "======================================"
	@echo " Build Verification Complete"
	@echo "======================================"
	@ls -la $(BUILD_DIR)/*.so
	@echo ""
	@echo "Note: These .so files are for verification only."
	@echo "      Use Android NDK for actual APK builds."

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

# Camera Control Service
$(CAMERA_SERVICE_SO): $(CAMERA_SERVICE_SRC) | $(BUILD_DIR)
	@echo "Building: camera_control_service.c -> libcamera_control_service.so"
	$(CC) -shared $(CFLAGS) -DAXIS2_JSON_ENABLED=1 $(INCLUDES) -o $@ $<
	@echo "OK: $@"

# Apache httpd Android stub
$(APACHE_HTTPD_SO): $(APACHE_HTTPD_SRC) | $(BUILD_DIR)
	@echo "Building: apache_httpd_android.c -> libapache_httpd_android.so"
	$(CC) -shared $(CFLAGS) -DAPACHE_HTTPD_ANDROID $(INCLUDES) -o $@ $<
	@echo "OK: $@"

# Combined library (matches CMakeLists.txt target)
combined: $(BUILD_DIR) $(COMBINED_SO)

$(COMBINED_SO): $(CAMERA_SERVICE_SRC) $(APACHE_HTTPD_SRC) | $(BUILD_DIR)
	@echo "Building combined: libkanaha_camera_control.so"
	$(CC) -shared $(CFLAGS) -DAXIS2_JSON_ENABLED=1 -DAPACHE_HTTPD_ANDROID $(INCLUDES) -o $@ $^
	@echo "OK: $@"

# Syntax check only (fast, no linking)
check:
	@echo "Syntax checking: camera_control_service.c"
	$(CC) -fsyntax-only $(CFLAGS) -DAXIS2_JSON_ENABLED=1 $(INCLUDES) $(CAMERA_SERVICE_SRC)
	@echo "OK: camera_control_service.c"
	@echo ""
	@echo "Syntax checking: apache_httpd_android.c"
	$(CC) -fsyntax-only $(CFLAGS) -DAPACHE_HTTPD_ANDROID $(INCLUDES) $(APACHE_HTTPD_SRC)
	@echo "OK: apache_httpd_android.c"
	@echo ""
	@echo "All syntax checks passed!"

# Test: build and inspect symbols
test: all
	@echo ""
	@echo "======================================"
	@echo " Symbol Inspection"
	@echo "======================================"
	@echo ""
	@echo "Exported symbols in libcamera_control_service.so:"
	@nm -D --defined-only $(CAMERA_SERVICE_SO) 2>/dev/null | grep ' T ' || echo "(no exported symbols - static functions only)"
	@echo ""
	@echo "Exported symbols in libapache_httpd_android.so:"
	@nm -D --defined-only $(APACHE_HTTPD_SO) 2>/dev/null | grep ' T ' || echo "(no exported symbols)"

# Clean build artifacts
clean:
	rm -rf $(BUILD_DIR)
	@echo "Cleaned build directory"

.PHONY: all check test clean combined
