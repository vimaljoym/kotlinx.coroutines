/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import benchmarks.common.*
import org.junit.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*
import java.util.concurrent.locks.*
import kotlin.concurrent.*

@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
open class SemaphoreBenchmark {
    @Param("1", "2", "4", "8", "16", "32", "64", "128", "144", "256", "512")
    private var threads = 0

    @Param("1", "2", "4", "8", "32", "128", "144", "100000")
    private var permits: Int = 0

    @Param("50", "70", "100", "200", "400")
    private var workIn: Int = 0

    @Param("50", "70", "100", "200", "400")
    private var workOut: Int = 0

    private lateinit var javaFairReentrantLock: ReentrantLock
    private lateinit var javaUnfairReentrantLock: ReentrantLock
    private lateinit var javaFairSemaphore: Semaphore
    private lateinit var javaUnfairSemaphore: Semaphore
    private lateinit var sqsSemaphoreSync: SQSSemaphoreSync
    private lateinit var sqsSemaphoreAsync: SQSSemaphoreAsync

    @Before
    fun setup() {
        javaFairReentrantLock = ReentrantLock(true)
        javaUnfairReentrantLock = ReentrantLock(false)
        javaFairSemaphore = Semaphore(permits, true)
        javaUnfairSemaphore = Semaphore(permits, false)
        sqsSemaphoreSync = SQSSemaphoreSync(permits)
        sqsSemaphoreAsync = SQSSemaphoreAsync(permits)
    }

    @Benchmark
    fun javaReentrantLockFair() =
        if (threads == 1) benchmark({ javaFairReentrantLock.lock() }, { javaFairReentrantLock.unlock() })
        else benchmark({}, {})

    @Benchmark
    fun javaReentrantLockUnfair() =
        if (threads == 1) benchmark({ javaUnfairReentrantLock.lock() }, { javaUnfairReentrantLock.unlock() })
        else benchmark({}, {})

    @Benchmark
    fun javaSemaphoreFair() = benchmark({ javaFairSemaphore.acquire() }, { javaFairSemaphore.release() })

    @Benchmark
    fun javaSemaphoreUnfair() = benchmark({ javaUnfairSemaphore.acquire() }, { javaUnfairSemaphore.release() })

    @Benchmark
    fun sqsSemaphoreSync() = benchmark({ sqsSemaphoreSync.acquire() }, { sqsSemaphoreSync.release() })

    @Benchmark
    fun sqsSemaphoreAsync() = benchmark({ sqsSemaphoreAsync.acquire() }, { sqsSemaphoreAsync.release() })

    private inline fun benchmark(crossinline acquire: () -> Unit, crossinline release: () -> Unit) {
        (1..threads).map {
            thread {
                repeat(TOTAL_OPERATIONS / threads) {
                    acquire()
                    doGeomDistrWork(workIn)
                    release()
                    doGeomDistrWork(workOut)
                }
            }
        }.forEach { it.join() }
    }
}

private const val TOTAL_OPERATIONS = 1_000_000