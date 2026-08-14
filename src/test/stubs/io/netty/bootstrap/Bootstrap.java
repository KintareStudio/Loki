package io.netty.bootstrap;

import java.net.SocketAddress;

/**
 * Stand-in for Netty's Bootstrap, so NettyConnectTransformer can be exercised without pulling
 * Netty into the build. Only the shape of the connect overloads matters; the transformer matches
 * on parameter types and ignores the return type, since 1.7.x relocates Netty's own classes.
 */
public class Bootstrap {
    public Object connect(SocketAddress remoteAddress) {
        return "future:" + remoteAddress;
    }

    public Object connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return "future:" + remoteAddress + "/" + localAddress;
    }

    public Object connect(String host, int port) {
        return "future:" + host + ":" + port;
    }
}
