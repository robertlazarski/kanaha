# Android ARM64 Cross-Compilation Guide

This document covers the Android-specific parts of building Kanaha Camera's native server: how the app's binary is linked, the static service registry, and 16 KB page alignment.

## Building the Dependencies

The app's server is Apache httpd with mod_ssl, mod_http2 and Axis2/C's mod_axis2, linked statically with OpenSSL, nghttp2, APR, APR-util, expat, PCRE2 and json-c. Those libraries are shared with Kanaha Audio and Kanaha Calcs, and are built by **[kanaha-android-deps](https://github.com/robertlazarski/kanaha-android-deps)**:

```bash
git clone https://github.com/robertlazarski/kanaha-android-deps.git
cd kanaha-android-deps && ./build-all.sh
```

That builds into `~/android-cross-builds`:

| Path | Contents |
|------|----------|
| `deps/arm64-v8a/lib`, `deps/arm64-v8a/include` | The static libraries and headers |
| `httpd-<version>/` | The httpd build tree; the link uses its archives, `modules.o` (with mod_axis2 registered) and `buildmark.o` |
| `deps/arm64-v8a/BUILD-INFO` | What was built: every pinned version and the Axis2/C commit |

`build-android.sh` reads the httpd version from `BUILD-INFO`, so a dependency upgrade happens in kanaha-android-deps alone; then run `build-android.sh` here and rebuild the APK. The pinned versions, patches and cross-compilation workarounds (the host-built `gen_test_char`, the `modules.c` registration, the APR patches) are documented in that repository, in its README and its step scripts.

> **Important:** Do NOT place cross-compiled builds inside `~/Android/Sdk`. That directory is managed by Android Studio and SDK Manager.

---

## Final Linking with Static Service Registry

The final httpd binary requires special linking to include all Axis2/C components, including the Android static service registry. **Libtool strips `--whole-archive` flags**, so direct linking is required.

### Why Direct Linking is Required


When linking static archives (.a files), the linker only includes object files that resolve undefined symbols. This causes problems for:

1. **Static service registry functions** - Not called from Apache httpd, only from Axis2/C internally
2. **Message receiver registrations** - Referenced at runtime, not link time
3. **Service implementations** - Invoked dynamically based on services.xml configuration

The `--whole-archive` flag forces inclusion of all objects, but libtool strips it. The solution is to bypass libtool entirely.

### The Link Script

The link is in this repository's `build-android.sh`. It compiles the camera service and the static service adapter, then links them with httpd's archives from the `httpd-<version>` tree and the libraries in `deps/arm64-v8a/lib`, calling the NDK's clang directly rather than libtool. It then strips the result and installs it as `kanaha-camera-app/app/src/main/jniLibs/arm64-v8a/libhttpd.so`, which Gradle packages as is.

### Key Linking Points

| Flag | Purpose |
|------|---------|
| `-Wl,--whole-archive` | Force inclusion of ALL objects from the archive |
| `-Wl,--no-whole-archive` | Resume normal archive linking |
| `-Wl,--export-dynamic` | Export symbols for dynamic lookups |
| `-fPIC` | Position-independent code for Android |
| `-pthread` | Android pthread (built into libc) |

### Verifying Symbol Inclusion

`build-android.sh` strips the binary, but `--export-dynamic` keeps the registry symbols in its dynamic symbol table, so you can check the installed file:

```bash
llvm-nm -D kanaha-camera-app/app/src/main/jniLibs/arm64-v8a/libhttpd.so \
    | grep -E "android_static|try_android|_service_invoke_json"

# Expected (T = defined here, W = only Axis2/C's weak stub):
# ... T android_static_service_lookup
# ... W audio_search_service_invoke_json
# ... T camera_control_service_invoke_json
# ... T camera_control_service_invoke_json_impl
# ... W financial_benchmark_service_invoke_json
# ... T try_android_static_service
```

`llvm-nm` is in the NDK's `toolchains/llvm/prebuilt/linux-x86_64/bin`. The line that matters is `camera_control_service_invoke_json`: `T` means the app's adapter was linked. `W` means only the weak stub is there: it returns `NULL`, the receiver falls back to loading the service with `dlopen()`, which fails on Android, and every request to the service returns an error. The other services show `W` because this app does not provide them.

### Deploy

```bash
cd kanaha-camera-app
./gradlew assembleFossDebug
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

`foss` is the default flavor. `nano` adds the on-device ML Kit model and needs Android 8 or later.

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

### Solution: Weak Stubs and a Lookup Table

The registry is in Axis2/C's `src/core/receivers/axis2_json_rpc_msg_recv.c`, under `#ifdef __ANDROID__`. It has three parts:

1. **A weak stub per known service.** Each returns `NULL`, so the engine links whether or not an app supplies that service:
   ```c
   __attribute__((weak))
   json_object* camera_control_service_invoke_json(
       const axutil_env_t *env, json_object *json_request)
   {
       return NULL;
   }
   ```
   The stubs today are `camera_control_service_invoke_json`, `audio_search_service_invoke_json` and `financial_benchmark_service_invoke_json`.

2. **`android_static_service_lookup()`**, which maps a service name to its function: `CameraControlService`, `AudioSearchService` or `FinancialBenchmarkService`.

3. **`try_android_static_service()`**, which the JSON-RPC receiver calls with the service and operation the URL resolved to. It parses the body and calls the function. If the body does not name an operation, it first adds `"operation": "<operation>"`, so a service with no message context still knows what was called. A body that names a different operation from the URL is only accepted on the catch-all location; the comment in that function gives the full rule.

The app supplies the real function under the same name. Its definition is strong, so the linker uses it instead of the stub. In this repository that is `kanaha-camera-app/app/src/main/cpp/axis2c/axis2_static_service_adapter.c`. It converts the `json_object` request to a string, calls the camera service's `camera_control_service_invoke_json_impl()`, and parses the reply back. `build-android.sh` archives the adapter and the service into `libkanaha_services.a` and links it with `--whole-archive`.

### Why Functions Must Be Non-Static

**Critical:** Registry functions MUST be non-static for archive linking:

- **Static functions** (`static` keyword) have internal linkage
- When linking from archives, the linker only includes objects that resolve undefined symbols
- Static functions not called from exported code are optimized away
- **Non-static functions** are exported symbols that the linker preserves

The same applies to the app's adapter: a `static` definition would not override the weak stub.

### Adding New Services

A new service needs a change in Axis2/C and three in the app.

1. **Axis2/C: a weak stub and a lookup entry.** In `axis2_json_rpc_msg_recv.c`, add a weak `my_service_invoke_json(env, json_request)` returning `NULL` next to the others, and a `strcmp` for `"MyService"` in `android_static_service_lookup()`. Without the entry, the receiver falls back to `dlopen()` and the requests fail, even when the app links an implementation.

2. **Rebuild the dependencies.** Commit the Axis2/C change, set `AXIS2C_REF` in kanaha-android-deps' `versions.env` to that commit, and run its `build-all.sh`. Do not patch `libaxis2_engine.a` in place: the next dependency rebuild would silently drop the change.

3. **App: the implementation.** Define `json_object* my_service_invoke_json(const axutil_env_t *env, json_object *json_request)`, non-static, returning a `json_object` the caller frees. Following `axis2_static_service_adapter.c`, keep it a thin adapter over the service. Add its sources to the compile and `ar` lines in `build-android.sh`, so they land in `libkanaha_services.a` inside the `--whole-archive` pair.

4. **App: services.xml, and deploying it.**
   - Add `kanaha-camera-app/app/src/main/assets/axis2c/services/MyService/services.xml`, modelled on `CameraControlService`'s, with an `<operation>` for every operation. Each needs `<messageReceiver class="axis2_json_rpc_msg_recv"/>`, `RESTMethod` `POST` and `RESTLocation` `/<operation>`. Keep the `jsonrpc` catch-all too.
   - An operation the file does not declare is answered with "Operation Not Found", even though the service implements it.
   - `ApacheService.java` creates the service directory and deploys the file by name, so add the new service there as well.
   - The `assets/axis2c/services.xml` one level up is not deployed.

Then run `build-android.sh`, check with `llvm-nm -D` that `my_service_invoke_json` shows `T`, and rebuild the APK.

---

## Android 15 16KB Page Alignment

Starting with Android 15 (API 35), devices with ARM64 processors may use 16KB memory pages instead of the traditional 4KB pages. Native libraries (.so files) that aren't properly aligned will trigger a compatibility warning dialog when the app launches.

### Why 16KB Pages?

The move to 16KB pages addresses several performance and efficiency issues that have become more pressing with modern hardware:

1. **Memory Management Overhead**: With 4KB pages, the kernel must maintain more page table entries. A 16GB device with 4KB pages needs 4 million page table entries just for physical memory mapping. With 16KB pages, this drops to 1 million entries.

2. **TLB (Translation Lookaside Buffer) Efficiency**: Modern ARM64 CPUs have limited TLB entries. Larger pages mean each TLB entry covers more memory, dramatically reducing TLB misses for memory-intensive workloads.

3. **I/O Performance**: File system operations benefit from larger page sizes since a single page fault can bring in more data. This is particularly important for database operations and large file access.

4. **Memory Fragmentation**: Larger pages reduce internal fragmentation overhead percentage-wise, though they can increase absolute waste for small allocations.

5. **ARM64 Hardware Support**: ARM64 architecture natively supports 4KB, 16KB, and 64KB pages. Apple Silicon (M1/M2/M3) uses 16KB pages, and Android is following suit to align with modern ARM ecosystem trends.

### Historical Context

Android historically required 4KB pages because:
- Early ARM devices (32-bit) only supported 4KB
- Compatibility with Linux x86 ecosystem (4KB standard)
- Legacy NDK binaries compiled with 4KB assumptions

The transition started with Android 15 as ARMv8+ devices became universal and the performance benefits outweighed compatibility concerns.

### Linker Flags for 16KB Alignment

All native libraries must be linked with these flags:

```bash
# Required for Android 15+ 16KB page support
-Wl,-z,max-page-size=16384
-Wl,-z,separate-loadable-segments
```

- **max-page-size=16384**: Sets the ELF page size to 16KB
- **separate-loadable-segments**: Forces each LOAD segment to start at a page boundary (not just be congruent mod page_size)

### CMake Configuration

In `build.gradle`, add the CMake argument:
```groovy
externalNativeBuild {
    cmake {
        arguments '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
                  '-DANDROID_STL=c++_static'  // Avoid libc++_shared.so alignment issues
    }
}
```

In `CMakeLists.txt`, add linker options:
```cmake
target_link_options(your_library PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,separate-loadable-segments
)
```

### Static vs Shared C++ Runtime

The NDK's pre-built `libc++_shared.so` may not have proper 16KB alignment. Use static linking (`c++_static`) to avoid this issue. The trade-off is slightly larger binaries but guaranteed alignment.

### Verifying Alignment

Check ELF segment alignment with `readelf`:

```bash
readelf -l libfoo.so | grep LOAD

# Good (all offsets are multiples of 0x4000 = 16384):
LOAD  0x0000000000000000 0x0000000000000000 ... 0x4000
LOAD  0x0000000000004000 0x0000000000004000 ... 0x4000

# Bad (offsets not aligned to 16KB):
LOAD  0x0000000000000000 0x0000000000000000 ... 0x1000
LOAD  0x00000000000f18d0 0x00000000000f28d0 ... 0x1000
```

---

## Version History

| Date | Changes |
|------|---------|
| 2026-10-12 | Rewrote the static service registry and "Adding New Services" for the weak-stub design: the app supplies the implementation, and a new service also needs its `services.xml` deployed. Symbol check uses `llvm-nm -D`, which works on the stripped binary. Deploy uses the `foss` flavor |
| 2026-10-09 | The dependency build steps (OpenSSL through Axis2/C, with their patches and workarounds) moved to [kanaha-android-deps](https://github.com/robertlazarski/kanaha-android-deps); `build-android.sh` takes the httpd version from its `BUILD-INFO`. Dropped the inline copy of the old link script and the in-place `libaxis2_engine.a` update |
| 2026-10-07 | Dependency refresh: OpenSSL 3.2.0 → 3.5.9, httpd 2.4.66 → 2.4.69, expat 2.6.4 → 2.9.0, Axis2/C master `ce7c8f381`. Documented the `modules.c` mod_axis2 registration (Issue 5), which had only existed inside the old httpd tree, and the `gen_test_char` relink trap (Issue 1) |
| 2026-02-15 | Rebuilt Axis2/C from `3004810a1`: JSON reader security fixes, HTTP worker hardening, deprecated feature removal, SNI support |
| 2026-01-31 | Added 16KB page alignment documentation for Android 15, updated linker flags for all native libraries |
| 2026-01-31 | Added "Rebuilding Axis2/C for Security Updates" section with AXIS2C-1708 security hardening documentation and rebuild script |
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
