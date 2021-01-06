/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlin.concurrent.*
import kotlin.test.*

internal class BlockingPoolTest {
    @Test(timeout = 1000)
    fun testSimpleStack() = testSimple(StackBlockingPool())

    @Test(timeout = 10_000)
    fun testStressStack() = testStress(StackBlockingPool())

    @Test(timeout = 1000)
    fun testSimpleQueue() = testSimple(QueueBlockingPool())

    @Test(timeout = 10_000)
    fun testStressQueue() = testStress(QueueBlockingPool())

    private fun testSimple(p: AbstractBlockingPool<Int>) {
        p.put(10)
        assertEquals(p.take(), 10)
        thread {
            p.put(14)
        }
        assertEquals(p.take(), 14)
    }

    private fun testStress(p: AbstractBlockingPool<Int>) {
        repeat(T / 2) {
            p.put(it)
        }
        (1..T).map {
            thread {
                repeat(N) {
                    p.put(p.take())
                }
            }
        }.forEach { it.join() }
    }
}

private val T = 10
private val N = 100_000