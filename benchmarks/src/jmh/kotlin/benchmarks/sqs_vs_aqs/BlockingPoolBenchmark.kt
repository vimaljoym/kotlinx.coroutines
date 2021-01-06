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
open class BlockingPoolBenchmark {
    // @Param("1", "2", "4", "8", "16", "32", "64", "128")
    @Param("1", "2", "4", "8", "16")
    private var threads = 0

//    @Param("1", "2", "4", "8", "16", "32", "64")
    @Param("1", "2", "4")
    private var elements = 0

    // @Param("50", "100", "200")
    @Param("100")
    private var workIn: Int = 0

    // @Param("50", "100", "200")
    @Param("100")
    private var workOut: Int = 0

    @Benchmark
    fun sqsStackBlockingPool() {
        val p = StackBlockingPool<Int>()
        repeat(elements) { p.put(it) }
        benchmark({ p.put(it) }, { p.take() })
    }

    @Benchmark
    fun sqsQueueBlockingPool() {
        val p = QueueBlockingPool<Int>()
        repeat(elements) { p.put(it) }
        benchmark({ p.put(it) }, { p.take() })
    }

    @Benchmark
    fun arrayBlockingQueueUnfair() {
        val q = ArrayBlockingQueue<Int>(elements, false)
        repeat(elements) { q.add(it) }
        benchmark({ q.put(it) }, { q.take() })
    }

    @Benchmark
    fun arrayBlockingQueueFair() {
        val q = ArrayBlockingQueue<Int>(elements, true)
        repeat(elements) { q.add(it) }
        benchmark({ q.put(it) }, { q.take() })
    }

    @Benchmark
    fun linkedBlockingQueueUnfair() {
        val q = LinkedBlockingQueue<Int>(elements)
        repeat(elements) { q.add(it) }
        benchmark({ q.put(it) }, { q.take() })
    }

    private inline fun benchmark(crossinline put: (element: Int) -> Unit, crossinline retrieve: () -> Int) {
        val cdl = CountDownLatch(threads)
        repeat(threads) {
            thread {
                repeat(TOTAL_OPERATIONS / threads) {
                    val elem = retrieve()
                    doGeomDistrWork(workIn)
                    put(elem)
                    doGeomDistrWork(workOut)
                }
                cdl.countDown()
            }
        }
        cdl.await()
    }
}

private const val TOTAL_OPERATIONS = 1_000_000