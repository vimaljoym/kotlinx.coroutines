/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import benchmarks.common.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*
import kotlin.concurrent.*

@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
open class CountDownLatchBenchmark {
    // @Param("50", "100", "200")
    @Param("100")
    private var work = 0

    // @Param("1", "2", "4", "8", "16", "32", "64", "128")
    @Param("1", "2", "4", "8", "16")
    private var threads = 0

    // @Param("1", "100", "1000")
    @Param("100")
    private var waiters = 0

    @Benchmark
    fun baseline() {
        val phaser = Phaser(threads)
        repeat(threads) {
            thread {
                repeat(TOTAL_OPERATIONS / threads) {
                    doGeomDistrWork(work)
                }
                phaser.arrive()
            }
        }
        phaser.awaitAdvance(0)
    }

    @Benchmark
    fun java() {
        val cdl = CountDownLatch(TOTAL_OPERATIONS / threads * threads)
        benchmark({ cdl.await() }, { cdl.countDown() })
    }

    @Benchmark
    fun sqs() {
        val cdl = SQSCountDownLatch(TOTAL_OPERATIONS / threads * threads, false)
        benchmark({ cdl.await() }, { cdl.countDown() })
    }

    private inline fun benchmark(crossinline await: () -> Unit, crossinline countDown: () -> Unit) {
        val phaser = Phaser(waiters)
        repeat(waiters) {
            thread {
                await()
                phaser.arrive()
            }
        }
        repeat(threads) {
            thread {
                repeat(TOTAL_OPERATIONS / threads) {
                    doGeomDistrWork(work)
                    countDown()
                }
            }
        }
        phaser.awaitAdvance(0)
    }
}

private const val TOTAL_OPERATIONS = 100_000