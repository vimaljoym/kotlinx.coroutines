/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.coroutines.*
import kotlin.concurrent.*
import kotlin.test.*

class SQSBarrierStressTest {
    @Test(timeout = 30_000)
    fun test() {
        val barriers = Array(N) { SQSBarrier(T, true) }
        (1..T).map { t ->
            thread {
                repeat(N) {
                    barriers[it].arrive()
                }
            }
        }.forEach { it.join() }
    }
}

private val T = 5 * stressTestMultiplierSqrt
private val N = 50_000 * stressTestMultiplierSqrt