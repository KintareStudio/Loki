package io.netty.buffer;

import java.nio.ByteBuffer;

/**
 * Stand-in for Netty's ByteBuf, holding a whole frame.
 * <p>
 * Reads are absolute, and the reader index deliberately starts somewhere other than zero: a real
 * buffer on its way out of a pipeline has usually been read from already, and a peek that ignored
 * that would be reading the wrong bytes.
 */
public class ByteBuf {
    private final byte[] backing;
    private final int readerIndex;

    public ByteBuf(byte[] frame, int leadingSlack) {
        this.backing = new byte[frame.length + leadingSlack];
        this.readerIndex = leadingSlack;
        System.arraycopy(frame, 0, backing, leadingSlack, frame.length);
    }

    public int readerIndex() {
        return readerIndex;
    }

    public int readableBytes() {
        return backing.length - readerIndex;
    }

    public void getBytes(int index, byte[] destination) {
        System.arraycopy(backing, index, destination, 0, destination.length);
    }

    /** An overload the bridge must not pick when it is handed a byte array. */
    public void getBytes(int index, ByteBuffer destination) {
        throw new UnsupportedOperationException("wrong getBytes overload");
    }
}
