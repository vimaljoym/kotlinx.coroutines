/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:JvmName("SegmentQueueSynchronizerJvm")

package kotlinx.coroutines.internal

import java.util.concurrent.locks.*

internal actual fun <T> resumeWaiter(waiter: Any, value: T) {
    waiter as? Thread ?: error("Unexpected waiter type")
    LockSupport.unpark(waiter)
}

internal fun SegmentQueueSynchronizer<Unit>.suspendCurrentThread() {
    suspend(Thread.currentThread())
    LockSupport.park()
}