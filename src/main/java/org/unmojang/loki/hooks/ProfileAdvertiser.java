package org.unmojang.loki.hooks;

import org.unmojang.loki.util.NettyBridge;
import org.unmojang.loki.util.Protocol;
import org.unmojang.loki.util.logger.NilLogger;

public final class ProfileAdvertiser {
    private static final int MAX_FRAME_BYTES = 300000;
    private static final int MAX_HOSTNAME_BYTES = 255 * 4;
    private static final String HANDLER_NAME = "loki-advertise";

    private static final NilLogger log = NilLogger.get("Loki");

    private ProfileAdvertiser() {}

    private static boolean disabled() {
        return Boolean.getBoolean("Loki.disable_profile_advertise");
    }

    public static String declaration() {
        if (disabled()) return null;

        StringBuilder fields = new StringBuilder();
        appendField(fields, "session", System.getProperty("minecraft.api.session.host"));
        appendField(fields, "account", System.getProperty("minecraft.api.account.host"));
        appendField(fields, "services", System.getProperty("minecraft.api.services.host"));

        if (enforcesSecureProfile()) {
            separate(fields);
            fields.append("\"enforceSecureProfile\":true");
        }

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

    private static boolean enforcesSecureProfile() {
        return Boolean.parseBoolean(System.getProperty("Loki.enforce_secure_profile",
                System.getProperty("Loki.verify_signatures", "false")));
    }

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
        if (url.indexOf('"') != -1 || url.indexOf('\\') != -1) {
            log.warn("Not declaring an endpoint containing quotes: " + url);
            return;
        }
        if (fields.length() != 0) fields.append(",");
        fields.append("\"").append(name).append("\":\"").append(url).append("\"");
    }

    public static void noteBind(Object future) {
        if (declaration() == null) return;
        try {
            Object channel = NettyBridge.call(future, "channel", new Object[0]);
            Object pipeline = NettyBridge.call(channel, "pipeline", new Object[0]);

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

    private static void watchConnection(Object child) {
        try {
            Object pipeline = NettyBridge.call(child, "pipeline", new Object[0]);
            Object rewriter = NettyBridge.newHandler(pipeline,
                    new String[]{"ChannelInboundHandler", "ChannelOutboundHandler"},
                    new Rewriter());
            NettyBridge.call(pipeline, "addFirst", new Object[]{HANDLER_NAME, rewriter});
        } catch (Throwable t) {
            log.trace("Ignoring an inbound message that is not a channel (" + t + ")");
        }
    }

    private static final class Rewriter extends NettyBridge.PeekAdapter {
        private boolean isStatus;
        private boolean decided;
        private boolean lengthSeen;

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
                reader.varInt();
                int packetId = reader.varInt();
                reader.varInt();
                reader.skipString(MAX_HOSTNAME_BYTES);
                reader.unsignedShort();
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

                if (!lengthSeen && isBareVarInt(frame)) {
                    lengthSeen = true;
                    Object empty = NettyBridge.wrapBytes(msg, new byte[0]);
                    NettyBridge.release(msg);
                    return empty;
                }

                Protocol.Reader reader = new Protocol.Reader(frame, 0);
                if (!lengthSeen) reader.varInt();
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
                NettyBridge.removeSelf(ctx);
                return rewritten;
            } catch (Throwable t) {
                log.error("Failed to declare the profile API, serving the status unchanged", t);
                return msg;
            }
        }
    }

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
