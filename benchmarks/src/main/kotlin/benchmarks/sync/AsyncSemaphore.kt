/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sync

import kotlinx.coroutines.sync.*

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal class AsyncSemaphore(permits: Int) : SemaphoreImpl(permits = permits, acquiredPermits = 0) {
    @Suppress("CANNOT_OVERRIDE_INVISIBLE_MEMBER")
    override val resumeMode get() = ResumeMode.ASYNC
}