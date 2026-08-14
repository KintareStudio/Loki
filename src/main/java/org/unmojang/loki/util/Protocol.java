package org.unmojang.loki.util;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The slice of the Minecraft protocol Loki has to speak for itself.
 * <p>
 * Only the handshake and the status state are modelled, and that is all Loki ever needs. Their
 * shape has not changed since the Netty rewrite in 1.7, and neither ever negotiates compression or
 * encryption, since both are switched on by packets that only exist in the login state. The bytes
 * are therefore always plain, whichever version is on either end.
 */
public final class Protocol {
    public static final int PACKET_HANDSHAKE = 0x00;
    public static final int PACKET_STATUS_RESPONSE = 0x00;
    public static final int STATE_STATUS = 1;
    public static final int STATE_LOGIN = 2;
    /** 1.20.5+, where a server can hand a client on to another one. */
    public static final int STATE_TRANSFER = 3;

    /** Sent as the handshake protocol version. Servers ignore it in the status state. */
    public static final int PROTOCOL_UNKNOWN = -1;

    private static final int MAX_VARINT_BYTES = 5;

    private Protocol() {}

    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        // Sign extension is intentional: -1 encodes as the full five bytes, which is what the
        // "unknown protocol version" convention expects.
        do {
            int part = value & 0x7F;
            value >>>= 7;
            out.writeByte(value != 0 ? (part | 0x80) : part);
        } while (value != 0);
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            int read = in.readUnsignedByte();
            result |= (read & 0x7F) << (i * 7);
            if ((read & 0x80) == 0) return result;
        }
        throw new IOException("VarInt is longer than " + MAX_VARINT_BYTES + " bytes");
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8");
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > maxBytes) throw new IOException("Implausible string length: " + length);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    /** Frames a status response around a JSON document, ready to go out on the wire. */
    public static byte[] statusResponse(String json) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(payload);
        writeVarInt(packet, PACKET_STATUS_RESPONSE);
        writeString(packet, json);
        packet.flush();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(frame);
        writeVarInt(framed, payload.size());
        framed.write(payload.toByteArray());
        framed.flush();
        return frame.toByteArray();
    }

    /**
     * Reads protocol types out of a copy of a frame.
     * <p>
     * Loki uses this to look at packets it does not own, on their way in or out of a pipeline it is
     * only a bystander in, so nothing here consumes anything. A malformed or truncated frame is not
     * an error worth an exception either: it just means Loki has nothing to say about this one, so
     * every read is guarded by {@link #ok()} and stops touching the buffer once it goes false.
     */
    public static final class Reader {
        private final byte[] bytes;
        private int at;
        private boolean ok = true;

        public Reader(byte[] bytes, int at) {
            this.bytes = bytes;
            this.at = at;
        }

        public boolean ok() {
            return ok;
        }

        public int position() {
            return at;
        }

        public int varInt() {
            int result = 0;
            for (int i = 0; i < MAX_VARINT_BYTES; i++) {
                int read = next();
                if (!ok) return 0;
                result |= (read & 0x7F) << (i * 7);
                if ((read & 0x80) == 0) return result;
            }
            ok = false;
            return 0;
        }

        public int unsignedShort() {
            int high = next();
            int low = next();
            return ok ? (high << 8) | low : 0;
        }

        public String string(int maxBytes) {
            int length = varInt();
            if (!ok || length < 0 || length > maxBytes || length > bytes.length - at) {
                ok = false;
                return null;
            }
            String value;
            try {
                value = new String(bytes, at, length, "UTF-8");
            } catch (IOException e) {
                ok = false;
                return null;
            }
            at += length;
            return value;
        }

        public void skipString(int maxBytes) {
            int length = varInt();
            if (!ok || length < 0 || length > maxBytes || length > bytes.length - at) {
                ok = false;
                return;
            }
            at += length;
        }

        private int next() {
            if (!ok || at >= bytes.length) {
                ok = false;
                return 0;
            }
            return bytes[at++] & 0xFF;
        }
    }
}
