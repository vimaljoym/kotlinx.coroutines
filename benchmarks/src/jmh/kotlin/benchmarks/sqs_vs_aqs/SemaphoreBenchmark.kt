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

    private lateinit var javaFairReentrantLock: ReentrantLock
    private lateinit var javaUnfairReentrantLock: ReentrantLock
    private lateinit var javaFairSemaphore: Semaphore
    private lateinit var javaUnfairSemaphore: Semaphore
    private lateinit var sqsSemaphoreSync: SQSSemaphoreSync
    private lateinit var sqsSemaphoreSyncWithBackoff: SQSSemaphoreSync
    private lateinit var sqsSemaphoreAsync: SQSSemaphoreAsync
    private lateinit var sqsSemaphoreAsyncWithBackoff: SQSSemaphoreAsync

    @Setup
    fun setup() {
        javaFairReentrantLock = ReentrantLock(true)
        javaUnfairReentrantLock = ReentrantLock(false)
        javaFairSemaphore = Semaphore(permits, true)
        javaUnfairSemaphore = Semaphore(permits, false)
        sqsSemaphoreSync = SQSSemaphoreSync(permits, false)
        sqsSemaphoreSyncWithBackoff = SQSSemaphoreSync(permits, true)
        sqsSemaphoreAsync = SQSSemaphoreAsync(permits, false)
        sqsSemaphoreAsyncWithBackoff = SQSSemaphoreAsync(permits, true)
    }

    @Benchmark
    fun baseline() = benchmark({}, {})

    // @Benchmark
    fun javaReentrantLockFair() =
        if (permits == 1) benchmark({ javaFairReentrantLock.lock() }, { javaFairReentrantLock.unlock() })
        else {}

    // @Benchmark
    fun javaReentrantLockUnfair() =
        if (permits == 1) benchmark({ javaUnfairReentrantLock.lock() }, { javaUnfairReentrantLock.unlock() })
        else {}

    @Benchmark
    fun javaSemaphoreFair() = benchmark({ javaFairSemaphore.acquire() }, { javaFairSemaphore.release() })

    @Benchmark
    fun javaSemaphoreUnfair() = benchmark({ javaUnfairSemaphore.acquire() }, { javaUnfairSemaphore.release() })

    @Benchmark
    fun sqsSemaphoreSync() = benchmark({ sqsSemaphoreSync.acquire() }, { sqsSemaphoreSync.release() })

    @Benchmark
    fun sqsSemaphoreAsync() = benchmark({ sqsSemaphoreAsync.acquire() }, { sqsSemaphoreAsync.release() })

    @Benchmark
    fun sqsSemaphoreSyncWithBackoff() = benchmark({ sqsSemaphoreSyncWithBackoff.acquire() }, { sqsSemaphoreSyncWithBackoff.release() })

    @Benchmark
    fun sqsSemaphoreAsyncWithBackoff() = benchmark({ sqsSemaphoreAsyncWithBackoff.acquire() }, { sqsSemaphoreAsyncWithBackoff.release() })

    private inline fun benchmark(crossinline acquire: () -> Unit, crossinline release: () -> Unit) {
        val phaser = Phaser(threads)
        repeat(threads) {
            thread {
                repeat(TOTAL_OPERATIONS / threads) {
                    acquire()
                    doGeomDistrWork(workIn)
                    release()
                    doGeomDistrWork(workOut)
                }
                phaser.arrive()
            }
        }
        phaser.awaitAdvance(0)
    }
}

private const val TOTAL_OPERATIONS = 100_000