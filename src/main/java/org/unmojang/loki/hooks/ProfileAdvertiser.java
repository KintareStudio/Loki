package org.unmojang.loki.hooks;

import org.unmojang.loki.util.NettyBridge;
import org.unmojang.loki.util.Protocol;
import org.unmojang.loki.util.logger.NilLogger;

/**
 * Puts the server's own API server into its Server List Ping, so Loki clients can resolve profiles
 * against it.
 * <p>
 * This is the other half of {@link ProfileRedirect}. A server that Loki already points at an API
 * server knows the one thing its players need in order to see each other, and there is no reason to
 * make an operator write it out a second time by hand.
 *
 * <h2>Why the wire and not the status object</h2>
 * A server's status response is built by classes that are obfuscated and renamed every version,
 * whereas the status packet has been a length, a packet id and one JSON string since 1.7. Loki
 * therefore rewrites the frame on its way out rather than the object on its way in, which is the
 * same reasoning that has the client speak the ping protocol itself. Nothing is decoded: the
 * declaration is spliced in after the opening brace, so every other byte of the document, favicon
 * included, is passed through untouched.
 * <p>
 * Neither state involved negotiates compression or encryption, so the bytes are always plain.
 */
public final class ProfileAdvertiser {
    /** A status response, plus room for the declaration. */
    private static final int MAX_FRAME_BYTES = 300000;
    private static final int MAX_HOSTNAME_BYTES = 255 * 4;
    private static final String HANDLER_NAME = "loki-advertise";

    private static final NilLogger log = NilLogger.get("Loki");

    private ProfileAdvertiser() {}

    private static boolean disabled() {
        return Boolean.getBoolean("Loki.disable_profile_advertise");
    }

    /**
     * The endpoints to declare, already written as the JSON body of the {@code loki} object, or
     * null when this server has nothing to say.
     * <p>
     * The hosts themselves, not the root they may or may not have come from. A server always knows
     * where it sends its own profile queries, whichever way it was configured, whereas a root only
     * exists if it was pointed at an authlib-injector one — and a Yggdrasil server is under no
     * obligation to arrange its paths the way that API expects. Naming them outright says what is
     * true instead of what would have to be inferred and then re-derived on the other side.
     */
    public static String declaration() {
        if (disabled()) return null;

        StringBuilder fields = new StringBuilder();
        appendField(fields, "session", System.getProperty("minecraft.api.session.host"));
        appendField(fields, "account", System.getProperty("minecraft.api.account.host"));
        appendField(fields, "services", System.getProperty("minecraft.api.services.host"));

        // Stands alone: a server on the same API server as everyone else still has this to say,
        // and it is the only thing it needs to say to be worth declaring
        if (enforcesSecureProfile()) {
            separate(fields);
            fields.append("\"enforceSecureProfile\":true");
        }

        // Whatever the client would otherwise have to read the metadata for, since it has no root
        String domains = System.getProperty(ProfileRedirect.PROP_TEXTURE_DOMAINS, "");
        if (domains.length() != 0 && domains.indexOf('"') == -1) {
            separate(fields);
            fields.append("\"skinDomains\":[");
            String[] each = domains.split(",");
            for (int i = 0; i < each.length; i++) {
                if (i > 0) fields.append(",");
                fields.append("\"").append(each[i]).append("\"");
            }
            fields.append("]");
        }

        return fields.length() == 0 ? null : fields.toString();
    }

    /**
     * Whether to tell clients that signatures are checked here: this server's own Loki argument.
     * <p>
     * Not {@code enforce-secure-profile} out of {@code server.properties}. That setting arrived in
     * 1.19 and does not exist before it, so on the versions where the client most needs telling —
     * the ones with no secure chat of their own — the file has nothing to say. The operator's
     * statement that signatures matter on this server is the flag they started Loki with, and it
     * means the same thing on every version.
     */
    private static boolean enforcesSecureProfile() {
        return Boolean.getBoolean("Loki.enforce_secure_profile");
    }

    /**
     * The reason inside a reflective failure, since everything here is reached by reflection and
     * "InvocationTargetException" on its own names nothing at all.
     */
    static String reasonOf(Throwable t) {
        Throwable cause = t;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause);
    }

    private static void separate(StringBuilder fields) {
        if (fields.length() != 0) fields.append(",");
    }

    private static void appendField(StringBuilder fields, String name, String url) {
        if (url == null || url.length() == 0) return;
        // A quote or a backslash would land inside a JSON string literal, and no endpoint has one
        if (url.indexOf('"') != -1 || url.indexOf('\\') != -1) {
            log.warn("Not declaring an endpoint containing quotes: " + url);
            return;
        }
        if (fields.length() != 0) fields.append(",");
        fields.append("\"").append(name).append("\":\"").append(url).append("\"");
    }

    /**
     * Called from {@code io.netty.bootstrap.AbstractBootstrap#bind}, so that every connection this
     * server accepts can be looked at.
     *
     * @param future the {@code ChannelFuture} the bind returned, typed as Object so the injected
     *               call site does not depend on where Netty happens to be relocated to
     */
    public static void noteBind(Object future) {
        if (declaration() == null) return;
        try {
            Object channel = NettyBridge.call(future, "channel", new Object[0]);
            Object pipeline = NettyBridge.call(channel, "pipeline", new Object[0]);

            // bind(int) and friends delegate to bind(SocketAddress), so the hook can fire twice
            if (NettyBridge.call(pipeline, "get", new Object[]{HANDLER_NAME}) != null) return;

            Object handler = NettyBridge.newHandler(pipeline,
                    new String[]{"ChannelInboundHandler"},
                    new NettyBridge.PeekAdapter() {
                        public Object inbound(Object ctx, Object msg) {
                            watchConnection(msg);
                            return msg;
                        }
                    });
            NettyBridge.call(pipeline, "addFirst", new Object[]{HANDLER_NAME, handler});
            log.info("Declaring this server's profile endpoints to Loki clients");
        } catch (Throwable t) {
            log.debug("Cannot advertise the profile API on this listener (" + reasonOf(t) + ")");
        }
    }

    /**
     * A server channel reads accepted connections the way any other channel reads bytes, so this is
     * how each new connection is reached before anything has been written to it.
     */
    private static void watchConnection(Object child) {
        try {
            Object pipeline = NettyBridge.call(child, "pipeline", new Object[0]);
            Object rewriter = NettyBridge.newHandler(pipeline,
                    new String[]{"ChannelInboundHandler", "ChannelOutboundHandler"},
                    new Rewriter());
            NettyBridge.call(pipeline, "addFirst", new Object[]{HANDLER_NAME, rewriter});
        } catch (Throwable t) {
            // Not an accepted connection, so not ours to touch
            log.trace("Ignoring an inbound message that is not a channel (" + t + ")");
        }
    }

    /**
     * Reads the handshake to learn what the connection is for, and rewrites the status response if
     * it turns out to be a ping. Steps out of the pipeline as soon as it knows it is not needed, so
     * a player's connection carries it for one packet at most.
     */
    private static final class Rewriter extends NettyBridge.PeekAdapter {
        private boolean isStatus;
        private boolean decided;
        /** Whether the frame's length has already gone past on its own. */
        private boolean lengthSeen;

        /**
         * Whether these bytes are a variable-length integer and nothing else, which is what a
         * length prefix written by itself looks like.
         */
        private static boolean isBareVarInt(byte[] bytes) {
            if (bytes.length == 0 || bytes.length > 5) return false;
            Protocol.Reader reader = new Protocol.Reader(bytes, 0);
            int value = reader.varInt();
            return reader.ok() && reader.position() == bytes.length && value > 0;
        }

        public Object inbound(Object ctx, Object msg) {
            if (decided) return msg;
            decided = true;
            try {
                byte[] frame = NettyBridge.peekBytes(msg, MAX_HOSTNAME_BYTES + 64);
                if (frame == null) return msg;

                Protocol.Reader reader = new Protocol.Reader(frame, 0);
                reader.varInt();                       // frame length
                int packetId = reader.varInt();
                reader.varInt();                       // protocol version
                reader.skipString(MAX_HOSTNAME_BYTES); // the address the client dialled
                reader.unsignedShort();                // port
                int nextState = reader.varInt();

                isStatus = reader.ok()
                        && packetId == Protocol.PACKET_HANDSHAKE
                        && nextState == Protocol.STATE_STATUS;
                log.trace("Inbound handshake: ok=" + reader.ok() + " packet=" + packetId
                        + " nextState=" + nextState + " frame=" + frame.length + " bytes");

            } catch (Throwable t) {
                log.debug("Could not read an inbound handshake (" + t + ")");
            }
            if (!isStatus) NettyBridge.removeSelf(ctx);
            return msg;
        }

        public Object outbound(Object ctx, Object msg) {
            if (!isStatus) return msg;
            try {
                byte[] frame = NettyBridge.peekBytes(msg, MAX_FRAME_BYTES);
                if (frame == null) {
                    log.trace("Outbound message is not readable bytes: "
                            + (msg == null ? "null" : msg.getClass().getName()));
                    return msg;
                }

                // A frame does not have to arrive in one piece. Velocity writes the length prefix
                // in a buffer of its own and the packet in the next one, which is not a quirk to
                // work around so much as the shape of an encoder that would rather not copy. When
                // that is what this is, the length is let go as nothing and the packet that follows
                // is rewritten into a whole frame, length and all.
                if (!lengthSeen && isBareVarInt(frame)) {
                    lengthSeen = true;
                    Object empty = NettyBridge.wrapBytes(msg, new byte[0]);
                    NettyBridge.release(msg);
                    return empty;
                }

                Protocol.Reader reader = new Protocol.Reader(frame, 0);
                if (!lengthSeen) reader.varInt(); // the length is here, at the front of the frame
                int packetId = reader.varInt();
                if (!reader.ok() || packetId != Protocol.PACKET_STATUS_RESPONSE) return msg;

                String status = reader.string(MAX_FRAME_BYTES);
                if (!reader.ok() || status == null) return msg;
                log.trace("Status response of " + status.length() + " chars from a "
                        + frame.length + " byte frame: " + status);

                String declared = withDeclaration(status, declaration());
                if (declared == null) return msg;

                Object rewritten = NettyBridge.wrapBytes(msg, Protocol.statusResponse(declared));
                NettyBridge.release(msg);
                log.debug("Declared the profile API in a status response");
                NettyBridge.removeSelf(ctx); // one status response per connection is all there is
                return rewritten;
            } catch (Throwable t) {
                log.error("Failed to declare the profile API, serving the status unchanged", t);
                return msg;
            }
        }
    }

    /**
     * Splices the declaration in after the opening brace rather than reparsing the document.
     * <p>
     * A status response carries a favicon and chat components, and re-serialising those to add one
     * key risks changing bytes nobody asked Loki to change.
     *
     * @return the new document, or null to leave this one alone
     */
    static String withDeclaration(String status, String fields) {
        if (status == null || fields == null) return null;
        String trimmed = status.trim();
        if (!trimmed.startsWith("{")) return null;
        if (trimmed.indexOf("\"loki\"") != -1) return null; // already declared, by us or by a plugin

        String rest = trimmed.substring(1).trim();
        String declaration = "\"loki\":{" + fields + "}";
        return "{" + declaration + (rest.startsWith("}") ? "" : ",") + rest;
    }
}
