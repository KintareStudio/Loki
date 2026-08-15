package org.unmojang.loki.hooks;

import org.unmojang.loki.util.NettyBridge;
import org.unmojang.loki.util.Protocol;
import org.unmojang.loki.util.logger.NilLogger;

/**
 * Tells a joining connection apart from a server list ping.
 * <p>
 * Both go through {@code Bootstrap.connect}, so the connect itself says nothing about which one
 * this is. The handshake does: its last field is the state the client is asking to move to, 1 for
 * status and 2 for login. This watcher sits at the front of the channel's pipeline, reads that one
 * number off the first frame on its way out, and leaves.
 * <p>
 * Reading it off the wire rather than off the call site is also what makes the way the player got
 * here irrelevant. The server list, the direct connect screen, a {@code --server} argument and a
 * 1.20.5+ transfer all arrive at the same {@code connect}, and all announce themselves in the same
 * field.
 * <p>
 * Getting this right is what stops the client's own server list from driving the feature. Opening
 * the multiplayer screen pings every server on it, and treating those as arrivals would leave the
 * declared profile API set by whichever server happened to answer last, and would have Loki ping
 * every server in the list on top of that.
 */
public final class HandshakeWatcher {
    /** Enough for the handshake: a VarInt-framed packet whose only variable part is a hostname. */
    private static final int MAX_PEEK_BYTES = 512;
    private static final int MAX_HOSTNAME_BYTES = 255 * 4;

    private static final NilLogger log = NilLogger.get("Loki");

    private HandshakeWatcher() {}

    /**
     * @param future the {@code ChannelFuture} {@code Bootstrap.connect} just returned. Its channel
     *               exists already, and nothing has been written to it yet, so there is no race
     *               with the handshake this is here to read.
     * @param host   the address the game dialled, as it dialled it
     */
    public static void watch(Object future, final String host, final int port) {
        final String peer = host + ":" + port;
        try {
            Object channel = NettyBridge.call(future, "channel", new Object[0]);
            final Object pipeline = NettyBridge.call(channel, "pipeline", new Object[0]);
            Object handler = NettyBridge.newHandler(pipeline,
                    new String[]{"ChannelOutboundHandler"},
                    new NettyBridge.PeekAdapter() {
                        public Object outbound(Object ctx, Object msg) {
                            inspect(ctx, msg, host, port);
                            return msg;
                        }
                    });
            NettyBridge.call(pipeline, "addFirst", new Object[]{"loki-handshake", handler});
        } catch (Throwable t) {
            // No Netty, or a pipeline Loki cannot reach. The feature stays off rather than falling
            // back to treating every connect as an arrival, which is the behaviour this replaces.
            log.debug("Cannot watch the handshake to " + peer + " (" + t + ")");
        }
    }

    /**
     * Looks at the first frame the channel writes, which is always the handshake, and steps out of
     * the pipeline whatever it turns out to be. Nothing here may cost anything on a live
     * connection, so this must be the only packet the watcher ever sees.
     */
    private static void inspect(Object ctx, Object msg, String host, int port) {
        String peer = host + ":" + port;
        try {
            byte[] frame = NettyBridge.peekBytes(msg, MAX_PEEK_BYTES);
            if (frame == null) return; // not a framed packet, so not the handshake either

            Protocol.Reader reader = new Protocol.Reader(frame, 0);
            reader.varInt();                       // frame length
            int packetId = reader.varInt();
            reader.varInt();                       // protocol version
            reader.skipString(MAX_HOSTNAME_BYTES); // server address
            reader.unsignedShort();                // port
            int nextState = reader.varInt();

            if (!reader.ok() || packetId != Protocol.PACKET_HANDSHAKE) {
                log.debug("First frame to " + peer + " is not a handshake, ignoring the connection");
                return;
            }
            // A transfer is an arrival like any other: the player ends up on a different server,
            // which may well declare a different profile API than the one that sent them.
            if (nextState == Protocol.STATE_LOGIN || nextState == Protocol.STATE_TRANSFER) {
                ProfileRedirect.noteJoin(host, port);
                watchClose(ctx, host, port);
            } else {
                log.trace("Connection to " + peer + " is a ping, not an arrival");
            }
        } catch (Throwable t) {
            log.debug("Failed to read the handshake to " + peer + " (" + t + ")");
        } finally {
            NettyBridge.removeSelf(ctx);
        }
    }

    /**
     * Arranges for the arrival to be undone when this connection ends.
     * <p>
     * Joining a server widens what the client trusts, and the bound on that is meant to be the time
     * the player spends there. Without this the widening ended at the next arrival instead, so a
     * player who quit to the menu kept the last server's signing keys and texture domains for as
     * long as they stayed there.
     */
    private static void watchClose(Object ctx, final String host, final int port) {
        try {
            Object channel = NettyBridge.call(ctx, "channel", new Object[0]);
            NettyBridge.onClose(channel, new Runnable() {
                public void run() {
                    ProfileRedirect.noteLeave(host, port);
                }
            });
        } catch (Throwable t) {
            // The redirect then lasts until the next arrival, as it did before, rather than the
            // join being abandoned over it
            log.debug("Cannot watch for the disconnect from " + host + ":" + port + " (" + t + ")");
        }
    }
}
