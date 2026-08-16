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

    // ---------------------------------------------------------------- Alpha and Beta

    /** Handshake, the first packet in each direction: a packet id and one string. */
    public static final byte HANDSHAKE = 0x02;
    /** Login, the second in each direction, and where the client's unused fields are. */
    public static final byte LOGIN = 0x01;

    /**
     * How long the handshake is, once its length field has arrived, or -1 while it has not.
     * <p>
     * A string on this wire is a two byte count of characters followed by that many UTF-16BE
     * characters, so three bytes are enough to know where the packet ends. That is the boundary the
     * announcement uses in both directions: the payload goes immediately after it, in the place the
     * login packet would otherwise start, and the client knows to look there because 0xFE is not a
     * packet id any server of this era sends.
     * <p>
     * Deliberately not the end of the login packet, which would have been the obvious choice: its
     * trailing fields differ between versions and getting them wrong by one byte would corrupt the
     * connection. The handshake is one string and has been since Alpha.
     */
    public static int handshakeLength(byte[] head, int seen) {
        if (seen < 3) return -1;
        int characters = ((head[1] & 0xFF) << 8) | (head[2] & 0xFF);
        return 3 + characters * 2;
    }

    /**
     * Where the client's login packet keeps the eight bytes nobody reads, counted from the start of
     * that packet, or -1 while the username's length has not arrived.
     * <p>
     * The layout is a packet id, the protocol version, the username, and then the map seed — which
     * the client sends as zero because a client has no seed to send. That is the marker's home: it
     * changes no length, and a server without Loki reads it into a field it then ignores.
     */
    public static int loginSeedOffset(byte[] login, int seen) {
        if (seen < 7) return -1;
        int characters = ((login[5] & 0xFF) << 8) | (login[6] & 0xFF);
        return 1 + 4 + 2 + characters * 2;
    }

    /** The eight bytes written there, chosen so that a zero seed cannot be mistaken for it. */
    public static final byte[] LOGIN_MARKER = {'L', 'o', 'k', 'i', 0x00, 0x01, 0x00, 0x00};

    /** Whether these bytes, at this offset, are the marker rather than a seed. */
    public static boolean isLoginMarker(byte[] bytes, int at) {
        for (int i = 0; i < LOGIN_MARKER.length; i++) {
            if (bytes[at + i] != LOGIN_MARKER[i]) return false;
        }
        return true;
    }

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
