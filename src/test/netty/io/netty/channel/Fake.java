package io.netty.channel;

import io.netty.util.concurrent.GenericFutureListener;

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

    /**
     * Netty's close future, which completes once and only once, when the channel does.
     * <p>
     * Its {@code addListener} is what the bridge reads the listener type off, so it is typed here
     * exactly as Netty types it rather than with something convenient.
     */
    public static final class CloseFuture {
        private final List<GenericFutureListener> listeners = new ArrayList<GenericFutureListener>();
        private boolean closed;

        public CloseFuture addListener(GenericFutureListener listener) {
            if (closed) {
                listener.operationComplete(this);
                return this;
            }
            listeners.add(listener);
            return this;
        }

        void complete() {
            if (closed) return;
            closed = true;
            List<GenericFutureListener> snapshot = new ArrayList<GenericFutureListener>(listeners);
            for (int i = 0; i < snapshot.size(); i++) {
                snapshot.get(i).operationComplete(this);
            }
        }
    }

    public static final class Channel {
        private final Pipeline pipeline;
        private final CloseFuture closeFuture = new CloseFuture();
        private final String label;

        public Channel(String label) {
            this.label = label;
            this.pipeline = new Pipeline(this);
        }

        public Pipeline pipeline() {
            return pipeline;
        }

        public CloseFuture closeFuture() {
            return closeFuture;
        }

        /** What the game does when the player disconnects, however that came about. */
        public void close() {
            closeFuture.complete();
        }

        public String toString() {
            return label;
        }
    }

    public static final class Pipeline implements ChannelPipeline {
        private final List<ChannelHandler> handlers = new ArrayList<ChannelHandler>();
        private final Channel channel;

        Pipeline(Channel channel) {
            this.channel = channel;
        }

        public Channel channel() {
            return channel;
        }

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

        /** What Netty does the moment a channel goes active, before a single byte moves. */
        public void read() {
            List<ChannelHandler> snapshot = new ArrayList<ChannelHandler>(handlers);
            for (int i = 0; i < snapshot.size(); i++) {
                ChannelHandler handler = snapshot.get(i);
                if (handler instanceof ChannelOutboundHandler) {
                    ((ChannelOutboundHandler) handler).read(new Context(this, handler));
                }
            }
        }
    }

    /** Set when a read reached the end of the pipeline, which is where Netty would go to the socket. */
    public static volatile boolean readReachedTheSocket;

    // Package private, as Netty 4.0 has it: only the interfaces above are visible from outside,
    // which is what makes the bridge have to look past them
    static final class Context implements ChannelHandlerContext {
        private final ChannelPipeline pipeline;
        private final ChannelHandler handler;

        public Context(ChannelPipeline pipeline, ChannelHandler handler) {
            this.pipeline = pipeline;
            this.handler = handler;
        }

        public void read() {
            readReachedTheSocket = true;
        }

        public Channel channel() {
            return ((Pipeline) pipeline).channel();
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
