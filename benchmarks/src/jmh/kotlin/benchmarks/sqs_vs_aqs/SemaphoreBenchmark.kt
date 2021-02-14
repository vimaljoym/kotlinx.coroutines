/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import benchmarks.common.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*
import java.util.concurrent.locks.*
import kotlin.concurrent.*

@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
open class SemaphoreBenchmark {
    // @Param("1", "2", "4", "8", "16", "32", "64", "128")
    @Param("1", "2", "4", "8", "16")
    private var threads = 0

    // @Param("1", "4", "16", "64")
    @Param("1", "4")
    private var permits: Int = 0

    // @Param("50", "100", "200")
    @Param("100")
    private var workIn: Int = 0

    // @Param("50", "100", "200")
    @Param("100")
    private var workOut: Int = 0

    @Benchmark
    fun baseline() = benchmark({}, {})

    // @Benchmark
    fun javaReentrantLockFair() {
        if (permits != 1) return
        val lock = ReentrantLock(true)
        benchmark({ lock.lock() }, { lock.unlock() })
    }

    // @Benchmark
    fun javaReentrantLockUnfair() {
        if (permits != 1) return
        val lock = ReentrantLock(false)
        benchmark({ lock.lock() }, { lock.unlock() })
    }

    // @Benchmark
    fun clhLock() {
        if (permits != 1) return
        val lock = CLHLock()
        benchmark({ lock.lock() }, { lock.unlock() })
    }

    // @Benchmark
    fun mcsLock() {
        if (permits != 1) return
        val lock = MCSLock()
        benchmark({ lock.lock() }, { lock.unlock() })
    }

    @Benchmark
    fun javaSemaphoreFair() {
        val semaphore = Semaphore(permits, true)
        benchmark({ semaphore.acquire() }, { semaphore.release() })
    }

    @Benchmark
    fun javaSemaphoreUnfair() {
        val semaphore = Semaphore(permits, false)
        benchmark({ semaphore.acquire() }, { semaphore.release() })
    }

    @Benchmark
    fun sqsSemaphoreSync() {
        val semaphore = SQSSemaphoreSync(permits, false)
        benchmark({ semaphore.acquire() }, { semaphore.release() })
    }

    @Benchmark
    fun sqsSemaphoreAsync() {
        val semaphore = SQSSemaphoreAsync(permits, false)
        benchmark({ semaphore.acquire() }, { semaphore.release() })
    }

    @Benchmark
    fun sqsSemaphoreSyncWithBackoff() {
        val semaphore = SQSSemaphoreSync(permits, true)
        benchmark({ semaphore.acquire() }, { semaphore.release() })
    }

    @Benchmark
    fun sqsSemaphoreAsyncWithBackoff() {
        val semaphore = SQSSemaphoreAsync(permits, true)
        benchmark({ semaphore.acquire() }, { semaphore.release() })    }

    private inline fun benchmark(crossinline acquire: () -> Unit, crossinline release: () -> Unit) {
        val cdl = CountDownLatch(threads)
        repeat(threads) {
            thread {
                repeat(TOTAL_OPERATIONS / threads) {
                    acquire()
                    doGeomDistrWork(workIn)
                    release()
                    doGeomDistrWork(workOut)
                }
                cdl.countDown()
            }
        }
        cdl.await()
    }
}

private const val TOTAL_OPERATIONS = 1_000_000