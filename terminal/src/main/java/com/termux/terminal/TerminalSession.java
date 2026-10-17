package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session: a terminal emulator coupled to a remote byte stream (Varco change; upstream Termux
 * couples it to a local process).
 * <p>
 * The emulator starts when the view reports its size ({@link #updateSize}). Remote output goes in through
 * {@link #feed} from any thread. Typed input goes out to the current {@link Transport} on a writer thread. A
 * session can outlive its transport: on reconnect, the app sets a new one and the screen and scrollback stay.
 * All terminal emulation and client callbacks run on the main thread.
 */
public final class TerminalSession extends TerminalOutput {

    /** The remote side of a session, for example an SSH shell channel. */
    public interface Transport {
        /** Typed input. Called on the writer thread; may block. */
        OutputStream output();

        /** The terminal size changed. Called on the main thread; must not block. */
        void resize(int columns, int rows, int widthPixels, int heightPixels);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int QUEUE_BYTES = 64 * 1024;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Remote output, written by the transport's reader thread and read by the main thread. */
    final ByteQueue mRemoteToTerminalIOQueue = new ByteQueue(QUEUE_BYTES);
    /** Typed input, written by the main thread and read by the writer thread. */
    final ByteQueue mTerminalToRemoteIOQueue = new ByteQueue(QUEUE_BYTES);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToRemoteIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when the screen or title changes. */
    TerminalSessionClient mClient;

    private volatile Transport mTransport;
    private volatile boolean mClosed;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        new Thread("TermSessionOutputWriter") {
            @Override
            public void run() {
                final byte[] buffer = new byte[QUEUE_BYTES];
                while (true) {
                    int bytesToWrite = mTerminalToRemoteIOQueue.read(buffer, true);
                    if (bytesToWrite == -1) return;
                    Transport transport = mTransport;
                    if (transport == null) continue; // Not connected: typed input is dropped.
                    try {
                        OutputStream out = transport.output();
                        out.write(buffer, 0, bytesToWrite);
                        out.flush();
                    } catch (IOException e) {
                        // The transport reports its own end; input to a closed channel is dropped.
                    }
                }
            }
        }.start();
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Sets the remote side (null = disconnected) and sends it the current size. Main thread. */
    public void setTransport(Transport transport) {
        mTransport = transport;
        if (transport != null && mEmulator != null) {
            transport.resize(mEmulator.mColumns, mEmulator.mRows,
                mEmulator.mColumns * mEmulator.mCellWidthPixels, mEmulator.mRows * mEmulator.mCellHeightPixels);
        }
    }

    /** Inform the remote side of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            // Output that came before the view had a size waits in the queue.
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        Transport transport = mTransport;
        if (transport != null) transport.resize(columns, rows, columns * cellWidthPixels, rows * cellHeightPixels);
    }

    /** Remote output for the emulator. Any thread; blocks while the queue is full. */
    public void feed(byte[] data, int offset, int count) {
        if (!mRemoteToTerminalIOQueue.write(data, offset, count)) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Local status text (for example "Connecting…"), shown as if the remote side sent it. */
    public void feedText(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        feed(bytes, 0, bytes.length);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Write data to the remote side. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (!mClosed) mTerminalToRemoteIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Stops the writer thread and drops queued data. The session cannot be used after this. */
    public void close() {
        mClosed = true;
        mTransport = null;
        mTerminalToRemoteIOQueue.close();
        mRemoteToTerminalIOQueue.close();
    }

    public boolean isClosed() {
        return mClosed;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    /** OSC 52 from the server. The client decides if the server may set the clipboard. */
    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text, true);
    }

    /** Copy from a text selection by the user. */
    public void copySelection(String text) {
        mClient.onCopyTextToClipboard(this, text, false);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[QUEUE_BYTES];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (mEmulator == null) return; // Kept in the queue until the view has a size.
            int bytesRead = mRemoteToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
                // A full read can leave data that no later message covers (one large feed). One read per
                // message keeps the main thread free between chunks.
                if (bytesRead == mReceiveBuffer.length) sendEmptyMessage(MSG_NEW_INPUT);
            }
        }

    }

}
