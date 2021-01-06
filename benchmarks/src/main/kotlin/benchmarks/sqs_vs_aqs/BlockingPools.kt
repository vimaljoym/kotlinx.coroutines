/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.sqs_vs_aqs

import kotlinx.atomicfu.*
import kotlinx.coroutines.internal.*

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal abstract class AbstractBlockingPool<T : Any> : SegmentQueueSynchronizer<T>() {
    @Suppress("CANNOT_OVERRIDE_INVISIBLE_MEMBER")
    override val resumeMode get() = ResumeMode.ASYNC

    private val size = atomic(0)

    fun put(element: T) {
        while (true) {
            val s = size.getAndIncrement()
            if (s < 0) {
                resume(element)
                return
            } else {
                if (tryInsert(element)) return
            }
        }
    }

    fun take(): T {
        while (true) {
            val s = size.getAndDecrement()
            if (s > 0) {
                val x = tryRetrieve()
                if (x != null) return x
            } else {
                return suspendCurrentThread()!!
            }
        }
    }

    abstract fun tryInsert(element: T): Boolean
    abstract fun tryRetrieve(): T?
}

internal class StackBlockingPool<T : Any> : AbstractBlockingPool<T>() {
    private val head = atomic<Node<T>?>(null)

    override fun tryInsert(element: T): Boolean = head.loop { h ->
        if (h != null && h.element == null) {
            if (head.compareAndSet(h, h.next)) return false
        } else {
            val newHead = Node(element, h)
            if (head.compareAndSet(h, newHead)) return true
        }
    }

    override fun tryRetrieve(): T? = head.loop { h ->
        if (h == null || h.element == null) {
            val failNode = Node(null, h)
            if (head.compareAndSet(h, failNode)) return null
        } else {
            if (head.compareAndSet(h, h.next)) return h.element
        }
    }
}
private class Node<T>(
    val element: T?,
    val next: Node<T>?
)

internal class QueueBlockingPool<T : Any> : AbstractBlockingPool<T>() {
    private val enqIdx = atomic(0L)
    private val deqIdx = atomic(0L)

    private val enqSegment: AtomicRef<QueueSegment<T>>
    private val deqSegment: AtomicRef<QueueSegment<T>>

    init {
        val segment = QueueSegment<T>(0)
        enqSegment = atomic(segment)
        deqSegment = atomic(segment)
    }

    override fun tryInsert(element: T): Boolean {
        var s = enqSegment.value
        val i = enqIdx.getAndIncrement()
        s = enqSegment.findSegmentAndMoveForward(id = i / 64, startFrom = s)
        return s.trySetValue((i % 64).toInt(), element)
    }

    override fun tryRetrieve(): T? {
        var s = deqSegment.value
        val i = deqIdx.getAndIncrement()
        s = deqSegment.findSegmentAndMoveForward(id = i / 64, startFrom = s)
        return s.tryRetrieveValue((i % 64).toInt())
    }
}

@Suppress("NOTHING_TO_INLINE") // AtomicRef extensions should be inlined
private inline fun <T : Any> AtomicRef<QueueSegment<T>>.findSegmentAndMoveForward(
    id: Long, startFrom: QueueSegment<T>): QueueSegment<T> {
    var cur = startFrom
    while (cur.id < id) cur = cur.next
    if (cur !== startFrom) {
        while (true) {
            val s = this.value
            if (s.id >= cur.id) break
            if (compareAndSet(s, cur)) break
        }
    }
    return cur
}

private class QueueSegment<T : Any>(val id: Long) {
    private val values = atomicArrayOfNulls<Any>(64)
    private val _next = atomic<QueueSegment<T>?>(null)

    val next: QueueSegment<T> get() {
        val cur = _next.value
        if (cur != null) return cur
        _next.compareAndSet(null, QueueSegment<T>(id + 1))
        return _next.value!!
    }

    fun trySetValue(index: Int, value: T): Boolean = values[index].compareAndSet(null, value)

    fun tryRetrieveValue(index: Int): T? {
        repeat(100) {
            val x = values[index].value
            if (x != null) {
                values[index].lazySet(null)
                return x as T
            }
        }
        return values[index].getAndSet(BROKEN) as T?
    }
}
private val BROKEN = Any()

