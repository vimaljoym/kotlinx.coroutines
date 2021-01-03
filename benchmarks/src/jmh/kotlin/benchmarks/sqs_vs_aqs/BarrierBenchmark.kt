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
    @Param("0", "100", "1000")
    private var work = 0

    @Param("1", "2", "4", "8", "16", "32", "64", "128", "144", "256", "512")
    private var threads = 0

    private lateinit var javaBarriers: Array<CyclicBarrier>
    private lateinit var sqsBarriers: Array<SQSBarrier>

    @Setup
    fun setup() {
        javaBarriers = Array(TOTAL_AWAITS / threads) { CyclicBarrier(threads) }
        sqsBarriers = Array(TOTAL_AWAITS / threads) { SQSBarrier(threads) }
    }

    @Benchmark
    fun java() = benchmark {
        javaBarriers[it].await()
    }

    @Benchmark
    fun sqs() = benchmark {
        sqsBarriers[it].arrive()
    }

    private inline fun benchmark(crossinline awaitAction: (index: Int) -> Unit) {
        val threads = (1..threads).map {
            thread {
                repeat(TOTAL_AWAITS / threads) {
                    awaitAction(it)
                    doGeomDistrWork(work)
                }
            }
        }
        threads.forEach { it.join() }
    }
}

private const val TOTAL_AWAITS = 1_000_000

