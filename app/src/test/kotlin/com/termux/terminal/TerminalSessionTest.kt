package com.termux.terminal

import android.os.Message
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TerminalSession (terminal/NOTICE.md): remote output, typed input and a change of transport. On the JVM the
 * main-thread handler does not run by itself, so the test calls it.
 */
class TerminalSessionTest {
    private object Client : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?, fromServer: Boolean) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    /** A remote side that records each write and each size. */
    private class Remote : TerminalSession.Transport {
        val writes = LinkedBlockingQueue<String>()
        val sizes = mutableListOf<List<Int>>()

        override fun output() = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) = writes.put(String(b, off, len, Charsets.UTF_8))
        }

        override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
            sizes += listOf(columns, rows, widthPixels, heightPixels)
        }
    }

    private val session = TerminalSession(100, Client)

    @AfterTest
    fun close() = session.close()

    private fun runMainThreadHandler() = session.mMainThreadHandler.handleMessage(Message())

    @Test
    fun outputBeforeTheViewHasASizeWaitsInTheQueue() {
        session.feedText("Connecting\r\nhello")
        // No emulator yet: the output stays in the queue.
        runMainThreadHandler()
        session.updateSize(20, 4, 10, 20)
        runMainThreadHandler()
        assertEquals("Connecting\nhello", session.emulator.screen.transcriptText)
    }

    @Test
    fun typedInputGoesToTheCurrentTransport() {
        val first = Remote()
        session.setTransport(first)
        session.write("ls\r")
        assertEquals("ls\r", first.writes.poll(5, TimeUnit.SECONDS))

        // A reconnect: the same session, a new transport.
        val second = Remote()
        session.setTransport(second)
        session.write("pwd\r")
        assertEquals("pwd\r", second.writes.poll(5, TimeUnit.SECONDS))
        assertNull(first.writes.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aTransportGetsTheCurrentSizeAndEachChange() {
        session.updateSize(80, 24, 10, 20)
        val remote = Remote()
        session.setTransport(remote)
        session.updateSize(100, 30, 10, 20)
        assertEquals(listOf(listOf(80, 24, 800, 480), listOf(100, 30, 1000, 600)), remote.sizes)
    }

    @Test
    fun closeStopsInputAndOutput() {
        val remote = Remote()
        session.setTransport(remote)
        session.close()
        session.write("exit\r")
        assertNull(remote.writes.poll(100, TimeUnit.MILLISECONDS))
        // The reader thread of a closed connection must not wait for the queue.
        session.feed(ByteArray(200_000), 0, 200_000)
    }
}
