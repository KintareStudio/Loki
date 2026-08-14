package io.netty.channel;

/** Stand-in for Netty's ChannelOutboundHandler. See {@link ChannelHandler}. */
public interface ChannelOutboundHandler extends ChannelHandler {
    void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise);

    void flush(ChannelHandlerContext ctx);

    /** Netty asks for this as soon as a channel goes active, before anything is ever written. */
    void read(ChannelHandlerContext ctx);
}
