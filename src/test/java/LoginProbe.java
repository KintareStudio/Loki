import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Prints what a real client of a pre-1.7 version actually puts on the wire.
 *
 * <p>This is not a server and does not pretend to be one: it accepts a connection, answers the
 * handshake with the one string that means "offline, go ahead", and then dumps every byte the
 * client sends next without interpreting any of it. What it produces is ground truth — the login
 * packet as Mojang's own binary writes it — which is what the marker's offset has to be built from.
 * The alternative was a table of protocol versions copied off a wiki, and the whole reason this
 * exists is that copying shapes from somewhere else is how the filter came to write into the wrong
 * field.
 *
 * <p>Usage: {@code LoginProbe <port>}. Prints PORT= when it is listening, then LOGIN= with the
 * packet in hex, then PROTOCOL= with the int the client put first.
 */
public final class LoginProbe {
    /** Whether this client writes two bytes a character, which the login packet is then read with. */
    private static boolean wide = true;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        ServerSocket server = new ServerSocket(port);
        // A client that never arrives — a version id the manifest does not carry, a jar that will
        // not start — must not leave this waiting for the rest of the sweep.
        server.setSoTimeout(120000);
        System.out.println("PORT=" + server.getLocalPort());
        System.out.flush();

        Socket socket;
        try {
            socket = server.accept();
        } catch (Exception e) {
            System.out.println("NOCLIENT=" + e);
            System.out.flush();
            System.exit(0);
            return;
        }
        socket.setSoTimeout(15000);
        InputStream in = socket.getInputStream();
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        int id = in.read();
        System.out.println("FIRST=0x" + Integer.toHexString(id));

        if (id == 0x02) {
            // The handshake: one string, as it has been since a1.0.16. How that string is written
            // is the thing to find out — early versions wrote a byte per character and later ones
            // two — so the length is read and then the bytes are counted rather than assumed.
            DataInputStream data = new DataInputStream(in);
            int characters = data.readUnsignedShort();
            Thread.sleep(300); // let the rest of the packet arrive before counting it
            int bytes = in.available();
            wide = bytes >= characters * 2;
            System.out.println("ENCODING=" + (wide ? "utf16" : "utf8")
                    + " chars=" + characters + " bytes=" + bytes);
            byte[] handshake = new byte[Math.min(bytes, characters * (wide ? 2 : 1))];
            data.readFully(handshake);
            System.out.println("HANDSHAKE=" + hex(handshake));

            // "-" is what a server in offline mode replies, and what makes the client go straight
            // on to the login packet instead of stopping to authenticate. Written the way this
            // client writes its own strings; the other way it reads as garbage and hangs up.
            out.writeByte(0x02);
            out.writeShort(1);
            if (wide) {
                out.writeChar('-');
            } else {
                out.writeByte('-');
            }
            out.flush();
        }

        byte[] login = new byte[512];
        int read = 0;
        if (id != 0x02) {
            // Before a1.0.16 there is no handshake and the login packet is the first thing sent, so
            // the id already read is the login's own and belongs at the front of what is printed.
            login[read++] = (byte) id;
        }
        try {
            while (read < login.length) {
                int n = in.read(login, read, login.length - read);
                if (n < 0) break;
                read += n;
                // One packet is all this needs, and the client sends nothing else until it is
                // answered, so a short pause after the first bytes means it has finished.
                if (read >= 8 && in.available() == 0) {
                    Thread.sleep(300);
                    if (in.available() == 0) break;
                }
            }
        } catch (Exception e) {
            // A timeout here still leaves whatever did arrive worth printing.
        }

        byte[] packet = new byte[read];
        System.arraycopy(login, 0, packet, 0, read);
        System.out.println("LOGIN=" + hex(packet));
        if (read >= 5 && packet[0] == 0x01) {
            int protocol = ((packet[1] & 0xFF) << 24) | ((packet[2] & 0xFF) << 16)
                    | ((packet[3] & 0xFF) << 8) | (packet[4] & 0xFF);
            System.out.println("PROTOCOL=" + protocol);
            if (read >= 7) {
                int nameChars = ((packet[5] & 0xFF) << 8) | (packet[6] & 0xFF);
                System.out.println("NAMECHARS=" + nameChars);
                int after = 7 + nameChars * (wide ? 2 : 1);
                if (after <= read) {
                    System.out.println("AFTERNAME=" + hex(packet, after, read - after));
                }
            }
        }
        System.out.flush();
        socket.close();
        server.close();
        System.exit(0);
    }

    private static String hex(byte[] bytes) {
        return hex(bytes, 0, bytes.length);
    }

    private static String hex(byte[] bytes, int at, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            String one = Integer.toHexString(bytes[at + i] & 0xFF);
            if (one.length() == 1) text.append('0');
            text.append(one);
        }
        return text.toString();
    }
}
