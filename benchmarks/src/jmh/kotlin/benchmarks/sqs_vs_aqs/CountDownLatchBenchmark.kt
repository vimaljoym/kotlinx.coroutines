/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import benchmarks.common.*
import org.junit.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*
import kotlin.concurrent.*

@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
open class CountDownLatchBenchmark {
    @Param("0", "100", "1000")
    private var work = 0

    @Param("1", "2", "4", "8", "16", "32", "64", "128", "144", "256", "512")
    private var threads = 0

    @Param("0", "100", "1000")
    private var waiters = 0

    private lateinit var cdlJava: CountDownLatch
    private lateinit var cdlSqs: SQSCountDownLatch

    @Before
    fun setup() {
        // we have to round the total work
        cdlJava = CountDownLatch(TOTAL_OPERATIONS / threads * threads)
        cdlSqs = SQSCountDownLatch(TOTAL_OPERATIONS / threads * threads)
    }

    @Benchmark
    fun java() = benchmark({ cdlJava.await() }, { cdlJava.countDown() })

    @Benchmark
    fun sqs() = benchmark({ cdlSqs.await() }, { cdlSqs.countDown() })

    private inline fun benchmark(crossinline await: () -> Unit, crossinline countDown: () -> Unit) {
        val waiters = (1..waiters).map {
            thread { await() }
        }
        repeat(threads) {
            thread {
                repeat(TOTAL_OPERATIONS / threads) {
                    doGeomDistrWork(work)
                    countDown()
                }
            }
        }
        waiters.forEach { it.join() }
    }
}

private const val TOTAL_OPERATIONS = 1_000_000