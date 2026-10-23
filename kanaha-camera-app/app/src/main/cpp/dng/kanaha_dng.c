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
 * HOW FRAMES ARRIVE: SHARED MEMORY, NOT COPIES
 *   The app copies each camera frame once, into one of N shared-memory buffers
 *   (memfd) it made for the take. It hands this program those buffers' file
 *   descriptors once, at the start, over a Unix domain socket (SCM_RIGHTS, the
 *   standard way to pass descriptors between processes). This program maps the
 *   same memory and compresses each frame where it lies: 25 MB a frame never
 *   travels through a pipe. Measured on a Pixel 10 Pro XL, pushing frames
 *   through a pipe instead took over 33 ms a frame once the phone warmed, and
 *   the take dropped frames.
 *
 *   argv[1] is the socket's path, in the app's private cache directory. This
 *   program listens there, prints READY, and serves one connection. Messages,
 *   all little-endian, each a 16-byte header then a payload:
 *
 *     header:  "KDNG"  uint32 type  uint32 index  uint32 payload_length
 *
 *     type 1 TAKE   once, first; index = how many buffers (N), whose
 *                   descriptors come attached to this message. Payload:
 *                   uint32 width, height, stride_bytes, buffer_bytes, header_len,
 *                   then header_len bytes of DNG header (everything before the
 *                   image data, from PackedDngWriter: the camera's own tags)
 *     type 2 FRAME  index = frame number; payload: uint32 slot, uint32 bytes.
 *                   The frame's RAW_SENSOR samples (16-bit little-endian, rows
 *                   stride_bytes apart) are in buffer `slot`.
 *     type 3 END    no more frames; finish what is queued and exit
 *
 *   Each buffer arrives holding a marker, "KDNGBUF" and its slot number, written
 *   by the app: checking it after mapping proves this program sees the app's
 *   memory, whatever kind of shared memory Android used (ashmem on a Pixel).
 *
 *   A slot travels back (uint32) whenever the app may use that buffer: once for
 *   every buffer after the markers check out - the app touches none before, so
 *   no frame can overwrite a marker first - and again each time a frame's file
 *   is written. Anything malformed, or a size that does
 *   not match the take, ends the take with an error: the input comes from the
 *   app, but is checked all the same.
 *
 * WHAT IT WRITES
 *   argv[2]/frame_NNNNNN.dng for each frame: the take's header with Compression
 *   set to 7 (lossless JPEG) and the image's byte count, then the compressed
 *   image. One line per event on stdout, which the app reads:
 *
 *     READY                        listening, waiting for the app to connect
 *     OK <index> <bytes> <compress_ms> <write_ms> <queued_ms>
 *                                  frame written; queued_ms = how long it waited
 *                                  for a free encoder thread
 *     ERR <message>                a frame or the take failed
 *     DONE <frames> <bytes>        END received, every frame written
 *
 * THREADS
 *   The main thread reads messages and queues frames; argv[3] encoder threads
 *   (3 by default) compress, write and release them. The queue holds at most
 *   N frames, one per buffer, so this program never grows: if it falls behind,
 *   the app runs out of free buffers and counts dropped frames.
 */
#include "lj92.h"

#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MSG_TAKE  1
#define MSG_FRAME 2
#define MSG_END   3

#define MAX_DIM       16384
#define MAX_HEADER    (1 << 20)
#define MAX_BUFFERS   64           /* descriptors in one message */
#define MAX_THREADS   8

/* ---- output: one line per event, from any thread ------------------------ */

static pthread_mutex_t out_lock = PTHREAD_MUTEX_INITIALIZER;

static void say(const char *fmt, ...) {
    va_list ap;
    pthread_mutex_lock(&out_lock);
    va_start(ap, fmt); vprintf(fmt, ap); va_end(ap);
    putchar('\n'); fflush(stdout);
    pthread_mutex_unlock(&out_lock);
}

static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1e3 + t.tv_nsec / 1e6; }

/* ---- the take: what TAKE told us, shared read-only by the encoders ------ */

typedef struct {
    int width, height;
    size_t stride, buffer_bytes, frame_bytes_min;
    uint8_t *header; size_t header_len;
    size_t compression_at, byte_count_at;   /* offsets of the values to patch */
    int big_endian;
    const uint8_t *buffer[MAX_BUFFERS];     /* the app's frame buffers, mapped */
    int nbuffers;
    lj92_take_t table;                      /* built from the first frame */
    const char *dir;
    int sock;                               /* the app's connection */
} take_t;

static take_t take;

/* ---- the job queue: frames waiting for an encoder ----------------------- */

typedef struct { uint32_t slot, index; double queued_at; } job_t;

static job_t queue[MAX_BUFFERS];
static int q_head, q_n, ending;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t job_ready = PTHREAD_COND_INITIALIZER;
static pthread_mutex_t sock_lock = PTHREAD_MUTEX_INITIALIZER;   /* encoders reply on one socket */
static long frames_done; static unsigned long long bytes_done;

/* Queue a frame. The app has at most nbuffers frames outstanding, so the queue
 * cannot overflow unless the app reuses a slot it has not been given back. */
static int enqueue(job_t j) {
    pthread_mutex_lock(&lock);
    int ok = q_n < take.nbuffers;
    if (ok) { queue[(q_head + q_n++) % MAX_BUFFERS] = j; pthread_cond_signal(&job_ready); }
    pthread_mutex_unlock(&lock);
    return ok;
}

/* Next frame to encode; 0 once END has come and the queue is empty. */
static int next_job(job_t *j) {
    pthread_mutex_lock(&lock);
    while (q_n == 0 && !ending) pthread_cond_wait(&job_ready, &lock);
    int got = q_n > 0;
    if (got) { *j = queue[q_head]; q_head = (q_head + 1) % MAX_BUFFERS; q_n--; }
    pthread_mutex_unlock(&lock);
    return got;
}

/* ---- socket I/O ----------------------------------------------------------- */

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

static int write_full(int fd, const void *buf, size_t n) {
    const uint8_t *p = buf;
    while (n > 0) {
        ssize_t w = write(fd, p, n);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) return -1;
        p += w; n -= (size_t)w;
    }
    return 0;
}

/* Read the 16-byte TAKE header, collecting any descriptors attached to it. */
static int read_header_with_fds(int fd, uint8_t h[16], int fds[MAX_BUFFERS], int *nfds) {
    *nfds = 0;
    size_t got = 0;
    while (got < 16) {
        char control[CMSG_SPACE(sizeof(int) * MAX_BUFFERS)];
        struct iovec iov = { h + got, 16 - got };
        struct msghdr msg = {0};
        msg.msg_iov = &iov; msg.msg_iovlen = 1;
        msg.msg_control = control; msg.msg_controllen = sizeof control;
        ssize_t r = recvmsg(fd, &msg, 0);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0 || (msg.msg_flags & MSG_CTRUNC)) return -1;
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
            int n = (int)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            for (int i = 0; i < n && *nfds < MAX_BUFFERS; i++) memcpy(&fds[(*nfds)++], CMSG_DATA(c) + i * sizeof(int), sizeof(int));
        }
        got += (size_t)r;
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

static void *encoder(void *arg) {
    (void)arg;
    lj92_out_t out = {0};
    uint8_t *header = malloc(take.header_len);   /* this thread's copy, patched per frame */
    memcpy(header, take.header, take.header_len);
    put16(header + take.compression_at, 7);      /* Compression = lossless JPEG */

    for (job_t j; next_job(&j); ) {
        double t0 = now_ms();
        lj92_frame_t f = { take.buffer[j.slot], take.width, take.height, take.stride };
        int ok = lj92_encode(&take.table, &f, &out) == 0;
        double t1 = now_ms();
        char path[1024];
        snprintf(path, sizeof path, "%s/frame_%06u.dng", take.dir, j.index);
        if (ok) {
            put32(header + take.byte_count_at, (uint32_t)out.len);
            FILE *fp = fopen(path, "wb");
            ok = fp && fwrite(header, 1, take.header_len, fp) == take.header_len
                    && fwrite(out.buf, 1, out.len, fp) == out.len;
            if (fp && fclose(fp) != 0) ok = 0;
        }
        /* The frame is compressed (or failed): give the buffer back either way. */
        uint8_t slot[4] = { (uint8_t)j.slot, (uint8_t)(j.slot >> 8), (uint8_t)(j.slot >> 16), (uint8_t)(j.slot >> 24) };
        pthread_mutex_lock(&sock_lock);
        write_full(take.sock, slot, 4);
        pthread_mutex_unlock(&sock_lock);

        if (!ok) { say("ERR %u could not write %s: %s", j.index, path, strerror(errno)); continue; }
        size_t bytes = take.header_len + out.len;
        pthread_mutex_lock(&lock); frames_done++; bytes_done += bytes; pthread_mutex_unlock(&lock);
        say("OK %u %zu %.0f %.0f %.0f", j.index, bytes, t1 - t0, now_ms() - t1, t0 - j.queued_at);
    }
    free(out.buf); free(header);
    return NULL;
}

/* ---- main: listen, read the take, queue frames for the encoders --------- */

static int fail(const char *msg) { say("ERR %s", msg); return 1; }

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s SOCKET OUT_DIR [THREADS]\n", argv[0]); return 2; }
    int threads = argc > 3 ? atoi(argv[3]) : 3;
    if (threads < 1 || threads > MAX_THREADS) return fail("THREADS must be 1 to 8");
    take.dir = argv[2];

    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    if (strlen(argv[1]) >= sizeof addr.sun_path) return fail("socket path too long");
    strcpy(addr.sun_path, argv[1]);
    unlink(argv[1]);
    int listener = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listener < 0 || bind(listener, (struct sockaddr *)&addr, sizeof addr) || listen(listener, 1))
        return fail("cannot listen on the socket");
    say("READY");
    take.sock = accept(listener, NULL, NULL);
    close(listener);
    unlink(argv[1]);                              /* one connection; nobody else can join */
    if (take.sock < 0) return fail("accept failed");

    uint8_t h[16];
    int fds[MAX_BUFFERS], nfds;
    if (read_header_with_fds(take.sock, h, fds, &nfds) || memcmp(h, "KDNG", 4) || le32(h + 4) != MSG_TAKE)
        return fail("expected TAKE");
    take.nbuffers = (int)le32(h + 8);
    uint8_t t[20];
    if (take.nbuffers < 1 || take.nbuffers > MAX_BUFFERS || nfds != take.nbuffers)
        return fail("TAKE must carry 1 to 64 buffers, one descriptor each");
    if (le32(h + 12) < 20 || read_full(take.sock, t, 20)) return fail("short TAKE");
    take.width = (int)le32(t); take.height = (int)le32(t + 4);
    take.stride = le32(t + 8); take.buffer_bytes = le32(t + 12); take.header_len = le32(t + 16);
    take.frame_bytes_min = take.stride * (size_t)(take.height - 1) + (size_t)take.width * 2;
    if (take.width < 4 || take.width > MAX_DIM || (take.width & 1) || take.height < 2 || take.height > MAX_DIM
            || take.stride < (size_t)take.width * 2 || take.stride > (size_t)MAX_DIM * 4
            || take.buffer_bytes < take.frame_bytes_min
            || take.header_len < 16 || take.header_len > MAX_HEADER || le32(h + 12) != 20 + take.header_len)
        return fail("TAKE values out of range");
    take.header = malloc(take.header_len);
    if (!take.header || read_full(take.sock, take.header, take.header_len)) return fail("short TAKE header");
    if (parse_header()) return fail("TAKE header is not a one-strip DNG of this size");
    for (int i = 0; i < take.nbuffers; i++) {
        void *m = mmap(NULL, take.buffer_bytes, PROT_READ, MAP_SHARED, fds[i], 0);
        close(fds[i]);
        if (m == MAP_FAILED) return fail("cannot map a frame buffer");
        take.buffer[i] = m;
        if (memcmp(m, "KDNGBUF", 7) != 0 || ((const uint8_t *)m)[7] != (uint8_t)i)
            return fail("a frame buffer does not hold the app's marker");
    }
    for (int i = 0; i < take.nbuffers; i++) {     /* every buffer checked: the app may use them */
        uint8_t slot[4] = { (uint8_t)i, (uint8_t)(i >> 8), 0, 0 };
        if (write_full(take.sock, slot, 4)) return fail("cannot release the buffers");
    }

    pthread_t tid[MAX_THREADS];
    int started = 0, have_table = 0;
    const char *error = NULL;
    for (;;) {
        if (read_full(take.sock, h, 16) || memcmp(h, "KDNG", 4)) { error = "connection closed without END"; break; }
        uint32_t type = le32(h + 4), index = le32(h + 8), len = le32(h + 12);
        if (type == MSG_END) break;
        uint8_t p[8];
        if (type != MSG_FRAME || len != 8 || read_full(take.sock, p, 8)) { error = "bad FRAME"; break; }
        uint32_t slot = le32(p), bytes = le32(p + 4);
        if (slot >= (uint32_t)take.nbuffers || bytes < take.frame_bytes_min || bytes > take.buffer_bytes) { error = "FRAME slot or size out of range"; break; }
        if (!have_table) {                        /* the first frame builds the take's table */
            lj92_frame_t f = { take.buffer[slot], take.width, take.height, take.stride };
            lj92_take_init(&take.table, &f);
            have_table = 1;
            for (; started < threads; started++) pthread_create(&tid[started], NULL, encoder, NULL);
        }
        if (!enqueue((job_t){ slot, index, now_ms() })) { error = "more frames queued than buffers"; break; }
    }

    pthread_mutex_lock(&lock); ending = 1; pthread_cond_broadcast(&job_ready); pthread_mutex_unlock(&lock);
    for (int i = 0; i < started; i++) pthread_join(tid[i], NULL);
    close(take.sock);
    if (error) return fail(error);
    say("DONE %ld %llu", frames_done, bytes_done);
    return 0;
}
