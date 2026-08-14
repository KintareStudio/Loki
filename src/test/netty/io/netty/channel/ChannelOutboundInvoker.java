package io.netty.channel;

/**
 * Stand-in for Netty's ChannelOutboundInvoker, and package private exactly as Netty 4.0 has it.
 * <p>
 * That detail is the whole reason this file exists. {@code read()} is declared here rather than on
 * {@link ChannelHandlerContext}, so a bridge that only looks at public types cannot find it, throws
 * when Netty asks a handler to read, and leaves the connection unserved — which is precisely what
 * happened on 1.7 servers. Netty 4.1 made this interface public, which is why nothing newer showed
 * the fault.
 */
interface ChannelOutboundInvoker {
    void read();
}
