package io.netty.channel;

/** Stand-in for Netty's ChannelHandlerContext. See {@link ChannelHandler}. */
public interface ChannelHandlerContext {
    ChannelPipeline pipeline();

    ChannelHandler handler();

    /** Overloaded on purpose: the bridge has to pick between these two by argument count. */
    void write(Object msg);

    void write(Object msg, ChannelPromise promise);

    void flush();
}
