/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.atomicfu.*
import java.util.concurrent.locks.*

private class QNode(resumed: Boolean = false) {
    private val _thread = atomic<Any?>(if (resumed) Unit else null)
    fun setCurThread() = _thread.compareAndSet(null, Thread.currentThread())
    fun resumeThread() {
        if (_thread.compareAndSet(null, Unit)) return
        LockSupport.unpark(_thread.value as Thread)
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
        qnode.cleanThread()
        val pred = tail.getAndSet(qnode)
        myPred.set(pred)
        if (pred.setCurThread()) {
            LockSupport.park()
        }
    }

    fun unlock() {
        val qnode = myNode.get()
        qnode.resumeThread()
        val pred = myPred.get()
        pred.cleanThread()
        myNode.set(myPred.get())
    }
}

internal class MCSLock {
    private val tail = atomic<QNode?>(null)
    private val myNode = ThreadLocal.withInitial { QNode() }

    fun lock() {
        val node = myNode.get()
        node.cleanThread()
        val pred = tail.getAndSet(node)
        if (pred == null) return
        pred.next = node
        if (node.setCurThread()) {
            LockSupport.park()
        }
    }

    fun unlock() {
        val node = myNode.get()
        if (node.next == null) {
            if (tail.compareAndSet(node, null)) return
            while (node.next == null) { }
        }
        node.next!!.resumeThread()
        node.next = null
    }
}