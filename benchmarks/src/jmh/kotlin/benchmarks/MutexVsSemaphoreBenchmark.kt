/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks

import benchmarks.common.*
import benchmarks.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.scheduling.*
import kotlinx.coroutines.sync.*
import kotlinx.coroutines.sync.Semaphore
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*

@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
open class MutexVsSemaphoreBenchmark {
    // @Param("1", "2", "4", "8", "16", "32", "64", "128")
    @Param("1", "2", "4", "8", "16")
    private var threads = 0

//    @Param("1000", "10000")
    @Param("1000")
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
    fun sqsSync() {
        val s = Semaphore(permits = 1)
        benchmark( { s.acquire() }, { s.release() })
    }

    @Benchmark
    fun sqsAsync() {
        val s = AsyncSemaphore(permits = 1)
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