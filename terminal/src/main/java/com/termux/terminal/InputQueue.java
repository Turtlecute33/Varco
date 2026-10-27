package com.termux.terminal;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Typed input on its way to the remote side (Varco change; upstream uses a blocking {@link ByteQueue}).
 * {@link #offer} never blocks, so a stalled connection cannot block the main thread. Keys and paste use the
 * same queue, so their order stays. Input that does not fit under the byte cap is dropped as one piece.
 */
final class InputQueue {

    /** The item that tells the consumer to stop. */
    private static final byte[] END = new byte[0];

    private final LinkedBlockingQueue<byte[]> mQueue = new LinkedBlockingQueue<>();
    /** Bytes that wait in {@link #mQueue}. */
    private final AtomicLong mBytes = new AtomicLong();
    private final long mCapBytes;
    private volatile boolean mClosed;

    InputQueue(long capBytes) {
        mCapBytes = capBytes;
    }

    /** Queues a copy of the data. False if the data was dropped: the queue is closed, or full. */
    boolean offer(byte[] data, int offset, int count) {
        if (mClosed) return false;
        if (count <= 0) return true;
        if (mBytes.addAndGet(count) > mCapBytes) {
            mBytes.addAndGet(-count);
            return false;
        }
        mQueue.add(Arrays.copyOfRange(data, offset, offset + count));
        return true;
    }

    /** The next piece of input, in order. Waits until there is one. Null after {@link #close}. */
    byte[] take() throws InterruptedException {
        byte[] data = mQueue.take();
        if (data == END) return null;
        mBytes.addAndGet(-data.length);
        return data;
    }

    /** Drops the queued input and stops the consumer. */
    void close() {
        mClosed = true;
        mQueue.clear();
        mQueue.add(END);
    }
}
