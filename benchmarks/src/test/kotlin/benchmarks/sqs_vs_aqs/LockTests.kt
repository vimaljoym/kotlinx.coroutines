/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlin.concurrent.*
import kotlin.test.*

class LockTests {
    @Test(timeout = 1_000)
    fun clhLockTest() {
        val lock = CLHLock()
        lockTest({ lock.lock() }, {  lock.unlock() })
    }

    @Test(timeout = 1_000)
    fun mcsLockTest() {
        val lock = MCSLock()
        lockTest({ lock.lock() }, {  lock.unlock() })
    }

    private fun lockTest(lock: () -> Unit, unlock: () -> Unit) {
        var c = 0
        (1..THREADS).map {
            thread {
                repeat(N) {
                    lock()
                    c++
                    unlock()
                }
            }
        }.forEach { it.join() }
        assertEquals(N * THREADS, c)
    }
}
private val THREADS = 10
private val N = 100_000