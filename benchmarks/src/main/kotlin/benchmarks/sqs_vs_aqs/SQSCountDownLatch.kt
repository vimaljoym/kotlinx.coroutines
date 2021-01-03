/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.atomicfu.*
import kotlinx.coroutines.internal.*

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal class SQSCountDownLatch(count: Int) : SegmentQueueSynchronizer<Unit>() {
    @Suppress("CANNOT_OVERRIDE_INVISIBLE_MEMBER")
    override val resumeMode get() = ResumeMode.ASYNC

    private val count = atomic(count)
    private val waiters = atomic(0)

    fun countDown() {
        val r = count.decrementAndGet()
        if (r <= 0) resumeWaiters()
    }

    private fun resumeWaiters() {
        val w = waiters.getAndUpdate { cur ->
            if (cur and DONE_MARK != 0) return
            cur or DONE_MARK
        }
        repeat(w) { resume(Unit) }
    }

    fun await() {
        if (count.value <= 0) return
        val w = waiters.incrementAndGet()
        if (w and DONE_MARK != 0) return
        suspendCurrentThread()
    }
}

private const val DONE_MARK = 1 shl 31
