/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.atomicfu.*
import java.util.concurrent.locks.*

private class QNode(resumed: Boolean = false) {
    private val _thread = atomic<Any?>(if (resumed) Unit else null)
    private fun setCurThread() = _thread.compareAndSet(null, Thread.currentThread())
    fun resumeThread() {
        val t = _thread.getAndSet(Unit)
        when (t) {
            null -> return
            is Thread -> LockSupport.unpark(t)
            else -> error(t)
        }
    }
    fun await() {
        if (!setCurThread()) return
        do {
            LockSupport.park()
        } while (_thread.value !== Unit)
    }

    fun cleanThread() {
        _thread.value = null
    }

    @Volatile
    var next: QNode? = null
}

internal class CLHLock {
    private val tail = atomic(QNode(resumed = true))
    private val myPred = ThreadLocal<QNode>()
    private val myNode = ThreadLocal.withInitial { QNode() }

    fun lock() {
        val qnode = myNode.get()
        val pred = tail.getAndSet(qnode)
        myPred.set(pred)
        pred.await()
    }

    fun unlock() {
        val qnode = myNode.get()
        qnode.resumeThread()
        val pred = myPred.get()
        pred.cleanThread()
        myNode.set(pred)
    }
}

internal class MCSLock {
    private val tail = atomic<QNode?>(null)
    private val threadNode = ThreadLocal.withInitial { QNode() }

    fun lock() {
        val node = threadNode.get()
        node.cleanThread()
        val curTail = tail.getAndSet(node)
        if (curTail == null) return
        curTail.next = node
        node.await()
    }

    fun unlock() {
        val node = threadNode.get()
        if (node.next == null) {
            if (tail.compareAndSet(node, null)) return
            while (node.next == null) { }
        }
        node.next!!.resumeThread()
        node.next = null
    }
}