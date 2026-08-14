package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * Working stand-ins for the Netty objects a connect hands back, enough to install a handler and
 * push one packet through it.
 * <p>
 * These are nested on purpose. {@code NettyBridge} finds Netty by looking for an interface named
 * {@code ChannelPipeline} and taking the package off it, so the interfaces have to be top level and
 * correctly named, while the implementations behind them can be anything at all.
 */
public final class Fake {
    private Fake() {}

    public static final class Future {
        private final Channel channel;

        public Future(Channel channel) {
            this.channel = channel;
        }

        public Channel channel() {
            return channel;
        }

        public String toString() {
            return "future:" + channel;
        }
    }

    public static final class Channel {
        private final Pipeline pipeline = new Pipeline();
        private final String label;

        public Channel(String label) {
            this.label = label;
        }

        public Pipeline pipeline() {
            return pipeline;
        }

        public String toString() {
            return label;
        }
    }

    public static final class Pipeline implements ChannelPipeline {
        private final List<ChannelHandler> handlers = new ArrayList<ChannelHandler>();

        public ChannelPipeline addFirst(String name, ChannelHandler handler) {
            handlers.add(0, handler);
            return this;
        }

        public ChannelPipeline remove(ChannelHandler handler) {
            handlers.remove(handler);
            return this;
        }

        public ChannelHandler remove(String name) {
            throw new UnsupportedOperationException("wrong remove overload");
        }

        public int size() {
            return handlers.size();
        }

        /** Pushes a message out through every handler currently installed, head last. */
        public void write(Object msg) {
            List<ChannelHandler> snapshot = new ArrayList<ChannelHandler>(handlers);
            for (int i = 0; i < snapshot.size(); i++) {
                ChannelHandler handler = snapshot.get(i);
                if (handler instanceof ChannelOutboundHandler) {
                    ((ChannelOutboundHandler) handler).write(new Context(this, handler), msg, null);
                }
            }
        }
    }

    public static final class Context implements ChannelHandlerContext {
        private final ChannelPipeline pipeline;
        private final ChannelHandler handler;

        public Context(ChannelPipeline pipeline, ChannelHandler handler) {
            this.pipeline = pipeline;
            this.handler = handler;
        }

        public ChannelPipeline pipeline() {
            return pipeline;
        }

        public ChannelHandler handler() {
            return handler;
        }

        public void write(Object msg) {
            throw new UnsupportedOperationException("wrong write overload");
        }

        public void write(Object msg, ChannelPromise promise) {
            // The far end of the pipeline. Nothing to do but accept it.
        }

        public void flush() {
        }
    }
}
