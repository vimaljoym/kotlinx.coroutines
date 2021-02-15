/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import java.util.concurrent.*
import kotlin.concurrent.*
import kotlin.test.*

class MCSLockLincheckest : AbstractLincheckTest() {
    private val l = MCSLock()
    private var c = 0

    @Operation
    fun inc(): Int {
        l.lock()
        val res = c++
        l.unlock()
        return res
    }

    override fun extractState() = c
}

class CLHLockLincheckest : AbstractLincheckTest() {
    private val l = CLHLock()
    private var c = 0

    @Operation
    fun inc(): Int {
        l.lock()
        val res = c++
        l.unlock()
        return res
    }

    override fun extractState() = c
}