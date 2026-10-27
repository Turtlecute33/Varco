package app.varco.ui.lock

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AppLock timing with a fake clock. */
class AppLockTest {
    private var enabled = true
    private var timeout = 60
    private var now = 1_000L
    private val lock = AppLock({ enabled }, { timeout }, { now })

    @Test
    fun locksAtStartOnlyWhenOn() {
        lock.onLoaded()
        assertTrue(lock.locked.value)
        lock.unlock()
        assertFalse(lock.locked.value)

        enabled = false
        val off = AppLock({ enabled }, { timeout }, { now })
        off.onLoaded()
        assertFalse(off.locked.value)
    }

    @Test
    fun locksWhenTheTimeoutHasPassedInTheBackground() {
        lock.onLoaded()
        lock.unlock()
        lock.onBackground()
        now += 59_999
        lock.onForeground()
        assertFalse(lock.locked.value)

        lock.onBackground()
        now += 60_000
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun zeroTimeoutLocksOnEachOpen() {
        timeout = 0
        lock.onLoaded()
        lock.unlock()
        lock.onBackground()
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun staysLockedWhenTheLockedUiGoesToTheBackground() {
        lock.onLoaded()
        lock.onBackground()
        lock.onForeground()
        assertTrue(lock.locked.value)
        // The time in the background while locked does not count as unlocked time.
        lock.onBackground()
        now += 1_000_000
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun turnedOffInTheBackgroundOpensWithoutALock() {
        lock.onLoaded()
        lock.unlock()
        lock.onBackground()
        now += 120_000
        enabled = false
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun turnedOnWhileOpenLocksAfterTheNextTimeout() {
        enabled = false
        lock.onLoaded()
        enabled = true
        assertFalse(lock.locked.value)
        lock.onBackground()
        now += 60_000
        lock.onForeground()
        assertTrue(lock.locked.value)
    }
}
