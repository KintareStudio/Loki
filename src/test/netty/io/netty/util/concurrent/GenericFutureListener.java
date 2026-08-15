package io.netty.util.concurrent;

/**
 * Stand-in for Netty's GenericFutureListener, in the package Netty really keeps it in.
 * <p>
 * The package matters: it is not the one the channel types live in, and 1.7.x relocates the whole
 * tree, so nothing may name this interface. The bridge reads it off {@code addListener}'s own
 * signature instead, and this file exists so that path is exercised rather than assumed.
 */
public interface GenericFutureListener {
    void operationComplete(Object future);
}
