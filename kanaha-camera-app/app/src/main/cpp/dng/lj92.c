/*
 * Kanaha Camera Control System
 * Lossless JPEG (DNG Compression 7) encoder for 12-bit Bayer RAW frames
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * WHY LOSSLESS JPEG
 *   It is the compression CinemaDNG cameras use, DNG "Compression = 7", and
 *   every raw developer (darktable, RawTherapee, Resolve, ffmpeg) reads it.
 *   Lossless means a decoder gets back exactly the samples the sensor produced.
 *
 * HOW IT WORKS, IN THREE STEPS
 *   1. Predict each sample from a neighbour; neighbours are usually close.
 *   2. Keep only the difference (sample - prediction); differences cluster near 0.
 *   3. Write each difference with a variable-length code: common small ones get
 *      short codes, rare large ones long codes (Huffman coding).
 *   A smooth wall compresses well; noise and fine detail compress less. On a lit
 *   studio scene a frame came to 44% of its 12-bit packed size.
 *
 * THE BAYER TRICK
 *   A raw frame is a colour mosaic: each row alternates two colours. A pixel's
 *   left neighbour is a different colour and predicts badly; the one two columns
 *   over is the same colour. So the frame is coded as two interleaved components
 *   (even and odd columns), each predicted from its own left neighbour, which is
 *   how DNG files store mosaic data in lossless JPEG.
 *
 * WHERE THE SPEED COMES FROM
 *   About one Pixel 10 Pro XL core compresses 30 frames a second (4080x3072):
 *   - one Huffman table per take, built from the first frame, so later frames
 *     need a single pass;
 *   - a lookup table holding, for every possible difference, the finished bit
 *     pattern and its length, so the inner loop is subtract, look up, append;
 *   - the first row and first two columns coded separately, so the main loop has
 *     no edge checks;
 *   - a bit writer that emits 32 bits at a time, byte by byte only when one of
 *     them is 0xFF (JPEG requires a 0x00 after each 0xFF in coded data).
 */
#include "lj92.h"

#include <stdlib.h>
#include <string.h>

/* ---- bit writer ---------------------------------------------------------- */

static inline void put_byte_stuffed(lj92_out_t *w, uint8_t b) {
    w->buf[w->len++] = b;
    if (b == 0xFF) w->buf[w->len++] = 0x00;
}

/* Append the low n bits of v (n <= 32). The caller has made room (ensure()). */
static inline void put_bits(lj92_out_t *w, uint32_t v, int n) {
    w->acc = (w->acc << n) | v;
    w->nbits += n;
    if (w->nbits >= 32) {
        uint32_t word = (uint32_t)(w->acc >> (w->nbits - 32));
        w->nbits -= 32;
        uint32_t inv = ~word;   /* has a zero byte exactly where word has 0xFF */
        if (((inv - 0x01010101u) & ~inv & 0x80808080u) == 0) {
            uint8_t *p = w->buf + w->len;
            p[0] = (uint8_t)(word >> 24); p[1] = (uint8_t)(word >> 16);
            p[2] = (uint8_t)(word >> 8);  p[3] = (uint8_t)word;
            w->len += 4;
        } else {
            put_byte_stuffed(w, (uint8_t)(word >> 24)); put_byte_stuffed(w, (uint8_t)(word >> 16));
            put_byte_stuffed(w, (uint8_t)(word >> 8));  put_byte_stuffed(w, (uint8_t)word);
        }
    }
}

/* Pad the last byte with 1-bits, as JPEG specifies, and write what is left. */
static void flush_bits(lj92_out_t *w) {
    while (w->nbits >= 8) { w->nbits -= 8; put_byte_stuffed(w, (uint8_t)(w->acc >> w->nbits)); }
    if (w->nbits > 0) put_byte_stuffed(w, (uint8_t)((w->acc << (8 - w->nbits)) | (0xFF >> w->nbits)));
    w->nbits = 0;
}

static inline void put_raw(lj92_out_t *w, uint8_t b) { w->buf[w->len++] = b; }

/* Room for `more` bytes. A coded sample is at most 28 bits, 7 bytes once stuffed,
 * so callers ask for a row's worth at a time. */
static int ensure(lj92_out_t *w, size_t more) {
    if (w->len + more <= w->cap) return 0;
    size_t cap = (w->len + more) * 3 / 2;
    uint8_t *b = realloc(w->buf, cap);
    if (!b) return -1;
    w->buf = b; w->cap = cap;
    return 0;
}

/* "SSSS": how many bits a difference needs, 0 for 0. JPEG codes this category
 * with Huffman, then appends the difference itself in SSSS bits. */
static inline int ssss_of(int d) {
    if (d == 0) return 0;
    unsigned a = d < 0 ? (unsigned)-d : (unsigned)d;
    return 32 - __builtin_clz(a);
}

/* ---- Huffman table (ITU T.81 Annex K.2) ---------------------------------- */

static void huff_build(const long freq_in[17], lj92_huff_t *h) {
    long freq[18]; int codesize[18], others[18];
    /* +1: every SSSS gets a code, so a later frame with a rarer difference than
     * the first frame had still encodes. */
    for (int i = 0; i < 17; i++) freq[i] = freq_in[i] + 1;
    freq[17] = 1;   /* placeholder that takes the all-ones code JPEG forbids; dropped below */
    for (int i = 0; i < 18; i++) { codesize[i] = 0; others[i] = -1; }
    for (;;) {      /* repeatedly merge the two least frequent entries */
        int v1 = -1, v2 = -1;
        for (int i = 0; i < 18; i++) if (freq[i] > 0 && (v1 < 0 || freq[i] <= freq[v1])) v1 = i;
        for (int i = 0; i < 18; i++) if (freq[i] > 0 && i != v1 && (v2 < 0 || freq[i] <= freq[v2])) v2 = i;
        if (v2 < 0) break;
        freq[v1] += freq[v2]; freq[v2] = 0;
        codesize[v1]++; while (others[v1] >= 0) { v1 = others[v1]; codesize[v1]++; }
        others[v1] = v2;
        codesize[v2]++; while (others[v2] >= 0) { v2 = others[v2]; codesize[v2]++; }
    }
    int cnt[40] = {0};
    for (int i = 0; i < 18; i++) if (codesize[i]) cnt[codesize[i]]++;
    for (int i = 39; i > 16; i--) {   /* JPEG caps codes at 16 bits */
        while (cnt[i] > 0) {
            int j = i - 2; while (cnt[j] == 0) j--;
            cnt[i] -= 2; cnt[i - 1]++; cnt[j + 1] += 2; cnt[j]--;
        }
    }
    int i = 16; while (cnt[i] == 0) i--; cnt[i]--;   /* drop the placeholder */
    memset(h->bits, 0, sizeof h->bits);
    for (int l = 1; l <= 16; l++) h->bits[l] = (uint8_t)cnt[l];
    h->nvals = 0;
    for (int l = 1; l <= 32; l++)
        for (int s = 0; s < 17; s++) if (codesize[s] == l) h->vals[h->nvals++] = (uint8_t)s;
    memset(h->size, 0, sizeof h->size);
    int k = 0; uint16_t c = 0;        /* consecutive codes within each length */
    for (int l = 1; l <= 16; l++) {
        for (int n = 0; n < h->bits[l]; n++, k++) { h->code[h->vals[k]] = c++; h->size[h->vals[k]] = (uint8_t)l; }
        c <<= 1;
    }
}

/* ---- frames --------------------------------------------------------------- */

static inline const uint16_t *row_of(const lj92_frame_t *f, int y) {
    return (const uint16_t *)(f->data + (size_t)y * f->stride_bytes);   /* little-endian host */
}

void lj92_take_init(lj92_take_t *t, const lj92_frame_t *f) {
    long freq[17] = {0};
    for (int y = 0; y < f->height; y++) {
        const uint16_t *row = row_of(f, y), *up = y ? row_of(f, y - 1) : NULL;
        for (int x = 0; x < f->width; x++) {
            int v = row[x] & 0xFFF;
            int pred = x >= 2 ? (row[x - 2] & 0xFFF) : (y ? (up[x] & 0xFFF) : 2048);
            freq[ssss_of(v - pred)]++;
        }
    }
    huff_build(freq, &t->h);
    for (int d = -LJ92_MAXDIFF; d <= LJ92_MAXDIFF; d++) {
        int s = ssss_of(d);
        /* JPEG writes a negative difference as (d - 1) in s bits: one's complement */
        uint32_t extra = s ? ((uint32_t)(d < 0 ? d - 1 : d) & ((1u << s) - 1)) : 0;
        t->pattern[d + LJ92_MAXDIFF] = ((uint32_t)t->h.code[s] << s) | extra;
        t->length[d + LJ92_MAXDIFF] = (uint8_t)(t->h.size[s] + s);
    }
}

static inline void put_diff(lj92_out_t *w, const lj92_take_t *t, int d) {
    put_bits(w, t->pattern[d + LJ92_MAXDIFF], t->length[d + LJ92_MAXDIFF]);
}

int lj92_encode(const lj92_take_t *t, const lj92_frame_t *f, lj92_out_t *w) {
    int width = f->width, height = f->height, cw = width / 2;
    w->len = 0; w->acc = 0; w->nbits = 0;
    if (ensure(w, 64 + 16 + (size_t)width * 7)) return -1;

    put_raw(w, 0xFF); put_raw(w, 0xD8);                      /* SOI: start of image */
    /* SOF3: lossless, 12-bit precision, height x (width/2), two components */
    const uint8_t sof[] = {0xFF, 0xC3, 0, 14, 12, (uint8_t)(height >> 8), (uint8_t)height,
                           (uint8_t)(cw >> 8), (uint8_t)cw, 2, 1, 0x11, 0, 2, 0x11, 0};
    for (size_t i = 0; i < sizeof sof; i++) put_raw(w, sof[i]);
    /* DHT: the Huffman table, shared by both components */
    int dhtlen = 2 + 1 + 16 + t->h.nvals;
    put_raw(w, 0xFF); put_raw(w, 0xC4); put_raw(w, (uint8_t)(dhtlen >> 8)); put_raw(w, (uint8_t)dhtlen);
    put_raw(w, 0x00);
    for (int l = 1; l <= 16; l++) put_raw(w, t->h.bits[l]);
    for (int k = 0; k < t->h.nvals; k++) put_raw(w, t->h.vals[k]);
    /* SOS: start of scan; predictor 1 = the sample to the left (same component) */
    const uint8_t sos[] = {0xFF, 0xDA, 0, 10, 2, 1, 0x00, 2, 0x00, 1, 0, 0};
    for (size_t i = 0; i < sizeof sos; i++) put_raw(w, sos[i]);

    /* First row: each component's first sample is predicted as 2048 (half the
     * 12-bit range), the rest from the left. Later rows: the first sample of each
     * component from the one above, the rest from the left. */
    const uint16_t *up = NULL;
    for (int y = 0; y < height; y++) {
        if (ensure(w, (size_t)width * 7)) return -1;
        const uint16_t *row = row_of(f, y);
        put_diff(w, t, (row[0] & 0xFFF) - (up ? (up[0] & 0xFFF) : 2048));
        put_diff(w, t, (row[1] & 0xFFF) - (up ? (up[1] & 0xFFF) : 2048));
        for (int x = 2; x < width; x++) put_diff(w, t, (row[x] & 0xFFF) - (row[x - 2] & 0xFFF));
        up = row;
    }

    if (ensure(w, 16)) return -1;
    flush_bits(w);
    put_raw(w, 0xFF); put_raw(w, 0xD9);                      /* EOI: end of image */
    return 0;
}
