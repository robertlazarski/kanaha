/*
 * Kanaha Camera Control System
 * Lossless JPEG (DNG Compression 7) encoder for 12-bit Bayer RAW frames
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2025-2026 Robert Lazarski
 *
 * Used by kanaha_dng, the helper process that compresses 12-bit RAW takes
 * (see kanaha_dng.c). Plain C, no Android APIs, so it also builds and is
 * checked on a desktop.
 */
#ifndef KANAHA_LJ92_H
#define KANAHA_LJ92_H

#include <stddef.h>
#include <stdint.h>

#define LJ92_MAXDIFF 4095        /* 12-bit samples: differences are -4095..4095 */

/* Huffman table: written into each frame, so any decoder can read it. */
typedef struct {
    uint8_t bits[17];            /* bits[L] = how many codes are L bits long */
    uint8_t vals[17];            /* the SSSS values in code order */
    int nvals;
    uint16_t code[17];           /* code[s] = Huffman code for SSSS s */
    uint8_t size[17];            /* size[s] = its length in bits */
} lj92_huff_t;

/* Per-take state: one table built from the take's first frame, reused for the
 * rest, so every later frame is coded in a single pass. Read-only once built,
 * so encoder threads share it. */
typedef struct {
    lj92_huff_t h;
    uint32_t pattern[2 * LJ92_MAXDIFF + 1];   /* finished bits for difference d, at [d + MAXDIFF] */
    uint8_t length[2 * LJ92_MAXDIFF + 1];     /* and how many bits */
} lj92_take_t;

/* Output buffer for one compressed frame; grows if a frame needs more room. */
typedef struct {
    uint8_t *buf;
    size_t len, cap;
    uint64_t acc;                /* bits not yet written, right-aligned */
    int nbits;                   /* how many bits are in acc */
} lj92_out_t;

/*
 * A frame: width x height samples, 12 significant bits each, in 16-bit
 * little-endian words (Camera2 RAW_SENSOR), rows stride_bytes apart.
 */
typedef struct {
    const uint8_t *data;
    int width, height;
    size_t stride_bytes;
} lj92_frame_t;

/* Build the take's table from its first frame. */
void lj92_take_init(lj92_take_t *take, const lj92_frame_t *frame);

/* Compress one frame into out (out->len is the JPEG's size). 0, or -1 if out
 * could not grow. */
int lj92_encode(const lj92_take_t *take, const lj92_frame_t *frame, lj92_out_t *out);

#endif /* KANAHA_LJ92_H */
