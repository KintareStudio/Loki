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
    private static final int MAX_RESPONSE_BYTES = 262144;

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
            writePacket(out, new byte[]{Protocol.PACKET_STATUS_RESPONSE}); // Status Request
            out.flush();

            int length = Protocol.readVarInt(in);
            if (length <= 0 || length > MAX_RESPONSE_BYTES) {
                throw new IOException("Implausible status packet length: " + length);
            }
            int packetId = Protocol.readVarInt(in);
            if (packetId != Protocol.PACKET_STATUS_RESPONSE) {
                throw new IOException("Unexpected packet 0x" + Integer.toHexString(packetId) + " in status state");
            }
            return Protocol.readString(in, MAX_RESPONSE_BYTES);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] handshake(String host, int port) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(Protocol.PACKET_HANDSHAKE);
        Protocol.writeVarInt(out, Protocol.PROTOCOL_UNKNOWN);
        Protocol.writeString(out, host);
        out.writeShort(port);
        Protocol.writeVarInt(out, Protocol.STATE_STATUS);
        out.flush();
        return buffer.toByteArray();
    }

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        Protocol.writeVarInt(out, payload.length);
        out.write(payload);
    }
}
