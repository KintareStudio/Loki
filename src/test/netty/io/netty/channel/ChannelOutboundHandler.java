package io.netty.channel;

/** Stand-in for Netty's ChannelOutboundHandler. See {@link ChannelHandler}. */
public interface ChannelOutboundHandler extends ChannelHandler {
    void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise);

    void flush(ChannelHandlerContext ctx);
}
