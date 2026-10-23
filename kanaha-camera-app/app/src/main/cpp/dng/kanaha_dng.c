/*
 * Kanaha Camera Control System
 * kanaha_dng: compresses a 12-bit RAW take into lossless DNG frames
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * WHAT THIS IS
 *   A separate program the camera app starts, the way ApacheService starts
 *   httpd: an executable packaged as libkanaha_dng.so so Android extracts it,
 *   run with ProcessBuilder, never loaded into the app. No JNI.
 *
 * WHY A SEPARATE PROGRAM
 *   Uncompressed 12-bit frames are 18.8 MB each; at 30 fps a four-minute song
 *   is 135 GB, more than the phone's storage writes at full speed. Lossless
 *   compression (lj92.c) brings it to about 60 GB, and C does it on about one
 *   core where Java could not keep up.
 *
 * HOW FRAMES ARRIVE
 *   Through a named pipe (a FIFO) the app creates in its private directory and
 *   passes as argv[1]. The app writes each camera frame into it from shared
 *   memory; frames travel through memory, never through flash twice. Messages,
 *   all little-endian:
 *
 *     16-byte header:  "KDNG"  uint32 type  uint32 index  uint32 length
 *     type 1 TAKE   once, first: uint32 width, height, stride_bytes, header_len,
 *                   then header_len bytes of DNG header (everything before the
 *                   image data, from PackedDngWriter: the camera's own tags)
 *     type 2 FRAME  index = frame number; length bytes of RAW_SENSOR samples
 *                   (16-bit little-endian, rows stride_bytes apart)
 *     type 3 END    no more frames; finish the queue and exit
 *
 *   Anything else, or a size that does not match the take, ends the take with
 *   an error: the input comes from the app, but is checked all the same.
 *
 * WHAT IT WRITES
 *   argv[2]/frame_NNNNNN.dng for each frame: the take's header with Compression
 *   set to 7 (lossless JPEG) and the image's byte count, then the compressed
 *   image. One line per frame on stdout, which the app reads:
 *
 *     READY                        pipe open, waiting for TAKE
 *     OK <index> <bytes> <compress_ms> <write_ms> <read_ms> <wait_ms>
 *                                  frame written; read_ms = reading it from the
 *                                  pipe, wait_ms = waiting for a free buffer first
 *     ERR <message>                a frame or the take failed
 *     DONE <frames> <bytes>        END received, every frame written
 *
 * THREADS
 *   The main thread reads the pipe into a pool of frame buffers; argv[3]
 *   encoder threads (3 by default) compress and write. When every buffer is in
 *   use the main thread stops reading, the pipe fills, and the app's writes
 *   block: back-pressure, so the app sees a slow helper as dropped frames, which
 *   it counts, rather than this process growing without bound.
 */
#include "lj92.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define MSG_TAKE  1
#define MSG_FRAME 2
#define MSG_END   3

#define MAX_DIM          16384
#define MAX_HEADER       (1 << 20)
#define POOL_FRAMES      8          /* frame buffers: 8 x 25 MB at 4080x3072 */
#define MAX_THREADS      8

/* ---- output: one line per event, from any thread ------------------------ */

static pthread_mutex_t out_lock = PTHREAD_MUTEX_INITIALIZER;

static void say(const char *fmt, ...) {
    va_list ap;
    pthread_mutex_lock(&out_lock);
    va_start(ap, fmt); vprintf(fmt, ap); va_end(ap);
    putchar('\n'); fflush(stdout);
    pthread_mutex_unlock(&out_lock);
}

/* ---- the take: what TAKE told us, shared read-only by the encoders ------ */

typedef struct {
    int width, height;
    size_t stride, frame_bytes_min, frame_bytes_max;
    uint8_t *header; size_t header_len;
    size_t compression_at, byte_count_at;   /* offsets of the values to patch */
    int big_endian;
    lj92_take_t table;                      /* built from the first frame */
    int table_ready;
    const char *dir;
} take_t;

static take_t take;

/* ---- frame buffers and the job queue ------------------------------------ */

typedef struct { uint8_t *data; size_t len; uint32_t index; double read_ms, wait_ms; } slot_t;

static slot_t pool[POOL_FRAMES];
static int free_list[POOL_FRAMES], free_n;            /* slots ready to fill */
static int queue[POOL_FRAMES], q_head, q_n;           /* slots ready to encode */
static int ending;                                     /* END received */
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t slot_freed = PTHREAD_COND_INITIALIZER;
static pthread_cond_t job_ready = PTHREAD_COND_INITIALIZER;
static long frames_done; static unsigned long long bytes_done;

static int take_slot(void) {
    pthread_mutex_lock(&lock);
    while (free_n == 0) pthread_cond_wait(&slot_freed, &lock);
    int s = free_list[--free_n];
    pthread_mutex_unlock(&lock);
    return s;
}

static void give_back(int s) {
    pthread_mutex_lock(&lock);
    free_list[free_n++] = s;
    pthread_cond_signal(&slot_freed);
    pthread_mutex_unlock(&lock);
}

static void enqueue(int s) {
    pthread_mutex_lock(&lock);
    queue[(q_head + q_n++) % POOL_FRAMES] = s;
    pthread_cond_signal(&job_ready);
    pthread_mutex_unlock(&lock);
}

/* Next slot to encode, or -1 once END has come and the queue is empty. */
static int next_job(void) {
    pthread_mutex_lock(&lock);
    while (q_n == 0 && !ending) pthread_cond_wait(&job_ready, &lock);
    int s = -1;
    if (q_n > 0) { s = queue[q_head]; q_head = (q_head + 1) % POOL_FRAMES; q_n--; }
    pthread_mutex_unlock(&lock);
    return s;
}

/* ---- reading the pipe ----------------------------------------------------- */

/* Read exactly n bytes; 0, or -1 on end of input or error. */
static int read_full(int fd, void *buf, size_t n) {
    uint8_t *p = buf;
    while (n > 0) {
        ssize_t r = read(fd, p, n);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        p += r; n -= (size_t)r;
    }
    return 0;
}

static uint32_t le32(const uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

/* ---- the DNG header -------------------------------------------------------- */

static uint32_t hdr16(size_t o) {
    const uint8_t *p = take.header + o;
    return take.big_endian ? (uint32_t)(p[0] << 8 | p[1]) : (uint32_t)(p[0] | p[1] << 8);
}
static uint32_t hdr32(size_t o) {
    return take.big_endian ? (hdr16(o) << 16 | hdr16(o + 2)) : (hdr16(o) | hdr16(o + 2) << 16);
}

/* Find where Compression (259) and StripByteCounts (279) keep their values, and
 * check the header describes this take: one strip, at the end of the header. */
static int parse_header(void) {
    if (take.header_len < 16) return -1;
    if (take.header[0] == 'M' && take.header[1] == 'M') take.big_endian = 1;
    else if (take.header[0] == 'I' && take.header[1] == 'I') take.big_endian = 0;
    else return -1;
    size_t ifd = hdr32(4);
    if (ifd + 2 > take.header_len) return -1;
    size_t n = hdr16(ifd);
    if (ifd + 2 + 12 * n + 4 > take.header_len) return -1;
    uint32_t strip_at = 0;
    for (size_t i = 0; i < n; i++) {
        size_t e = ifd + 2 + 12 * i;
        uint32_t tag = hdr16(e), type = hdr16(e + 2), count = hdr32(e + 4);
        if (tag == 259 && type == 3 && count == 1) take.compression_at = e + 8;
        if (tag == 279 && type == 4 && count == 1) take.byte_count_at = e + 8;
        if (tag == 273 && type == 4 && count == 1) strip_at = hdr32(e + 8);
        if (tag == 256 && hdr32(e + 8) != (uint32_t)take.width && hdr16(e + 8) != (uint32_t)take.width) return -1;
    }
    if (!take.compression_at || !take.byte_count_at || strip_at != take.header_len) return -1;
    return 0;
}

static void put16(uint8_t *p, uint32_t v) {
    if (take.big_endian) { p[0] = (uint8_t)(v >> 8); p[1] = (uint8_t)v; } else { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
}
static void put32(uint8_t *p, uint32_t v) {
    if (take.big_endian) { put16(p, v >> 16); put16(p + 2, v); } else { put16(p, v); put16(p + 2, v >> 16); }
}

/* ---- encoder threads ------------------------------------------------------ */

static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1e3 + t.tv_nsec / 1e6; }

static void *encoder(void *arg) {
    (void)arg;
    lj92_out_t out = {0};
    uint8_t *header = malloc(take.header_len);   /* this thread's copy, patched per frame */
    memcpy(header, take.header, take.header_len);
    put16(header + take.compression_at, 7);      /* Compression = lossless JPEG */

    for (int s; (s = next_job()) >= 0; give_back(s)) {
        slot_t *slot = &pool[s];
        double t0 = now_ms();
        lj92_frame_t f = { slot->data, take.width, take.height, take.stride };
        if (lj92_encode(&take.table, &f, &out) != 0) { say("ERR %u out of memory", slot->index); continue; }
        double t1 = now_ms();
        put32(header + take.byte_count_at, (uint32_t)out.len);

        char path[1024];
        snprintf(path, sizeof path, "%s/frame_%06u.dng", take.dir, slot->index);
        FILE *fp = fopen(path, "wb");
        int ok = fp && fwrite(header, 1, take.header_len, fp) == take.header_len
                    && fwrite(out.buf, 1, out.len, fp) == out.len;
        if (fp && fclose(fp) != 0) ok = 0;
        if (!ok) { say("ERR %u write %s: %s", slot->index, path, strerror(errno)); continue; }

        size_t bytes = take.header_len + out.len;
        pthread_mutex_lock(&lock); frames_done++; bytes_done += bytes; pthread_mutex_unlock(&lock);
        say("OK %u %zu %.0f %.0f %.0f %.0f", slot->index, bytes, t1 - t0, now_ms() - t1, slot->read_ms, slot->wait_ms);
    }
    free(out.buf); free(header);
    return NULL;
}

/* ---- main: open the pipe, read the take, hand frames to the encoders ---- */

static int fail(const char *msg) { say("ERR %s", msg); return 1; }

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s FIFO OUT_DIR [THREADS]\n", argv[0]); return 2; }
    int threads = argc > 3 ? atoi(argv[3]) : 3;
    if (threads < 1 || threads > MAX_THREADS) return fail("THREADS must be 1 to 8");
    take.dir = argv[2];

    int fd = open(argv[1], O_RDONLY);            /* blocks until the app opens it to write */
    if (fd < 0) return fail("cannot open the pipe");
#ifdef F_SETPIPE_SZ
    fcntl(fd, F_SETPIPE_SZ, 1 << 20);            /* fewer, larger reads; best effort */
#endif
    say("READY");

    uint8_t h[16];
    if (read_full(fd, h, 16) || memcmp(h, "KDNG", 4) || le32(h + 4) != MSG_TAKE) return fail("expected TAKE");
    uint8_t t[16];
    if (le32(h + 12) < 16 || read_full(fd, t, 16)) return fail("short TAKE");
    take.width = (int)le32(t); take.height = (int)le32(t + 4);
    take.stride = le32(t + 8); take.header_len = le32(t + 12);
    if (take.width < 4 || take.width > MAX_DIM || (take.width & 1) || take.height < 2 || take.height > MAX_DIM
            || take.stride < (size_t)take.width * 2 || take.stride > (size_t)MAX_DIM * 4
            || take.header_len < 16 || take.header_len > MAX_HEADER || le32(h + 12) != 16 + take.header_len)
        return fail("TAKE values out of range");
    take.header = malloc(take.header_len);
    if (!take.header || read_full(fd, take.header, take.header_len)) return fail("short TAKE header");
    if (parse_header()) return fail("TAKE header is not a one-strip DNG of this size");
    /* Camera planes may omit the last row's padding. */
    take.frame_bytes_min = take.stride * (size_t)(take.height - 1) + (size_t)take.width * 2;
    take.frame_bytes_max = take.stride * (size_t)take.height;

    for (int i = 0; i < POOL_FRAMES; i++) {
        pool[i].data = malloc(take.frame_bytes_max);
        if (!pool[i].data) return fail("cannot allocate frame buffers");
        free_list[free_n++] = i;
    }

    pthread_t tid[MAX_THREADS];
    int started = 0;
    const char *error = NULL;
    for (;;) {
        if (read_full(fd, h, 16) || memcmp(h, "KDNG", 4)) { error = "pipe closed without END"; break; }
        uint32_t type = le32(h + 4), index = le32(h + 8), len = le32(h + 12);
        if (type == MSG_END) break;
        if (type != MSG_FRAME || len < take.frame_bytes_min || len > take.frame_bytes_max) { error = "bad FRAME"; break; }
        double w0 = now_ms();
        int s = take_slot();
        double r0 = now_ms();
        if (read_full(fd, pool[s].data, len)) { error = "short FRAME"; break; }
        pool[s].len = len; pool[s].index = index;
        pool[s].wait_ms = r0 - w0; pool[s].read_ms = now_ms() - r0;
        if (!take.table_ready) {                 /* the first frame builds the take's table */
            lj92_frame_t f = { pool[s].data, take.width, take.height, take.stride };
            lj92_take_init(&take.table, &f);
            take.table_ready = 1;
            for (; started < threads; started++) pthread_create(&tid[started], NULL, encoder, NULL);
        }
        enqueue(s);
    }

    pthread_mutex_lock(&lock); ending = 1; pthread_cond_broadcast(&job_ready); pthread_mutex_unlock(&lock);
    for (int i = 0; i < started; i++) pthread_join(tid[i], NULL);
    close(fd);
    if (error) return fail(error);
    say("DONE %ld %llu", frames_done, bytes_done);
    return 0;
}
