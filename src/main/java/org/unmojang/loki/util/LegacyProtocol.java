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
        if (seen < 4) return -1;
        // From 1.3 the handshake is not one string any more — it opens with a protocol version
        // byte, then the username, the host and the port — and reading it as one walks off the end
        // of the packet. It is told apart here rather than left to fail later: the high byte of a
        // string's length can only be zero for a name, so anything else there is not this shape.
        if (head[1] != 0) return -2;
        int characters = head[2] & 0xFF;
        if (characters == 0) return 3;
        return 3 + characters * (isWide(head) ? 2 : 1);
    }

    /**
     * Whether this client writes two bytes a character.
     * <p>
     * It did not always: up to and including the early Alphas a string was written the way
     * {@code DataOutputStream.writeUTF} writes one, a byte a character for anything ASCII, and only
     * later did it become UTF-16. Which one this is decides where every packet after it ends, so it
     * is read off the wire rather than assumed — the first character of a name is ASCII, so under
     * UTF-16 the byte after the length is the zero half of it, and under UTF-8 it is the letter.
     */
    public static boolean isWide(byte[] head) {
        return head[3] == 0;
    }


    // ------------------------------------------------- which shape a login packet has

    /** This version has no eight bytes to spare, so it gets no marker. */
    public static final int STRINGS_NONE = -1;
    /** The eight zero bytes follow the username directly. */
    public static final int STRINGS_ONE = 1;
    /** A second string comes between the username and them. */
    public static final int STRINGS_TWO = 2;
    /**
     * There is no seed at all, and the eight bytes are the password's own.
     * <p>
     * The earliest Alpha login packet ends after the password, so there is nothing behind it to
     * write into. The field itself is the space: the client sends the literal {@code Password} —
     * eight characters, the same in every version of this range — and the server reads it with an
     * uncapped {@code readUTF} and never looks at it again. That was read out of a0.1.2_01, a0.1.4
     * and a0.2.0 rather than assumed; in all three the handler takes the password as an argument
     * and the argument is never loaded.
     * <p>
     * Being exactly eight characters is what makes this the same mechanism as the seed rather than
     * a second one: the marker replaces them in place, the string keeps its count, and the packet
     * keeps its length.
     */
    public static final int STRINGS_PASSWORD = 3;

    /** What the client of that range puts in the field, and the only thing this will replace. */
    public static final String PASSWORD = "Password";

    /** What replaces it. Printable throughout, since it travels as a string rather than a seed. */
    public static final byte[] PASSWORD_MARKER = {'L', 'o', 'k', 'i', '0', '0', '0', '1'};

    /** a1.1.2_01, the last version before the login packet grew a seed. */
    public static final int PROTOCOL_A1_1_2 = 2;
    /** a1.0.15 and a1.0.16, the numbering before a1.0.17 restarted it at one. */
    public static final int PROTOCOL_A1_0_15 = 13;
    public static final int PROTOCOL_A1_0_16 = 14;
    /** a1.2.0, the first version whose login packet has eight spare bytes at all. */
    public static final int PROTOCOL_A1_2_0 = 3;
    /** b1.4_01, the last one that still sent a password string before them. */
    public static final int PROTOCOL_B1_4_01 = 10;
    /** b1.5, where the password went away, the seed moved up, and strings became UTF-16. */
    public static final int PROTOCOL_B1_5 = 11;
    /** 1.1, the last version whose seed follows the username directly. */
    public static final int PROTOCOL_1_1 = 23;
    /** 1.2.1, where a level type took the second string's place. */
    public static final int PROTOCOL_1_2_1 = 28;
    /** 1.2.5, the last version with a username in its login packet. */
    public static final int PROTOCOL_1_2_5 = 29;

    /**
     * How many strings a login packet of this protocol carries before its eight unused bytes.
     * <p>
     * Every number here was read off the wire from the version's own client by
     * {@code scripts/login-probe.sh}, and that is the point: the shape does not change once per era
     * and the protocol version does not increase in step with the version. Between b1.5 and 1.1 the
     * map seed follows the username; before that a password string comes first; in 1.2 it is a
     * level type that does, followed by two zero ints occupying exactly the same eight bytes. That
     * last one is why guessing was not good enough — reading 1.2 as if it were 1.1 puts the marker
     * into a string's length field, and the server then waits for a name thirty-nine thousand
     * characters long that is never coming.
     * <p>
     * The encoding is asked for as well as the version because the numbers repeat: a1.0.16 is
     * protocol 14 and so is b1.7.3. They are told apart by how they write a string, which changed
     * at exactly the same version the shape did.
     */
    public static int stringsBeforeSeed(int protocol, boolean wide, boolean afterHandshake) {
        if (!wide) {
            // Nothing older than the handshake has a seed to write into, and the handshake is the
            // only thing that tells those versions apart from the ones that do: the numbering
            // restarted at a1.0.17, so a1.0.11 and b1.4_01 are both protocol 10. One sends a
            // handshake first and the other does not, and that is the whole difference. Reading
            // a1.0.11 as b1.4_01 would look for eight bytes past the end of its login packet and
            // write the marker into whatever the client sent next.
            if (!afterHandshake) return STRINGS_PASSWORD;

            // Alpha and Beta up to b1.4_01: username, password, then the seed.
            if (protocol >= PROTOCOL_A1_2_0 && protocol <= PROTOCOL_B1_4_01) return STRINGS_TWO;
            // a1.0.16, and a1.0.17 to a1.1.2_01 after the restart: still no seed.
            if (protocol == PROTOCOL_A1_0_16) return STRINGS_PASSWORD;
            return protocol >= 1 && protocol <= PROTOCOL_A1_1_2 ? STRINGS_PASSWORD : STRINGS_NONE;
        }
        if (protocol >= PROTOCOL_B1_5 && protocol <= PROTOCOL_1_1) return STRINGS_ONE;
        if (protocol >= PROTOCOL_1_2_1 && protocol <= PROTOCOL_1_2_5) return STRINGS_TWO;
        return STRINGS_NONE; // 1.3 and up: no username in the login packet at all
    }

    /** How long a name or a level type can plausibly be; past it, this is not a login packet. */
    private static final int STRING_LIMIT = 256;

    // ---------------------------------------------------------------- 1.3 to 1.6.4

    /**
     * What a Loki client puts on the end of the address it says it connected to.
     * <p>
     * From 1.3 the login packet has no spare field left — it stopped carrying a username at all —
     * and the handshake became four fields: a protocol version, the username, the host and the
     * port. The host is the one nothing reads: the server already knows its own address, and every
     * version of this era takes the string and drops it.
     * <p>
     * Appending to it is not a liberty being taken for the first time. Forge has appended
     * {@code \0FML\0} there since 1.3 for exactly this reason, which is both the precedent and the
     * reason the convention is safe: a separator of NUL is what everything downstream already
     * expects to find extra data behind.
     * <p>
     * The one thing this can reach that a vanilla server cannot is a proxy, which does read the
     * host. {@code Loki.no_legacy_handshake_marker} turns it off for anyone behind one.
     */
    public static final String HOST_MARKER = "\0Loki\0";

    /** The first thing a server of this era writes, and the first thing that is not our block. */
    public static final byte ENCRYPTION_REQUEST = (byte) 0xFD;

    /**
     * The whole handshake, once enough of it has arrived to say, or -1 while it has not.
     * <p>
     * Id, one byte of protocol version, the username, the host, and four bytes of port. Both
     * strings have to be walked because the second one's position depends on the first one's
     * length.
     */
    public static int modernHandshakeLength(byte[] packet, int seen) {
        if (seen < 4) return -1;
        int at = 2;
        for (int string = 0; string < 2; string++) {
            if (seen < at + 2) return -1;
            int characters = ((packet[at] & 0xFF) << 8) | (packet[at + 1] & 0xFF);
            if (characters > STRING_LIMIT) return NOT_THIS_SHAPE;
            at += 2 + characters * 2;
        }
        return at + 4;
    }

    /** Where the host string's own bytes start, counted from the beginning of the handshake. */
    public static int modernHostAt(byte[] packet) {
        int username = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        return 4 + username * 2;
    }

    /** Neither "yes" nor "not yet": these bytes are not the packet being looked for. */
    public static final int NOT_THIS_SHAPE = -2;

    /**
     * The same handshake with the marker on the end of its host, and the length field to match.
     *
     * @return the rewritten packet, or the one given when it cannot be read as this shape
     */
    public static byte[] withHostMarker(byte[] packet, int length) {
        int end = modernHandshakeLength(packet, length);
        if (end != length) return packet; // not a whole handshake of this shape, so leave it alone

        int hostAt = modernHostAt(packet);
        int hostChars = ((packet[hostAt] & 0xFF) << 8) | (packet[hostAt + 1] & 0xFF);
        int hostEnd = hostAt + 2 + hostChars * 2;
        int added = HOST_MARKER.length();
        if (hostChars + added > STRING_LIMIT) return packet;

        byte[] marked = new byte[length + added * 2];
        System.arraycopy(packet, 0, marked, 0, hostEnd);
        marked[hostAt] = (byte) ((hostChars + added) >> 8);
        marked[hostAt + 1] = (byte) (hostChars + added);
        for (int i = 0; i < added; i++) {
            marked[hostEnd + i * 2] = (byte) (HOST_MARKER.charAt(i) >> 8);
            marked[hostEnd + i * 2 + 1] = (byte) HOST_MARKER.charAt(i);
        }
        System.arraycopy(packet, hostEnd, marked, hostEnd + added * 2, length - hostEnd);
        return marked;
    }

    /** Whether the host in this handshake carries the marker. */
    public static boolean hasHostMarker(byte[] packet, int length) {
        if (modernHandshakeLength(packet, length) != length) return false;
        int hostAt = modernHostAt(packet);
        int hostChars = ((packet[hostAt] & 0xFF) << 8) | (packet[hostAt + 1] & 0xFF);
        int wanted = HOST_MARKER.length();
        if (hostChars < wanted) return false;

        int at = hostAt + 2 + (hostChars - wanted) * 2;
        for (int i = 0; i < wanted; i++) {
            char character = (char) (((packet[at + i * 2] & 0xFF) << 8) | (packet[at + i * 2 + 1] & 0xFF));
            if (character != HOST_MARKER.charAt(i)) return false;
        }
        return true;
    }

    /**
     * Walks a client's login packet a byte at a time and says when the eight unused ones go past.
     * <p>
     * A state machine rather than an offset, because the offset cannot be computed in advance: it
     * sits behind one or two strings whose lengths are only known as they arrive, and which of the
     * two it is behind is only known once the protocol version — the first field — has arrived. One
     * instance follows one connection, and the same walk serves both ends: the client writes the
     * marker into those eight bytes and the server reads them back out of the same place.
     * <p>
     * It gives up rather than guess. An unexpected packet id, a protocol whose shape is not one of
     * the ones below, a string longer than any name: all of them stop the walk, and a stopped walk
     * means the connection goes through untouched.
     */
    public static final class LoginWalk {
        private final boolean wide;
        private final boolean afterHandshake;
        private final int bytesPerCharacter;
        private int seen;
        private int protocol;
        private int strings = STRINGS_NONE;
        private int stringsDone;
        private int lengthHigh = -1;
        private int remaining = -1;
        private int seedAt = -1;
        private boolean stopped;

        /** @param wide as the handshake said, since the login packet is written the same way */
        public LoginWalk(boolean wide, boolean afterHandshake) {
            this.afterHandshake = afterHandshake;
            this.wide = wide;
            this.bytesPerCharacter = wide ? 2 : 1;
        }

        /**
         * @return where this byte falls within the eight, or -1 when it falls outside them
         */
        public int step(byte b) {
            if (stopped) return -1;
            int at = seen++;

            if (seedAt >= 0) {
                int index = at - seedAt;
                if (index >= LOGIN_MARKER.length - 1) stopped = true; // the last of them
                return index;
            }

            if (at == 0) {
                if (b != LOGIN) stopped = true;
                return -1;
            }
            if (at <= 4) {
                protocol = (protocol << 8) | (b & 0xFF);
                if (at == 4) {
                    strings = stringsBeforeSeed(protocol, wide, afterHandshake);
                    if (strings == STRINGS_NONE) stopped = true;
                }
                return -1;
            }

            if (remaining < 0) {
                if (lengthHigh < 0) {
                    lengthHigh = b & 0xFF;
                    return -1;
                }
                int characters = (lengthHigh << 8) | (b & 0xFF);
                lengthHigh = -1;
                if (characters > STRING_LIMIT) {
                    stopped = true; // not a string this protocol would have sent
                    return -1;
                }
                remaining = characters * bytesPerCharacter;

                // Where there is no seed, the second string's own bytes are the eight, and its
                // length is what says whether this is the field: the client of that range sends
                // "Password" and nothing else, so anything of another length is not it.
                if (strings == STRINGS_PASSWORD && stringsDone == 1) {
                    if (characters != PASSWORD.length()) {
                        stopped = true;
                        return -1;
                    }
                    seedAt = at + 1;
                    return -1;
                }

                if (remaining == 0) finishedString(at + 1);
                return -1;
            }

            remaining--;
            if (remaining == 0) finishedString(at + 1);
            return -1;
        }

        private void finishedString(int next) {
            remaining = -1;
            if (++stringsDone >= strings) seedAt = next;
        }

        /** What has to be at this position for the eight bytes to be the ones this may replace. */
        public byte expectedAt(int index) {
            return strings == STRINGS_PASSWORD ? (byte) PASSWORD.charAt(index) : 0;
        }

        /** What goes there instead. */
        public byte markerAt(int index) {
            return strings == STRINGS_PASSWORD ? PASSWORD_MARKER[index] : LOGIN_MARKER[index];
        }

        /** Whether the eight bytes just read are a marker rather than what was there before. */
        public boolean isMarker(byte[] eight) {
            byte[] wanted = strings == STRINGS_PASSWORD ? PASSWORD_MARKER : LOGIN_MARKER;
            for (int i = 0; i < wanted.length; i++) {
                if (eight[i] != wanted[i]) return false;
            }
            return true;
        }

        /** Whether this has given up, or has already seen all eight. */
        public boolean isStopped() {
            return stopped;
        }

        /** What the client said it was, once its first five bytes have gone past. */
        public int protocol() {
            return protocol;
        }
    }

    /** The eight bytes written there, chosen so that a zero seed cannot be mistaken for it. */
    public static final byte[] LOGIN_MARKER = {'L', 'o', 'k', 'i', 0x00, 0x01, 0x00, 0x00};


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
     * How the declaration is framed on the wire, of which there are two.
     * <p>
     * Where a connection is a wire between two programs, the block can be raw bytes: nothing in
     * between is parsing it, and the client takes it back out before the game reads any of it.
     * From 1.3 that stopped being true, because from 1.3 there are proxies, and a proxy decodes
     * every packet in both directions to decide where to send it. Raw bytes in that stream are not
     * a packet, and a proxy that meets them says so and drops the connection — measured, on
     * BungeeCord, as "Unknown packet id 79", which is the O of {@code 0xFE 'L' 'O' 'K'}.
     * <p>
     * So from 1.3 the same declaration travels in a plugin message instead, which is a packet of
     * the protocol carrying a channel name and a payload. A proxy decodes it like any other and
     * either forwards it or drops it; either way the connection survives, which raw bytes could not
     * promise. A client without Loki never marks itself and is never sent one.
     */
    public interface Block {
        /** What the block starts with, which is also how a reader knows to look at it at all. */
        byte firstByte();

        /** How many bytes have to be read before the length of the rest is known. */
        int headerBytes();

        /** The bytes to write, or null when there is nothing to say. */
        byte[] frame(String declaration);

        /**
         * @return how many bytes of body follow the header, or -1 when this is not one of ours
         */
        int bodyLength(byte[] header, int got);
    }

    /** The channel the declaration travels on, chosen to be nobody else's. */
    public static final String CHANNEL = "Loki";

    /** Raw bytes, for the eras where nothing sits between the two ends of a connection. */
    public static final Block RAW = new Block() {
        public byte firstByte() {
            return PAYLOAD_MAGIC[0];
        }

        public int headerBytes() {
            return PAYLOAD_HEADER_BYTES;
        }

        public byte[] frame(String declaration) {
            return payload(declaration);
        }

        public int bodyLength(byte[] header, int got) {
            if (got < PAYLOAD_HEADER_BYTES || !startsWithMagic(header, 0, got)) return -1;
            int length = ((header[PAYLOAD_MAGIC.length] & 0xFF) << 8)
                    | (header[PAYLOAD_MAGIC.length + 1] & 0xFF);
            return length > 0 && length <= PAYLOAD_MAX_BYTES ? length : -1;
        }
    };

    /**
     * A plugin message, for 1.3 and up, where a proxy may be reading.
     * <p>
     * {@code 0xFA}, the channel as a string, a short length and that many bytes — the shape read
     * out of the 1.6.4 client's own writer rather than taken from a wiki. The channel is a fixed
     * four characters, so the header is a fixed thirteen bytes and the length is always the last
     * two of them.
     */
    public static final Block PLUGIN_MESSAGE = new Block() {
        public byte firstByte() {
            return (byte) 0xFA;
        }

        public int headerBytes() {
            return 1 + 2 + CHANNEL.length() * 2 + 2;
        }

        public byte[] frame(String declaration) {
            if (declaration == null || declaration.length() == 0) return null;
            byte[] body;
            try {
                body = declaration.getBytes("UTF-8");
            } catch (Exception e) {
                return null;
            }
            if (body.length > PAYLOAD_MAX_BYTES) return null;

            byte[] block = new byte[headerBytes() + body.length];
            int at = 0;
            block[at++] = firstByte();
            block[at++] = (byte) (CHANNEL.length() >> 8);
            block[at++] = (byte) CHANNEL.length();
            for (int i = 0; i < CHANNEL.length(); i++) {
                block[at++] = (byte) (CHANNEL.charAt(i) >> 8);
                block[at++] = (byte) CHANNEL.charAt(i);
            }
            block[at++] = (byte) (body.length >> 8);
            block[at++] = (byte) body.length;
            System.arraycopy(body, 0, block, at, body.length);
            return block;
        }

        public int bodyLength(byte[] header, int got) {
            if (got < headerBytes() || header[0] != firstByte()) return -1;
            int characters = ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
            if (characters != CHANNEL.length()) return -1;
            for (int i = 0; i < CHANNEL.length(); i++) {
                int c = ((header[3 + i * 2] & 0xFF) << 8) | (header[4 + i * 2] & 0xFF);
                if (c != CHANNEL.charAt(i)) return -1;
            }
            int length = ((header[headerBytes() - 2] & 0xFF) << 8) | (header[headerBytes() - 1] & 0xFF);
            return length > 0 && length <= PAYLOAD_MAX_BYTES ? length : -1;
        }
    };

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
