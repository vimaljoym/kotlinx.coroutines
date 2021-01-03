/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.atomicfu.*
import kotlinx.coroutines.internal.*

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal class SQSSemaphoreSync(permits: Int) : SegmentQueueSynchronizer<Unit>() {
    @Suppress("CANNOT_OVERRIDE_INVISIBLE_MEMBER")
    override val resumeMode get() = ResumeMode.SYNC

    private val availablePermits = atomic(permits)

    fun acquire() {
        while (true) {
            val p = availablePermits.getAndDecrement()
            if (p > 0) return
            if (suspendCurrentThread()) return
        }
    }

    fun release() {
        while (true) {
            val p = availablePermits.getAndIncrement()
            if (p >= 0) return
            if (resume(Unit)) return
        }
    }
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal class SQSSemaphoreAsync(permits: Int) : SegmentQueueSynchronizer<Unit>() {
    @Suppress("CANNOT_OVERRIDE_INVISIBLE_MEMBER")
    override val resumeMode get() = ResumeMode.ASYNC

    private val availablePermits = atomic(permits)

    fun acquire() {
        val p = availablePermits.getAndDecrement()
        if (p > 0) return
        suspendCurrentThread()
    }

    fun release() {
        val p = availablePermits.getAndIncrement()
        if (p >= 0) return
        resume(Unit)
    }
}