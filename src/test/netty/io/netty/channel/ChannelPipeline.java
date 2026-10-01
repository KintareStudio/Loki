package io.netty.channel;

/**
 * Stand-in for Netty's ChannelPipeline. See {@link ChannelHandler}.
 * <p>
 * The overloads matter here. {@code remove} takes a handler, a name or a class in real Netty, and
 * picking the wrong one is precisely the mistake the bridge's overload resolution has to not make.
 */
public interface ChannelPipeline {
    ChannelPipeline addFirst(String name, ChannelHandler handler);

    ChannelPipeline remove(ChannelHandler handler);

    ChannelHandler remove(String name);
}
