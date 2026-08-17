package org.unmojang.loki.util;

public final class LegacyProtocol {
    public static final byte CLASSIC_IDENTIFICATION = 0x00;
    public static final int CLASSIC_IDENTIFICATION_BYTES = 131;
    public static final int CLASSIC_MARKER_OFFSET = 130;

    public static final byte MARKER = 0x4C;

    public static final byte[] PAYLOAD_MAGIC = {(byte) 0xFE, 0x4C, 0x4F, 0x4B};
    public static final int PAYLOAD_HEADER_BYTES = PAYLOAD_MAGIC.length + 2;
    public static final int PAYLOAD_MAX_BYTES = 8192;

    public static final byte HANDSHAKE = 0x02;
    public static final byte LOGIN = 0x01;

    public static int handshakeLength(byte[] head, int seen) {
        if (seen < 4) return -1;
        if (head[1] != 0) return -2;
        int characters = head[2] & 0xFF;
        if (characters == 0) return 3;
        return 3 + characters * (isWide(head) ? 2 : 1);
    }

    public static boolean isWide(byte[] head) {
        return head[3] == 0;
    }

    public static final int STRINGS_NONE = -1;
    public static final int STRINGS_ONE = 1;
    public static final int STRINGS_TWO = 2;
    public static final int STRINGS_PASSWORD = 3;

    public static final String PASSWORD = "Password";
    private static final byte[] PASSWORD_BYTES = {'P', 'a', 's', 's', 'w', 'o', 'r', 'd'};
    private static final byte[] EMPTY_SEED = new byte[8];

    public static final byte[] PASSWORD_MARKER = {'L', 'o', 'k', 'i', '0', '0', '0', '1'};

    public static final int PROTOCOL_A1_1_2 = 2;
    public static final int PROTOCOL_A1_0_15 = 13;
    public static final int PROTOCOL_A1_0_16 = 14;
    public static final int PROTOCOL_A1_2_0 = 3;
    public static final int PROTOCOL_B1_4_01 = 10;
    public static final int PROTOCOL_B1_5 = 11;
    public static final int PROTOCOL_1_1 = 23;
    public static final int PROTOCOL_1_2_1 = 28;
    public static final int PROTOCOL_1_2_5 = 29;

    public static int stringsBeforeSeed(int protocol, boolean wide, boolean afterHandshake) {
        if (!wide) {
            if (!afterHandshake) return STRINGS_PASSWORD;

            if (protocol >= PROTOCOL_A1_2_0 && protocol <= PROTOCOL_B1_4_01) return STRINGS_TWO;
            if (protocol == PROTOCOL_A1_0_16) return STRINGS_PASSWORD;
            return protocol >= 1 && protocol <= PROTOCOL_A1_1_2 ? STRINGS_PASSWORD : STRINGS_NONE;
        }
        if (protocol >= PROTOCOL_B1_5 && protocol <= PROTOCOL_1_1) return STRINGS_ONE;
        if (protocol >= PROTOCOL_1_2_1 && protocol <= PROTOCOL_1_2_5) return STRINGS_TWO;
        return STRINGS_NONE;
    }

    private static final int STRING_LIMIT = 256;

    public static final String HOST_MARKER = "\0Loki\0";

    public static final byte ENCRYPTION_REQUEST = (byte) 0xFD;

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

    public static final class Handshake {
        private byte[] bytes = new byte[64];
        private int length;

        public int add(byte[] from, int at, int count) {
            boolean wrongPacket = length == 0 && count > 0 && from[at] != HANDSHAKE;
            if (length + count > bytes.length) {
                byte[] bigger = new byte[Math.max(bytes.length * 2, length + count)];
                System.arraycopy(bytes, 0, bigger, 0, length);
                bytes = bigger;
            }
            System.arraycopy(from, at, bytes, length, count);
            length += count;
            if (wrongPacket || length > PAYLOAD_MAX_BYTES) return NOT_THIS_SHAPE;
            return modernHandshakeLength(bytes, length);
        }

        public byte[] packet() {
            return bytes;
        }

        public int gathered() {
            return length;
        }
    }

    public static int modernHostAt(byte[] packet) {
        int username = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        return 4 + username * 2;
    }

    public static final int NOT_THIS_SHAPE = -2;

    public static byte[] withHostMarker(byte[] packet, int length) {
        int end = modernHandshakeLength(packet, length);
        if (end != length) return packet;

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

        public LoginWalk(boolean wide, boolean afterHandshake) {
            this.afterHandshake = afterHandshake;
            this.wide = wide;
            this.bytesPerCharacter = wide ? 2 : 1;
        }

        public int step(byte b) {
            if (stopped) return -1;
            int at = seen++;

            if (seedAt >= 0) {
                int index = at - seedAt;
                if (index >= LOGIN_MARKER.length - 1) stopped = true;
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
                    stopped = true;
                    return -1;
                }
                remaining = characters * bytesPerCharacter;

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

        public byte expectedAt(int index) {
            return before()[index];
        }

        public byte markerAt(int index) {
            return after()[index];
        }

        public boolean isMarker(byte[] eight) {
            byte[] wanted = after();
            for (int i = 0; i < wanted.length; i++) {
                if (eight[i] != wanted[i]) return false;
            }
            return true;
        }

        private byte[] before() {
            return strings == STRINGS_PASSWORD ? PASSWORD_BYTES : EMPTY_SEED;
        }

        private byte[] after() {
            return strings == STRINGS_PASSWORD ? PASSWORD_MARKER : LOGIN_MARKER;
        }

        public boolean isStopped() {
            return stopped;
        }

        public int protocol() {
            return protocol;
        }
    }

    public static final class BeforeLogin {
        private final byte[] head = new byte[8];
        private int seen;
        private int end = -1;
        private LoginWalk walk;
        private boolean impossible;

        public LoginWalk step(byte b) {
            if (walk != null) return walk;

            if (seen == 0 && b == LOGIN) return walk = new LoginWalk(false, false);
            if (seen == 0 && b != HANDSHAKE) {
                impossible = true;
                return null;
            }
            if (end > 0 && seen >= end) return walk = new LoginWalk(isWide(head), true);

            if (seen < head.length) head[seen] = b;
            seen++;
            if (end < 0) end = handshakeLength(head, seen);
            if (end == NOT_THIS_SHAPE) impossible = true;
            return null;
        }

        public boolean isImpossible() {
            return impossible;
        }
    }

    public static final byte[] LOGIN_MARKER = {'L', 'o', 'k', 'i', 0x00, 0x01, 0x00, 0x00};

    private LegacyProtocol() {}

    public static boolean startsWithMagic(byte[] bytes, int at, int available) {
        if (available < PAYLOAD_MAGIC.length) return false;
        for (int i = 0; i < PAYLOAD_MAGIC.length; i++) {
            if (bytes[at + i] != PAYLOAD_MAGIC[i]) return false;
        }
        return true;
    }

    public interface Block {
        byte firstByte();

        int headerBytes();

        byte[] frame(String declaration);

        int bodyLength(byte[] header, int got);
    }

    public static final String CHANNEL = "Loki";

    public static Block blockStartingWith(byte first) {
        if (first == RAW.firstByte()) return RAW;
        if (first == PLUGIN_MESSAGE.firstByte()) return PLUGIN_MESSAGE;
        return null;
    }

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

    public static byte[] payload(String declaration) {
        if (declaration == null || declaration.length() == 0) return null;
        byte[] body;
        try {
            body = declaration.getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
        if (body.length > PAYLOAD_MAX_BYTES) return null;

        byte[] block = new byte[PAYLOAD_HEADER_BYTES + body.length];
        System.arraycopy(PAYLOAD_MAGIC, 0, block, 0, PAYLOAD_MAGIC.length);
        block[PAYLOAD_MAGIC.length] = (byte) (body.length >> 8);
        block[PAYLOAD_MAGIC.length + 1] = (byte) body.length;
        System.arraycopy(body, 0, block, PAYLOAD_HEADER_BYTES, body.length);
        return block;
    }
}
