/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.coroutines.*
import kotlin.concurrent.*
import kotlin.test.*

class SQSCountDownLatchStressTest {
    @Test(timeout = 30_000)
    fun test() {
        val cdl = SQSCountDownLatch(T * N, true)
        (1..T).map {
            thread {
                repeat(N) { cdl.countDown() }
            }
        }
        val waiter = thread { cdl.await() }
        waiter.join()
    }
}

private val T = 5 * stressTestMultiplierSqrt
private val N = 50_000 * stressTestMultiplierSqrt