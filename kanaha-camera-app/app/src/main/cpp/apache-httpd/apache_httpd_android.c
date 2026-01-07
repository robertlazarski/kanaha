/*
 * Kanaha Camera Control System
 * HTTP Server with JSON-RPC, TLS, and HTTP/2 Support
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * This provides a lightweight HTTP server using:
 * - json-c for JSON-RPC parsing
 * - OpenSSL for TLS/mTLS
 * - nghttp2 for HTTP/2 protocol info
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <android/log.h>

#define LOG_TAG "KanahaServer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#ifdef USE_CROSS_COMPILED_LIBS

/* json-c for JSON-RPC */
#include <json-c/json.h>

/* OpenSSL for TLS */
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/x509.h>
#include <openssl/opensslv.h>

/* nghttp2 for HTTP/2 version info */
#include <nghttp2/nghttp2.h>

/* Server state */
static volatile int g_server_running = 0;
static int g_server_port = 443;
static char g_repo_path[512] = {0};
static char g_ssl_cert_path[512] = {0};
static char g_ssl_key_path[512] = {0};
static char g_ssl_ca_path[512] = {0};
static SSL_CTX *g_ssl_ctx = NULL;
static pthread_t g_server_thread;
static int g_connection_count = 0;
static int g_tls_enabled = 0;

/* External camera control service function - matches signature in camera_control_service.c */
extern int camera_control_service_invoke_json_impl(
    const char* json_request,
    char* json_response,
    size_t response_size
);

/**
 * Initialize OpenSSL library and configure TLS/mTLS
 */
static int init_openssl(void) {
    LOGI("Initializing OpenSSL...");

#if OPENSSL_VERSION_NUMBER < 0x10100000L
    SSL_library_init();
    SSL_load_error_strings();
    OpenSSL_add_all_algorithms();
#else
    OPENSSL_init_ssl(OPENSSL_INIT_LOAD_SSL_STRINGS | OPENSSL_INIT_LOAD_CRYPTO_STRINGS, NULL);
#endif

    /* Create SSL context for TLS server */
    g_ssl_ctx = SSL_CTX_new(TLS_server_method());
    if (!g_ssl_ctx) {
        LOGE("Failed to create SSL context");
        ERR_print_errors_fp(stderr);
        return -1;
    }

    /* Set minimum TLS version to 1.2 */
    SSL_CTX_set_min_proto_version(g_ssl_ctx, TLS1_2_VERSION);

    /* Log version info */
    LOGI("OpenSSL initialized: %s", OpenSSL_version(OPENSSL_VERSION));
    LOGI("json-c version: %s", json_c_version());
    LOGI("nghttp2 version: %s", nghttp2_version(0)->version_str);

    /* Load server certificate if paths are configured */
    if (g_ssl_cert_path[0] && g_ssl_key_path[0]) {
        LOGI("Loading server certificate: %s", g_ssl_cert_path);
        if (SSL_CTX_use_certificate_file(g_ssl_ctx, g_ssl_cert_path, SSL_FILETYPE_PEM) != 1) {
            LOGE("Failed to load server certificate: %s", g_ssl_cert_path);
            ERR_print_errors_fp(stderr);
            return -1;
        }

        LOGI("Loading server private key: %s", g_ssl_key_path);
        if (SSL_CTX_use_PrivateKey_file(g_ssl_ctx, g_ssl_key_path, SSL_FILETYPE_PEM) != 1) {
            LOGE("Failed to load server private key: %s", g_ssl_key_path);
            ERR_print_errors_fp(stderr);
            return -1;
        }

        /* Verify private key matches certificate */
        if (SSL_CTX_check_private_key(g_ssl_ctx) != 1) {
            LOGE("Server private key does not match certificate");
            ERR_print_errors_fp(stderr);
            return -1;
        }

        /* Load CA certificate for client verification (mTLS) */
        if (g_ssl_ca_path[0]) {
            LOGI("Loading CA certificate for mTLS: %s", g_ssl_ca_path);
            if (SSL_CTX_load_verify_locations(g_ssl_ctx, g_ssl_ca_path, NULL) != 1) {
                LOGE("Failed to load CA certificate: %s", g_ssl_ca_path);
                ERR_print_errors_fp(stderr);
                return -1;
            }

            /* Require client certificate (mTLS) */
            SSL_CTX_set_verify(g_ssl_ctx, SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, NULL);
            SSL_CTX_set_verify_depth(g_ssl_ctx, 2);
            LOGI("mTLS enabled: client certificates required");
        }

        g_tls_enabled = 1;
        LOGI("TLS enabled with server certificate");
    } else {
        LOGW("TLS disabled: no certificate paths configured");
        g_tls_enabled = 0;
    }

    return 0;
}

/**
 * Cleanup OpenSSL
 */
static void cleanup_openssl(void) {
    if (g_ssl_ctx) {
        SSL_CTX_free(g_ssl_ctx);
        g_ssl_ctx = NULL;
    }
#if OPENSSL_VERSION_NUMBER < 0x10100000L
    EVP_cleanup();
    ERR_free_strings();
#endif
}

/**
 * Parse JSON request and invoke camera control service
 *
 * Supports action-based format: {"action":"start_recording","clip_name":"test",...}
 * The service implementation handles action validation and routing.
 */
static char* handle_json_request(const char *request_body) {
    struct json_object *request = NULL;
    struct json_object *action_obj = NULL;
    char *result = NULL;

    LOGD("JSON request: %.100s%s", request_body,
         strlen(request_body) > 100 ? "..." : "");

    /* Parse JSON */
    request = json_tokener_parse(request_body);
    if (!request) {
        LOGE("JSON parse error");
        return strdup("{\"error\":\"Parse error\",\"code\":-32700}");
    }

    /* Validate action-based structure */
    if (!json_object_object_get_ex(request, "action", &action_obj)) {
        LOGE("Missing action field");
        json_object_put(request);
        return strdup("{\"error\":\"Missing 'action' parameter\",\"code\":-32600}");
    }

    LOGI("Service action: %s", json_object_get_string(action_obj));

    /* Invoke camera control service */
    char response_buffer[4096];
    int invoke_result = camera_control_service_invoke_json_impl(request_body, response_buffer, sizeof(response_buffer));

    if (invoke_result == 0) {
        result = strdup(response_buffer);
    } else {
        result = strdup("{\"error\":\"Internal error\",\"code\":-32603}");
    }

    json_object_put(request);
    return result;
}

/**
 * Build HTTP response (plain socket)
 */
static void send_http_response(int fd, int status, const char *status_text,
                               const char *content_type, const char *body) {
    char header[1024];
    size_t body_len = body ? strlen(body) : 0;

    snprintf(header, sizeof(header),
             "HTTP/1.1 %d %s\r\n"
             "Content-Type: %s\r\n"
             "Content-Length: %zu\r\n"
             "Connection: close\r\n"
             "Server: Kanaha/2.0\r\n"
             "\r\n",
             status, status_text, content_type, body_len);

    send(fd, header, strlen(header), 0);
    if (body && body_len > 0) {
        send(fd, body, body_len, 0);
    }
}

/**
 * Build HTTP response (SSL)
 */
static void send_http_response_ssl(SSL *ssl, int status, const char *status_text,
                                   const char *content_type, const char *body) {
    char header[1024];
    size_t body_len = body ? strlen(body) : 0;

    snprintf(header, sizeof(header),
             "HTTP/1.1 %d %s\r\n"
             "Content-Type: %s\r\n"
             "Content-Length: %zu\r\n"
             "Connection: close\r\n"
             "Server: Kanaha/2.0\r\n"
             "\r\n",
             status, status_text, content_type, body_len);

    SSL_write(ssl, header, strlen(header));
    if (body && body_len > 0) {
        SSL_write(ssl, body, body_len);
    }
}

/**
 * Handle client connection (TLS or plain)
 */
static void handle_client(int client_fd, struct sockaddr_in *client_addr) {
    char buffer[8192];
    char *body_start;
    char *response;
    ssize_t bytes_read;
    SSL *ssl = NULL;

    g_connection_count++;

    LOGD("Connection #%d from %s:%d (TLS: %s)",
         g_connection_count,
         inet_ntoa(client_addr->sin_addr),
         ntohs(client_addr->sin_port),
         g_tls_enabled ? "yes" : "no");

    /* TLS handshake if enabled */
    if (g_tls_enabled) {
        ssl = SSL_new(g_ssl_ctx);
        if (!ssl) {
            LOGE("Failed to create SSL object");
            close(client_fd);
            return;
        }

        SSL_set_fd(ssl, client_fd);

        int ssl_accept_result = SSL_accept(ssl);
        if (ssl_accept_result != 1) {
            int ssl_error = SSL_get_error(ssl, ssl_accept_result);
            LOGE("SSL_accept failed: error=%d", ssl_error);
            ERR_print_errors_fp(stderr);
            SSL_free(ssl);
            close(client_fd);
            return;
        }

        /* Log client certificate info */
        X509 *client_cert = SSL_get_peer_certificate(ssl);
        if (client_cert) {
            char subject[256];
            X509_NAME_oneline(X509_get_subject_name(client_cert), subject, sizeof(subject));
            LOGI("Client certificate: %s", subject);
            X509_free(client_cert);
        }

        /* Read HTTP request over TLS */
        bytes_read = SSL_read(ssl, buffer, sizeof(buffer) - 1);
    } else {
        /* Read HTTP request over plain socket */
        bytes_read = recv(client_fd, buffer, sizeof(buffer) - 1, 0);
    }

    if (bytes_read <= 0) {
        LOGD("Connection closed (no data)");
        if (ssl) SSL_free(ssl);
        close(client_fd);
        return;
    }
    buffer[bytes_read] = '\0';

    /* Find body */
    body_start = strstr(buffer, "\r\n\r\n");
    if (!body_start) {
        if (ssl) {
            send_http_response_ssl(ssl, 400, "Bad Request", "text/plain", "Bad Request");
            SSL_shutdown(ssl);
            SSL_free(ssl);
        } else {
            send_http_response(client_fd, 400, "Bad Request", "text/plain", "Bad Request");
        }
        close(client_fd);
        return;
    }
    body_start += 4;

    /* Route request */
    if (strstr(buffer, "POST") && strstr(buffer, "application/json")) {
        /* JSON service endpoint (action-based format) */
        response = handle_json_request(body_start);
        if (ssl) {
            send_http_response_ssl(ssl, 200, "OK", "application/json", response);
        } else {
            send_http_response(client_fd, 200, "OK", "application/json", response);
        }
        free(response);
    }
    else if (strstr(buffer, "GET /status") || strstr(buffer, "GET / ")) {
        /* Status endpoint */
        char status_json[1024];
        snprintf(status_json, sizeof(status_json),
            "{"
            "\"status\":\"running\","
            "\"port\":%d,"
            "\"tls_enabled\":%s,"
            "\"connections\":%d,"
            "\"version\":\"2.0.0\","
            "\"libraries\":{"
            "\"openssl\":\"%s\","
            "\"json-c\":\"%s\","
            "\"nghttp2\":\"%s\""
            "},"
            "\"features\":[\"json-rpc\",\"tls\",\"mtls\",\"http2-ready\"]"
            "}",
            g_server_port,
            g_tls_enabled ? "true" : "false",
            g_connection_count,
            OpenSSL_version(OPENSSL_VERSION),
            json_c_version(),
            nghttp2_version(0)->version_str
        );
        if (ssl) {
            send_http_response_ssl(ssl, 200, "OK", "application/json", status_json);
        } else {
            send_http_response(client_fd, 200, "OK", "application/json", status_json);
        }
    }
    else if (strstr(buffer, "GET /health")) {
        if (ssl) {
            send_http_response_ssl(ssl, 200, "OK", "application/json", "{\"healthy\":true}");
        } else {
            send_http_response(client_fd, 200, "OK", "application/json", "{\"healthy\":true}");
        }
    }
    else {
        if (ssl) {
            send_http_response_ssl(ssl, 404, "Not Found", "text/plain", "Not Found");
        } else {
            send_http_response(client_fd, 404, "Not Found", "text/plain", "Not Found");
        }
    }

    /* Cleanup */
    if (ssl) {
        SSL_shutdown(ssl);
        SSL_free(ssl);
    }
    close(client_fd);
}

/**
 * Server main loop
 */
static void* server_thread_func(void *arg) {
    int server_fd, client_fd;
    struct sockaddr_in server_addr, client_addr;
    socklen_t client_len = sizeof(client_addr);
    int opt = 1;

    (void)arg;

    LOGI("Starting server on port %d...", g_server_port);

    /* Create socket */
    server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return NULL;
    }

    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    /* Bind */
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(g_server_port);

    if (bind(server_fd, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        LOGE("bind() failed: %s", strerror(errno));
        close(server_fd);
        return NULL;
    }

    /* Listen */
    if (listen(server_fd, 10) < 0) {
        LOGE("listen() failed: %s", strerror(errno));
        close(server_fd);
        return NULL;
    }

    LOGI("Server listening on port %d", g_server_port);
    g_server_running = 1;

    /* Accept loop */
    while (g_server_running) {
        fd_set read_fds;
        struct timeval tv = {1, 0};

        FD_ZERO(&read_fds);
        FD_SET(server_fd, &read_fds);

        if (select(server_fd + 1, &read_fds, NULL, NULL, &tv) > 0) {
            client_fd = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
            if (client_fd >= 0) {
                handle_client(client_fd, &client_addr);
            }
        }
    }

    close(server_fd);
    LOGI("Server stopped");
    return NULL;
}

/**
 * Main entry point
 *
 * Arguments:
 *   -p <port>      Server port (default: 443)
 *   -d <path>      Repository path
 *   -c <path>      Server certificate file (PEM)
 *   -k <path>      Server private key file (PEM)
 *   -a <path>      CA certificate file for mTLS (PEM)
 */
int apache_httpd_main(int argc, char* argv[]) {
    const char *repo_path = "/data/data/net.sourceforge.opencamera/files/axis2";
    const char *cert_path = NULL;
    const char *key_path = NULL;
    const char *ca_path = NULL;
    int port = 443;
    int i;

    LOGI("=== Kanaha Camera Control Server ===");
    LOGI("Built with:");
    LOGI("  OpenSSL: %s", OpenSSL_version(OPENSSL_VERSION));
    LOGI("  json-c: %s", json_c_version());
    LOGI("  nghttp2: %s", nghttp2_version(0)->version_str);

    /* Parse arguments */
    for (i = 0; i < argc; i++) {
        if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) {
            port = atoi(argv[i + 1]);
        } else if (strcmp(argv[i], "-d") == 0 && i + 1 < argc) {
            repo_path = argv[i + 1];
        } else if (strcmp(argv[i], "-c") == 0 && i + 1 < argc) {
            cert_path = argv[i + 1];
        } else if (strcmp(argv[i], "-k") == 0 && i + 1 < argc) {
            key_path = argv[i + 1];
        } else if (strcmp(argv[i], "-a") == 0 && i + 1 < argc) {
            ca_path = argv[i + 1];
        }
    }

    strncpy(g_repo_path, repo_path, sizeof(g_repo_path) - 1);
    g_server_port = port;

    /* Set SSL paths if provided */
    if (cert_path) strncpy(g_ssl_cert_path, cert_path, sizeof(g_ssl_cert_path) - 1);
    if (key_path) strncpy(g_ssl_key_path, key_path, sizeof(g_ssl_key_path) - 1);
    if (ca_path) strncpy(g_ssl_ca_path, ca_path, sizeof(g_ssl_ca_path) - 1);

    LOGI("Configuration: port=%d, repo=%s", port, repo_path);
    LOGI("SSL: cert=%s, key=%s, ca=%s",
         g_ssl_cert_path[0] ? g_ssl_cert_path : "(none)",
         g_ssl_key_path[0] ? g_ssl_key_path : "(none)",
         g_ssl_ca_path[0] ? g_ssl_ca_path : "(none)");

    /* Initialize */
    if (init_openssl() != 0) {
        LOGE("OpenSSL init failed");
        return 1;
    }

    /* Start server */
    if (pthread_create(&g_server_thread, NULL, server_thread_func, NULL) != 0) {
        LOGE("Failed to start server thread");
        cleanup_openssl();
        return 1;
    }

    /* Wait for server to start */
    while (!g_server_running && g_server_running != -1) {
        usleep(100000);
    }

    LOGI("Server running on port %d", g_server_port);

    /* Main loop */
    while (g_server_running == 1) {
        sleep(5);
        LOGD("Heartbeat: port=%d, connections=%d", g_server_port, g_connection_count);
    }

    /* Cleanup */
    pthread_join(g_server_thread, NULL);
    cleanup_openssl();

    LOGI("=== Server Exited ===");
    return 0;
}

int apache_httpd_validate_config(const char* config_file) {
    struct stat st;
    LOGI("Validating: %s", config_file);
    return stat(config_file, &st) == 0 ? 0 : -1;
}

int apache_httpd_get_status(char* buffer, size_t size) {
    return snprintf(buffer, size,
        "{\"status\":\"%s\",\"port\":%d,\"connections\":%d,"
        "\"openssl\":\"%s\",\"json-c\":\"%s\",\"nghttp2\":\"%s\"}",
        g_server_running ? "running" : "stopped",
        g_server_port, g_connection_count,
        OpenSSL_version(OPENSSL_VERSION),
        json_c_version(),
        nghttp2_version(0)->version_str
    ) < (int)size ? 0 : -1;
}

void apache_httpd_signal_shutdown(void) {
    LOGI("Shutdown signal");
    g_server_running = 0;
}

int apache_httpd_is_running(void) {
    return g_server_running == 1;
}

int apache_httpd_get_port(void) {
    return g_server_port;
}

#else /* Stub for non-arm64 */

int apache_httpd_main(int argc, char* argv[]) {
    LOGI("Server main (STUB)");
    (void)argc; (void)argv;
    sleep(5);
    return 0;
}

int apache_httpd_validate_config(const char* f) {
    LOGI("Validate (STUB): %s", f);
    return 0;
}

int apache_httpd_get_status(char* buf, size_t sz) {
    const char* s = "{\"status\":\"stub\",\"port\":443}";
    if (buf && sz > strlen(s)) { strcpy(buf, s); return 0; }
    return -1;
}

void apache_httpd_signal_shutdown(void) { LOGI("Shutdown (STUB)"); }
int apache_httpd_is_running(void) { return 0; }
int apache_httpd_get_port(void) { return 443; }

#endif
