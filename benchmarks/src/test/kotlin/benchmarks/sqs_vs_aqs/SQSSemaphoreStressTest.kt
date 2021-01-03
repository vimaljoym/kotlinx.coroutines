/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.coroutines.*
import kotlin.concurrent.*
import kotlin.test.*

class SQSSemaphoreStressTest {
    @Test(timeout = 30_000)
    fun testSync() {
        val s = SQSSemaphoreSync(1, true)
        var c = 0
        (1..T).map {
            thread {
                repeat(N / T) {
                    s.acquire()
                    c++
                    s.release()
                }
            }
        }.forEach { it.join() }
        assertEquals(N / T * T, c)
    }

    @Test(timeout = 30_000)
    fun testAsync() {
        val s = SQSSemaphoreAsync(1, true)
        var c = 0
        (1..T).map {
            thread {
                repeat(N / T) {
                    s.acquire()
                    c++
                    s.release()
                }
            }
        }.forEach { it.join() }
        assertEquals(N / T * T, c)
    }
}

private val T = 5 * stressTestMultiplierSqrt
private val N = 50_000 * stressTestMultiplierSqrt