package org.kanaha.camera;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes uncompressed DNG frames at 12 bits per sample. {@code DngCreator} stores each
 * 12-bit sample in 16 bits, a quarter of every file padding; this writer keeps every tag
 * DngCreator wrote (colour matrices, calibration, black and white level, CFA pattern,
 * noise profile, lens-correction opcode lists) by copying them from one DngCreator file
 * per take, and replaces only the image data and the tags that describe it.
 *
 * <p>The template's byte order is kept, so values copied from it need no conversion.
 * Samples are packed as TIFF requires for BitsPerSample 12, most significant bit first,
 * two samples in three bytes, each row starting on a byte boundary. The EXIF and GPS
 * sub-IFDs are not carried over: they are pointers into the template and the raw
 * decoders do not need them.
 */
final class PackedDngWriter {
    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_BITS_PER_SAMPLE = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_STRIP_BYTE_COUNTS = 279;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;

    /** Tags rewritten here, or pointers into the template that cannot be copied. */
    private static final int[] DROPPED = {
            TAG_BITS_PER_SAMPLE, TAG_COMPRESSION, TAG_STRIP_OFFSETS, TAG_ROWS_PER_STRIP,
            TAG_STRIP_BYTE_COUNTS, 322, 323, 324, 325, 330, 34665, 34853};

    final int width;
    final int height;
    final int rowBytes;
    private final byte[] header;   // TIFF header, IFD0 and its values; the image follows

    private static final class Entry {
        final int tag, type, count;
        final byte[] value;        // exactly count * size(type) bytes, in the file's order
        Entry(int tag, int type, int count, byte[] value) {
            this.tag = tag; this.type = type; this.count = count; this.value = value;
        }
    }

    private PackedDngWriter(ByteOrder order, int width, int height, List<Entry> entries) {
        this.width = width;
        this.height = height;
        this.rowBytes = width * 12 / 8;
        long imageBytes = (long) rowBytes * height;

        int n = entries.size() + 5;
        int ifdSize = 2 + 12 * n + 4;
        int valuesStart = 8 + ifdSize;
        int valuesSize = 0;
        for (Entry e : entries) {
            if (e.value.length > 4) valuesSize += (e.value.length + 1) & ~1;   // word aligned
        }
        int dataOffset = (valuesStart + valuesSize + 15) & ~15;

        List<Entry> all = new ArrayList<>(entries);
        all.add(new Entry(TAG_BITS_PER_SAMPLE, TYPE_SHORT, 1, shortBytes(order, 12)));
        all.add(new Entry(TAG_COMPRESSION, TYPE_SHORT, 1, shortBytes(order, 1)));
        all.add(new Entry(TAG_STRIP_OFFSETS, TYPE_LONG, 1, intBytes(order, dataOffset)));
        all.add(new Entry(TAG_ROWS_PER_STRIP, TYPE_LONG, 1, intBytes(order, height)));
        all.add(new Entry(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, 1, intBytes(order, (int) imageBytes)));
        all.sort((a, b) -> Integer.compare(a.tag, b.tag));   // TIFF requires ascending tags

        ByteBuffer h = ByteBuffer.allocate(dataOffset).order(order);
        h.put(order == ByteOrder.BIG_ENDIAN ? (byte) 'M' : (byte) 'I');
        h.put(order == ByteOrder.BIG_ENDIAN ? (byte) 'M' : (byte) 'I');
        h.putShort((short) 42).putInt(8);
        h.putShort((short) n);
        int valueAt = valuesStart;
        for (Entry e : all) {
            h.putShort((short) e.tag).putShort((short) e.type).putInt(e.count);
            if (e.value.length <= 4) {
                byte[] inline = new byte[4];
                System.arraycopy(e.value, 0, inline, 0, e.value.length);   // left-justified
                h.put(inline);
            } else {
                h.putInt(valueAt);
                int entryEnd = h.position();
                h.position(valueAt);
                h.put(e.value);
                h.position(entryEnd);
                valueAt += (e.value.length + 1) & ~1;
            }
        }
        h.putInt(0);   // no further IFD
        this.header = h.array();
    }

    /** The writer for frames shaped like the given DngCreator output. */
    static PackedDngWriter fromTemplate(byte[] dng) {
        ByteOrder order = dng[0] == 'M' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer b = ByteBuffer.wrap(dng).order(order);
        int ifd = b.getInt(4);
        int count = b.getShort(ifd) & 0xffff;
        List<Entry> entries = new ArrayList<>();
        int width = 0, height = 0;
        for (int i = 0; i < count; i++) {
            int e = ifd + 2 + 12 * i;
            int tag = b.getShort(e) & 0xffff;
            int type = b.getShort(e + 2) & 0xffff;
            int n = b.getInt(e + 4);
            int size = n * typeSize(type);
            byte[] value = new byte[size];
            System.arraycopy(dng, size <= 4 ? e + 8 : b.getInt(e + 8), value, 0, size);
            if (tag == TAG_IMAGE_WIDTH) width = readInt(b, type, e + 8);
            if (tag == TAG_IMAGE_LENGTH) height = readInt(b, type, e + 8);
            if (!dropped(tag)) entries.add(new Entry(tag, type, n, value));
        }
        if (width <= 0 || height <= 0 || (width & 1) != 0) {
            throw new IllegalArgumentException("template has no usable size: " + width + "x" + height);
        }
        return new PackedDngWriter(order, width, height, entries);
    }

    /** The TIFF header, IFD0 and its values: everything before the image data. */
    byte[] header() {
        return header.clone();
    }

    /** Bytes in one file: the header plus the packed image. */
    long fileBytes() {
        return header.length + (long) rowBytes * height;
    }

    /**
     * Write one frame. {@code plane} is a RAW_SENSOR plane: 16-bit little-endian samples,
     * {@code rowStride} bytes per row. Thread-safe; the header is never modified.
     */
    void write(OutputStream out, ByteBuffer plane, int rowStride) throws IOException {
        out.write(header);
        ShortBuffer samples = plane.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] row = new short[width];
        byte[] packed = new byte[rowBytes];
        int strideShorts = rowStride / 2;
        for (int y = 0; y < height; y++) {
            samples.position(y * strideShorts);
            samples.get(row, 0, width);
            for (int x = 0, j = 0; x < width; x += 2, j += 3) {
                int p0 = row[x] & 0xfff;
                int p1 = row[x + 1] & 0xfff;
                packed[j] = (byte) (p0 >> 4);
                packed[j + 1] = (byte) ((p0 << 4) | (p1 >> 8));
                packed[j + 2] = (byte) p1;
            }
            out.write(packed);
        }
    }

    private static boolean dropped(int tag) {
        for (int t : DROPPED) if (t == tag) return true;
        return false;
    }

    private static int readInt(ByteBuffer b, int type, int at) {
        return type == TYPE_SHORT ? b.getShort(at) & 0xffff : b.getInt(at);
    }

    private static int typeSize(int type) {
        switch (type) {
            case 3: case 8: return 2;               // SHORT, SSHORT
            case 4: case 9: case 11: return 4;      // LONG, SLONG, FLOAT
            case 5: case 10: case 12: return 8;     // RATIONAL, SRATIONAL, DOUBLE
            default: return 1;                      // BYTE, ASCII, SBYTE, UNDEFINED
        }
    }

    private static byte[] shortBytes(ByteOrder order, int v) {
        return ByteBuffer.allocate(2).order(order).putShort((short) v).array();
    }

    private static byte[] intBytes(ByteOrder order, int v) {
        return ByteBuffer.allocate(4).order(order).putInt(v).array();
    }
}
