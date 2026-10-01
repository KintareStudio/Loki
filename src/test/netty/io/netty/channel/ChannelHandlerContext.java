package io.netty.channel;

/**
 * Stand-in for Netty's ChannelHandlerContext. See {@link ChannelHandler}.
 * <p>
 * Extends a package private interface on purpose, because Netty 4.0 does: see
 * {@link ChannelOutboundInvoker} for why that matters.
 */
public interface ChannelHandlerContext extends ChannelOutboundInvoker {
    Fake.Channel channel();

    ChannelPipeline pipeline();

    ChannelHandler handler();

    /** Overloaded on purpose: the bridge has to pick between these two by argument count. */
    void write(Object msg);

    void write(Object msg, ChannelPromise promise);

    void flush();
}
