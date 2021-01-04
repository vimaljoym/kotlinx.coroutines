/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import benchmarks.common.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.concurrent.*

@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
open class BarrierBenchmark {
    // @Param("100", "1000")
    @Param("100")
    private var work = 0

    // @Param("1", "2", "4", "8", "16", "32", "64", "128")
    @Param("1", "2", "4", "8", "16")
    private var threads = 0

    @Benchmark
    fun counter() {
        val t = threads
        val count = AtomicLong()
        benchmark { round ->
            var c = count.incrementAndGet()
            while (c < t * (round + 1)) {
                c = count.get()
            }
        }
    }

    @Benchmark
    fun java() {
        val barriers = Array(TOTAL_AWAITS / threads) { CyclicBarrier(threads) }
        benchmark {
            barriers[it].await()
        }
    }

    @Benchmark
    fun sqs() {
        val barriers = Array(TOTAL_AWAITS / threads) { SQSBarrier(threads, false) }
        benchmark {
            barriers[it].arrive()
        }
    }

    private inline fun benchmark(crossinline await: (index: Int) -> Unit) {
        val phaser = Phaser(threads)
        repeat(threads) {
            thread {
                repeat(TOTAL_AWAITS / threads) { op ->
                    await(op)
                    doGeomDistrWork(work)
                }
                phaser.arrive()
            }
        }
        phaser.awaitAdvance(0)
    }
}

private const val TOTAL_AWAITS = 100_000

