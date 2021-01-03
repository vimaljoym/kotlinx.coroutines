/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import benchmarks.common.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*
import kotlin.concurrent.*

@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
open class BarrierBenchmark {
    // @Param("50", "100", "200")
    @Param("100")
    private var work = 0

    // @Param("1", "2", "4", "8", "16", "32", "64", "128")
    @Param("1", "2", "4", "8", "16")
    private var threads = 0

    private lateinit var javaBarriers: Array<CyclicBarrier>
    private lateinit var sqsBarriers: Array<SQSBarrier>
    private lateinit var sqsBarriersWithBackoff: Array<SQSBarrier>

    @Setup
    fun setup() {
        javaBarriers = Array(TOTAL_AWAITS / threads) { CyclicBarrier(threads) }
        sqsBarriers = Array(TOTAL_AWAITS / threads) { SQSBarrier(threads, false) }
        sqsBarriersWithBackoff = Array(TOTAL_AWAITS / threads) { SQSBarrier(threads, true) }
    }

    @Benchmark
    fun baseline() = benchmark {}

    @Benchmark
    fun java() = benchmark {
        javaBarriers[it].await()
    }

    @Benchmark
    fun sqs() = benchmark {
        sqsBarriers[it].arrive()
    }

    @Benchmark
    fun sqsWithBackoff() = benchmark {
        sqsBarriersWithBackoff[it].arrive()
    }

    private inline fun benchmark(crossinline await: (index: Int) -> Unit) {
        val phaser = Phaser(threads)
        repeat(threads) {
            thread {
                repeat(TOTAL_AWAITS / threads) {
                    await(it)
                    doGeomDistrWork(work)
                }
                phaser.arrive()
            }
        }
        phaser.awaitAdvance(0)
    }
}

private const val TOTAL_AWAITS = 100_000

