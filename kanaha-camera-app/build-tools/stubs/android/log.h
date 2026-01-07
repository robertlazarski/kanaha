/*
 * Android Logging API Stub
 * For local compilation verification without Android NDK
 *
 * Usage: gcc -I/path/to/stubs -DANDROID ...
 */
#ifndef _ANDROID_LOG_H_STUB
#define _ANDROID_LOG_H_STUB

typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT
} android_LogPriority;

/*
 * Stub implementations - print to stderr for local debugging
 * In production Android builds, these are provided by liblog.so
 */
#include <stdio.h>
#include <stdarg.h>

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    va_end(args);
    (void)prio;
    return 0;
}

static inline int __android_log_write(int prio, const char* tag, const char* text) {
    fprintf(stderr, "[%s] %s\n", tag, text);
    (void)prio;
    return 0;
}

static inline int __android_log_vprint(int prio, const char* tag, const char* fmt, va_list ap) {
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    (void)prio;
    return 0;
}

#endif /* _ANDROID_LOG_H_STUB */
