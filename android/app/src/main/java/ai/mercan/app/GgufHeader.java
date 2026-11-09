package ai.mercan.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads just enough of a .mercan (GGUF v3) header to identify the model before it is
 * downloaded or imported: the magic, {@code general.architecture} and {@code mercan.format}.
 * Works on a prefix of the file; keys beyond the prefix are simply reported as unknown.
 */
final class GgufHeader {
    /** Bytes fetched/peeked from the start of a model file for {@link #parse}. */
    static final int PROBE_BYTES = 256 * 1024;

    static final class Info {
        String architecture; // null when not within the probed prefix
        String mercanFormat; // null when not within the probed prefix (it is often stored late)
    }

    private static final int T_UINT8 = 0, T_INT8 = 1, T_UINT16 = 2, T_INT16 = 3, T_UINT32 = 4, T_INT32 = 5,
            T_FLOAT32 = 6, T_BOOL = 7, T_STRING = 8, T_ARRAY = 9, T_UINT64 = 10, T_INT64 = 11, T_FLOAT64 = 12;

    private GgufHeader() {}

    /** @throws IllegalArgumentException if the bytes are not a GGUF v3 header. */
    static Info parse(byte[] data, int length) {
        ByteBuffer b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        if (length < 24 || data[0] != 'G' || data[1] != 'G' || data[2] != 'U' || data[3] != 'F') {
            throw new IllegalArgumentException("not a GGUF/.mercan file");
        }
        b.position(4);
        int version = b.getInt();
        if (version != 3) throw new IllegalArgumentException("unsupported GGUF version " + version);
        b.getLong(); // tensor count
        long kvCount = b.getLong();
        Info info = new Info();
        try {
            for (long i = 0; i < kvCount && (info.architecture == null || info.mercanFormat == null); i++) {
                String key = readString(b);
                int type = b.getInt();
                if (type == T_STRING && ("general.architecture".equals(key) || "mercan.format".equals(key))) {
                    String value = readString(b);
                    if ("general.architecture".equals(key)) info.architecture = value;
                    else info.mercanFormat = value;
                } else {
                    skipValue(b, type);
                }
            }
        } catch (RuntimeException truncated) {
            // Ran past the probed prefix (e.g. a large tokenizer array); what we have is enough.
        }
        return info;
    }

    private static String readString(ByteBuffer b) {
        long n = b.getLong();
        if (n < 0 || n > b.remaining()) throw new IndexOutOfBoundsException("string past prefix");
        byte[] s = new byte[(int) n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private static void skipValue(ByteBuffer b, int type) {
        switch (type) {
            case T_UINT8: case T_INT8: case T_BOOL: skip(b, 1); break;
            case T_UINT16: case T_INT16: skip(b, 2); break;
            case T_UINT32: case T_INT32: case T_FLOAT32: skip(b, 4); break;
            case T_UINT64: case T_INT64: case T_FLOAT64: skip(b, 8); break;
            case T_STRING: skip(b, b.getLong()); break;
            case T_ARRAY: {
                int elemType = b.getInt();
                long count = b.getLong();
                int fixed = fixedSize(elemType);
                if (fixed > 0) {
                    skip(b, count * fixed);
                } else {
                    for (long i = 0; i < count; i++) skipValue(b, elemType);
                }
                break;
            }
            default: throw new IllegalArgumentException("bad GGUF value type " + type);
        }
    }

    private static int fixedSize(int type) {
        switch (type) {
            case T_UINT8: case T_INT8: case T_BOOL: return 1;
            case T_UINT16: case T_INT16: return 2;
            case T_UINT32: case T_INT32: case T_FLOAT32: return 4;
            case T_UINT64: case T_INT64: case T_FLOAT64: return 8;
            default: return 0;
        }
    }

    private static void skip(ByteBuffer b, long n) {
        if (n < 0 || n > b.remaining()) throw new IndexOutOfBoundsException("value past prefix");
        b.position(b.position() + (int) n);
    }
}
