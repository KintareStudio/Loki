package io.netty.channel;

/**
 * Stand-in for Netty's ChannelHandler.
 * <p>
 * Loki never compiles against Netty, so what the tests need is not Netty itself but something with
 * Netty's shape: the same package layout, the same interface names and the same method names, since
 * those are exactly what {@code NettyBridge} resolves against at runtime.
 */
public interface ChannelHandler {
    void handlerAdded(ChannelHandlerContext ctx);

    void handlerRemoved(ChannelHandlerContext ctx);
}
