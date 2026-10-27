package com.termux.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** InputQueue (terminal/NOTICE.md): typed input that never blocks the main thread. */
class InputQueueTest {
    private fun InputQueue.offer(text: String) = text.toByteArray().let { offer(it, 0, it.size) }

    @Test
    fun keepsTheOrderAndCopiesTheData() {
        val queue = InputQueue(100)
        val buffer = "ab".toByteArray()
        assertTrue(queue.offer(buffer, 1, 1))
        buffer[1] = 'x'.code.toByte()
        assertTrue(queue.offer("cd"))
        assertContentEquals("b".toByteArray(), queue.take())
        assertContentEquals("cd".toByteArray(), queue.take())
    }

    @Test
    fun dropsInputOverTheCapAsOnePiece() {
        val queue = InputQueue(4)
        assertTrue(queue.offer("abc"))
        assertFalse(queue.offer("de"))
        assertTrue(queue.offer("d"))
        assertFalse(queue.offer("e"))
        assertContentEquals("abc".toByteArray(), queue.take())
        // Taken bytes free the space again.
        assertTrue(queue.offer("efg"))
        assertContentEquals("d".toByteArray(), queue.take())
        assertContentEquals("efg".toByteArray(), queue.take())
    }

    @Test
    fun closeDropsQueuedInputAndStopsTheConsumer() {
        val queue = InputQueue(100)
        assertTrue(queue.offer("abc"))
        queue.close()
        assertNull(queue.take())
        assertFalse(queue.offer("d"))
    }
}
