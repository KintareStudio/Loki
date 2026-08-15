package org.unmojang.loki.util;

/**
 * Where, in each pre-1.7 protocol, the two holes are.
 * <p>
 * The mechanism is the same everywhere and only these two locations change, which is why they are a
 * table rather than five implementations:
 * <ul>
 *   <li>a <b>marker</b>, written by the client into a field its own packets already carry and
 *       nobody reads. It cannot lengthen the packet, because a server without Loki would take the
 *       extra bytes for the start of the next one and drop the connection.</li>
 *   <li>a <b>payload</b>, appended by the server after one of its packets, and only to a client
 *       that marked itself. Length is free here because the client that receives it is the one that
 *       takes it back out before the game reads a byte of it.</li>
 * </ul>
 *
 * <h2>Why this is safe in both directions</h2>
 * A client without Loki never sets the marker, so it is never sent a payload and sees a protocol
 * that has not changed. A server without Loki is sent a marker in a field it ignores, and answers
 * normally; the client finds no payload and carries on with whatever it was configured with. Every
 * combination degrades to what happens today.
 *
 * <h2>Classic</h2>
 * Both identification packets are 131 bytes: an id, a protocol version, two 64-byte space-padded
 * strings and one trailing byte. The client's trailing byte is unused, and using it to announce an
 * extended client is what CPE has done for years, so servers of that era are known to tolerate it.
 */
public final class LegacyProtocol {
    /** Player and Server Identification alike. */
    public static final byte CLASSIC_IDENTIFICATION = 0x00;
    /** id + version + 64 + 64 + one trailing byte. */
    public static final int CLASSIC_IDENTIFICATION_BYTES = 131;
    /** The trailing byte of the client's identification, which vanilla servers ignore. */
    public static final int CLASSIC_MARKER_OFFSET = 130;

    /**
     * What a Loki client writes there.
     * <p>
     * Not CPE's {@code 0x42}: that means "I understand the extension protocol", which Loki does not
     * implement and must not claim. A server that speaks CPE would start negotiating extensions and
     * the game behind us would understand none of them.
     */
    public static final byte MARKER = 0x4C;

    /**
     * Starts the block a server appends, chosen to be something no packet id can begin with so a
     * client that receives it by accident stops rather than misreads it.
     * <p>
     * Followed by two bytes of length, most significant first, and then that many bytes of UTF-8.
     */
    public static final byte[] PAYLOAD_MAGIC = {(byte) 0xFE, 0x4C, 0x4F, 0x4B};
    public static final int PAYLOAD_HEADER_BYTES = PAYLOAD_MAGIC.length + 2;
    /** Enough for any declaration, and small enough that a corrupt length cannot ask for the heap. */
    public static final int PAYLOAD_MAX_BYTES = 8192;

    private LegacyProtocol() {}

    /** Whether these bytes are the start of the block a server appends for a Loki client. */
    public static boolean startsWithMagic(byte[] bytes, int at, int available) {
        if (available < PAYLOAD_MAGIC.length) return false;
        for (int i = 0; i < PAYLOAD_MAGIC.length; i++) {
            if (bytes[at + i] != PAYLOAD_MAGIC[i]) return false;
        }
        return true;
    }

    /**
     * The block to append after a server packet, or null when there is nothing to say.
     *
     * @param declaration what the client should be told, already in the form the client parses
     */
    public static byte[] payload(String declaration) {
        if (declaration == null || declaration.length() == 0) return null;
        byte[] body;
        try {
            body = declaration.getBytes("UTF-8");
        } catch (Exception e) {
            return null; // No UTF-8 in this JVM, which cannot happen, but not worth a crash
        }
        if (body.length > PAYLOAD_MAX_BYTES) return null;

        byte[] block = new byte[PAYLOAD_HEADER_BYTES + body.length];
        System.arraycopy(PAYLOAD_MAGIC, 0, block, 0, PAYLOAD_MAGIC.length);
        block[PAYLOAD_MAGIC.length] = (byte) (body.length >> 8);
        block[PAYLOAD_MAGIC.length + 1] = (byte) body.length;
        System.arraycopy(body, 0, block, PAYLOAD_HEADER_BYTES, body.length);
        return block;
    }

    /**
     * Reads back what {@link #payload} wrote.
     *
     * @return the declaration, or null when the block is incomplete or malformed. A caller that
     *         gets null must pass the bytes on untouched rather than swallow them.
     */
    public static String declarationOf(byte[] bytes, int at, int available) {
        if (!startsWithMagic(bytes, at, available)) return null;
        if (available < PAYLOAD_HEADER_BYTES) return null;

        int length = ((bytes[at + PAYLOAD_MAGIC.length] & 0xFF) << 8)
                | (bytes[at + PAYLOAD_MAGIC.length + 1] & 0xFF);
        if (length <= 0 || length > PAYLOAD_MAX_BYTES) return null;
        if (available < PAYLOAD_HEADER_BYTES + length) return null;

        try {
            return new String(bytes, at + PAYLOAD_HEADER_BYTES, length, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** How many bytes the whole block occupies, so a reader knows what to skip. */
    public static int payloadLength(byte[] bytes, int at, int available) {
        if (!startsWithMagic(bytes, at, available) || available < PAYLOAD_HEADER_BYTES) return -1;
        int length = ((bytes[at + PAYLOAD_MAGIC.length] & 0xFF) << 8)
                | (bytes[at + PAYLOAD_MAGIC.length + 1] & 0xFF);
        if (length <= 0 || length > PAYLOAD_MAX_BYTES) return -1;
        return available < PAYLOAD_HEADER_BYTES + length ? -1 : PAYLOAD_HEADER_BYTES + length;
    }
}
