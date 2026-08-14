package org.unmojang.loki.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Minimal Server List Ping client, status state only.
 * <p>
 * Loki speaks the status protocol itself instead of reading the game's own ping response. Vanilla
 * clients are obfuscated and the classes that model a status response are renamed every version,
 * whereas the status handshake has been unchanged since 1.7 (protocol 4). Doing it ourselves is the
 * only way this stays version agnostic without a mapping database.
 * <p>
 * Nothing here touches the login state, so no encryption and no compression is ever negotiated.
 */
public final class ServerListPing {
    /** Sent as the handshake protocol version. Servers ignore it in the status state. */
    private static final int PROTOCOL_UNKNOWN = -1;
    private static final int NEXT_STATE_STATUS = 1;
    private static final int MAX_RESPONSE_BYTES = 262144;
    private static final int MAX_VARINT_BYTES = 5;

    private ServerListPing() {}

    /**
     * Performs a handshake + status request and returns the raw status JSON.
     *
     * @param host the hostname to send in the handshake, so virtual hosts on a proxy resolve the
     *             same way they would for the game
     */
    public static String statusJson(String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            writePacket(out, handshake(host, port));
            writePacket(out, new byte[]{0x00}); // Status Request
            out.flush();

            int length = readVarInt(in);
            if (length <= 0 || length > MAX_RESPONSE_BYTES) {
                throw new IOException("Implausible status packet length: " + length);
            }
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                throw new IOException("Unexpected packet 0x" + Integer.toHexString(packetId) + " in status state");
            }
            return readString(in);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] handshake(String host, int port) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(0x00); // Handshake
        writeVarInt(out, PROTOCOL_UNKNOWN);
        writeString(out, host);
        out.writeShort(port);
        writeVarInt(out, NEXT_STATE_STATUS);
        out.flush();
        return buffer.toByteArray();
    }

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8");
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_RESPONSE_BYTES) {
            throw new IOException("Implausible string length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        // Sign extension is intentional: -1 encodes as the full five bytes, which is what the
        // "unknown protocol version" convention expects.
        do {
            int part = value & 0x7F;
            value >>>= 7;
            out.writeByte(value != 0 ? (part | 0x80) : part);
        } while (value != 0);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            int read = in.readUnsignedByte();
            result |= (read & 0x7F) << (i * 7);
            if ((read & 0x80) == 0) return result;
        }
        throw new IOException("VarInt is longer than " + MAX_VARINT_BYTES + " bytes");
    }
}
