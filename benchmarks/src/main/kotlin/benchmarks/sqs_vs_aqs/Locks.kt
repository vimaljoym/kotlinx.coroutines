/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.atomicfu.*

private class QNode(released: Boolean = false) {
    @Volatile
    var released: Boolean = released

    @Volatile
    var next: QNode? = null
}

internal class CLHLock {
    private val tail = atomic(QNode(released = true))
    private val myPred = ThreadLocal<QNode>()
    private val myNode = ThreadLocal.withInitial { QNode() }

    fun lock() {
        val qnode = myNode.get()
        val pred = tail.getAndSet(qnode)
        myPred.set(pred)
        while (!pred.released) { }
    }

    fun unlock() {
        val qnode = myNode.get()
        qnode.released = true
        val pred = myPred.get()
        pred.released = false
        myNode.set(myPred.get())
    }
}

internal class MCSLock {
    private val tail = atomic<QNode?>(null)
    private val myNode = ThreadLocal.withInitial { QNode() }

    fun lock() {
        val node = myNode.get()
        node.released = false
        val pred = tail.getAndSet(node)
        if (pred == null) return
        pred.next = node
        while (!node.released) {}
    }

    fun unlock() {
        val node = myNode.get()
        if (node.next == null) {
            if (tail.compareAndSet(node, null)) return
            while (node.next == null) { }
        }
        node.next!!.released = true
        node.next = null
    }
}