/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks

import benchmarks.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.scheduling.*
import kotlinx.coroutines.sync.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*

@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
class MutexVsSemaphoreBenchmark {
    // @Param("1", "2", "4", "8", "16", "18", "32", "36", "54", "64", "72", "90", "108", "128")
    @Param("1", "2", "4", "8", "16")
    private var threads = 0

    @Param("1000", "10000")
    private var coroutines = 0

    // @Param("50", "100", "200")
    @Param("100")
    private var workIn: Int = 0

    // @Param("50", "100", "200")
    @Param("100")
    private var workOut: Int = 0

    private lateinit var dispatcher: CoroutineDispatcher

    @Setup
    fun setup() {
        dispatcher = ExperimentalCoroutineDispatcher(corePoolSize = threads, maxPoolSize = threads)
    }

    @Benchmark
    fun baseline() = benchmark({}, {})

    @Benchmark
    fun kotlinMutex() {
        val m = Mutex()
        benchmark( { m.lock() }, { m.unlock() })
    }

    @Benchmark
    fun sqs() {
        val s = Semaphore(permits = 1)
        benchmark( { s.acquire() }, { s.release() })
    }

    @Benchmark
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    fun sqsBackoff() {
        val s = object : SemaphoreImpl(permits = 1, acquiredPermits = 0) {
            @Suppress("CANNOT_OVERRIDE_INVISIBLE_MEMBER")
            override val useBackoff: Boolean get() = true
        }
        benchmark( { s.acquire() }, { s.release() })
    }

    private inline fun benchmark(crossinline lock: suspend () -> Unit, crossinline unlock: () -> Unit) {
        val phaser = Phaser(coroutines)
        repeat(coroutines) {
            GlobalScope.launch(dispatcher) {
                repeat(TOTAL_OPERATIONS / coroutines) {
                    lock()
                    doGeomDistrWork(workIn)
                    unlock()
                    doGeomDistrWork(workOut)
                }
                phaser.arrive()
            }
        }
        phaser.awaitAdvance(0)
    }
}

private const val TOTAL_OPERATIONS = 100_000