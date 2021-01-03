/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.atomicfu.*
import kotlinx.coroutines.internal.*

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal class SQSBarrier(
    private val parties: Int,
    @Suppress("CANNOT_OVERRIDE_INVISIBLE_MEMBER")
    override val useBackoff: Boolean
) : SegmentQueueSynchronizer<Unit>() {
    @Suppress("CANNOT_OVERRIDE_INVISIBLE_MEMBER")
    override val resumeMode get() = ResumeMode.ASYNC

    private val arrived = atomic(0L)

    fun arrive() {
        if (arrived.value > parties) error("TODO")
        val a = arrived.incrementAndGet()
        when {
            // Should we suspend?
            a < parties -> {
                suspendCurrentThread()
            }
            // Are we the last party?
            a == parties.toLong() -> {
                // Resume all waiters.
                repeat(parties - 1) {
                    resume(Unit)
                }
            }
        }
    }
}