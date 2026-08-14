package io.netty.bootstrap;

import io.netty.channel.Fake;

import java.net.SocketAddress;

/**
 * Stand-in for Netty's Bootstrap, so NettyConnectTransformer can be exercised without pulling
 * Netty into the build. Only the shape of the connect overloads matters; the transformer matches
 * on parameter types and ignores the return type, since 1.7.x relocates Netty's own classes.
 * <p>
 * This one lives apart from the other stand-ins and off the test classpath, because the test has to
 * load the patched bytes for it rather than these.
 */
public class Bootstrap {
    public Object connect(SocketAddress remoteAddress) {
        return new Fake.Future(new Fake.Channel(String.valueOf(remoteAddress)));
    }

    public Object connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return new Fake.Future(new Fake.Channel(remoteAddress + "/" + localAddress));
    }

    public Object connect(String host, int port) {
        return new Fake.Future(new Fake.Channel(host + ":" + port));
    }
}
