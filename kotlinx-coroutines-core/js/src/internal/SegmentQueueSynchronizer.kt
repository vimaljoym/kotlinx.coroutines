/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

internal actual fun resumeWaiter(waiter: Any): Unit = error("Not supported")
internal actual fun suspendWaiter(waiter: Any): Unit = error("Not supported")