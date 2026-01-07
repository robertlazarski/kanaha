/*
 * Kanaha Camera Control Server - Native Executable Entry Point
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This is the main() entry point for the standalone HTTP server executable.
 * It is launched via ProcessBuilder from ApacheService.java.
 *
 * Usage: ./kanaha-httpd [-p port] [-d repo_path]
 *
 * ARCHITECTURE: No JNI - Runs as separate native process
 * Communication: Intent IPC via system("am broadcast ...")
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <android/log.h>

#define LOG_TAG "KanahaHttpdMain"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* External function from apache_httpd_android.c */
extern int apache_httpd_main(int argc, char* argv[]);
extern void apache_httpd_signal_shutdown(void);

/* Signal handler for graceful shutdown */
static void signal_handler(int signum) {
    LOGI("Received signal %d, shutting down...", signum);
    apache_httpd_signal_shutdown();
}

/**
 * Main entry point for the Kanaha HTTP server executable.
 *
 * This executable is launched via ProcessBuilder from ApacheService.java
 * and runs in its own process, separate from the Android Java layer.
 *
 * The server communicates with the Java layer via Internal Intent IPC
 * (system("am broadcast ...")) rather than JNI callbacks.
 */
int main(int argc, char* argv[]) {
    LOGI("=== Kanaha Camera Control Server Starting ===");
    LOGI("Process ID: %d", getpid());

    /* Set up signal handlers for graceful shutdown */
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    /* Log command line arguments */
    for (int i = 0; i < argc; i++) {
        LOGI("argv[%d] = %s", i, argv[i]);
    }

    /* Start the HTTP server */
    int result = apache_httpd_main(argc, argv);

    LOGI("=== Kanaha Camera Control Server Exited (code: %d) ===", result);
    return result;
}
