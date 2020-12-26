/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.sync

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.selects.*
import kotlinx.coroutines.sync.ReadWriteMutexImpl.WriteUnlockPolicy.*
import kotlin.contracts.*
import kotlin.js.*

/**
 * A readers-writer mutex maintains a logical pair of locks, one for read-only
 * operations that can be processed concurrently, and one for write operations
 * that guarantee exclusive access. It is guaranteed that write and read operations
 * are never processed concurrently.
 *
 * The table below shows which locks can be held simultaneously.
 * +-------------+-------------+-------------+
 * |             | reader lock | writer lock |
 * +-------------+-------------+-------------+
 * | reader lock |     OK!     |  FORBIDDEN  |
 * +-------------+-------------+-------------+
 * | writer lock |  FORBIDDEN  |  FORBIDDEN  |
 * +-------------+-------------+-------------+
 *
 * Similar to [Mutex], this readers-writer mutex is **non-reentrant**,
 * so that invoking [readLock] or [writeLock] even from the coroutine that
 * currently holds the corresponding lock still suspends the invoker.
 * At the same time, invoking [readLock] from the holder of the write lock
 * also suspends the invoker.
 *
 * Typical usage of [ReadWriteMutex] is wrapping each read invocation with
 * [ReadWriteMutex.read] and each write invocation with [ReadWriteMutex.write]
 * correspondingly. These wrapper functions guarantee that the mutex is used correctly and safely.
 * However, one can use `lock` and `unlock` operations directly, but `unlock` should be invoked only
 * after a successful corresponding `lock` invocation.
 *
 * The advantage of using [ReadWriteMutex] comparing to the plain [Mutex] is an availability
 * to parallelize read operations and, therefore, increasing the level of concurrency.
 * It is extremely useful for the workloads with dominating read operations so that they can be
 * executed in parallel and improve the performance. However, depending on the updates' frequency,
 * the execution cost of read and write operations, and the contention, it can be cheaper to use
 * the plain [Mutex].  Therefore, it is highly recommended to measure the performance difference t
 * o make the right choice.
 */
@ExperimentalCoroutinesApi
public interface ReadWriteMutex {
    /**
     * Acquires a reader lock of this mutex if the writer lock is not held and there is no writer
     * waiting for it, suspends the caller otherwise until the write lock is released and
     * this reader is resumed. Please note that the next waiting writer instead of this reader
     * can be released after the currently active writer releases the lock.
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     * There is a **prompt cancellation guarantee**. If the job was cancelled while this function was
     * suspended, it will not resume successfully. See [suspendCancellableCoroutine] documentation for low-level details.
     * This function releases the lock if it was already acquired by this function before the [CancellationException]
     * was thrown.
     *
     * Note that this function does not check for cancellation when it is not suspended.
     * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
     *
     * It is recommended to use [read] for safety reasons, so that the acquired reader lock is always
     * released at the end of your critical section and [readUnlock] is never invoked before a successful
     * reader lock acquisition.
     */
    @ExperimentalCoroutinesApi
    public suspend fun readLock()

    /**
     * Attempts to acquire a reader lock of this mutex if the writer lock is not held and
     * there is no writer waiting for it, return `false` if the lock cannot be acquired immediately.
     */
    @ExperimentalCoroutinesApi
    public fun tryReadLock(): Boolean

    /**
     * Releases a reader lock of this mutex and resumes the first waiting writer
     * if this operation releases the last acquired reader lock.
     *
     * It is recommended to use [read] for safety reasons, so that the acquired reader lock is always
     * released at the end of your critical section and [readUnlock] is never invoked before a successful
     * reader lock acquisition.
     */
    @ExperimentalCoroutinesApi
    public fun readUnlock()

    /**
     * Returns a [Mutex] which manipulates with the writer lock of this [ReadWriteMutex].
     *
     * When acquires the writer lock, the operation completes immediately if neither the writer lock nor
     * a reader one is held, the acquisition suspends the caller otherwise until the exclusive access
     * is granted by either [readUnlock()][readUnlock] or [write.unlock()][Mutex.unlock]. Please note that
     * all suspended writers are processed in first-in-first-out order.
     *
     * Please note that this [Mutex] implementation for writers does not support owners in [lock()][Mutex.lock]
     * and [withLock { ... }][Mutex.withLock] functions, as well as the [onLock][Mutex.onLock] select clause.
     *
     * When releasing the writer lock, the operation resumes the first waiting writer or waiting readers.
     * Note that different fairness policies can be applied by an implementation, such as
     * prioritizing readers or writers and attempting to always resume them at first,
     * choosing the current prioritization by flipping a coin, or providing a truly fair
     * strategy where all waiters, both readers and writers, form a single first-in-first-out queue.
     */
    @ExperimentalCoroutinesApi
    public val write: Mutex
}

/**
 * Creates a new [ReadWriteMutex] instance, both reader and writer locks are not acquired.
 */
@JsName("_ReadWriteMutex")
public fun ReadWriteMutex(): ReadWriteMutex = ReadWriteMutexImpl()

/**
 * Executes the given [action] under a _reader_ lock of this mutex.
 *
 * @return the return value of the [action].
 */
@OptIn(ExperimentalContracts::class)
public suspend inline fun <T> ReadWriteMutex.read(action: () -> T): T {
    contract {
        callsInPlace(action, InvocationKind.EXACTLY_ONCE)
    }

    readLock()
    try {
       return action()
    } finally {
        readUnlock()
    }
}

/**
 * Executes the given [action] under the _writer_ lock of this mutex.
 *
 * @return the return value of the [action].
 */
@OptIn(ExperimentalContracts::class)
public suspend inline fun <T> ReadWriteMutex.write(action: () -> T): T {
    contract {
        callsInPlace(action, InvocationKind.EXACTLY_ONCE)
    }

    write.lock()
    try {
        return action()
    } finally {
        write.unlock()
    }
}

/**
 * This reader-writer mutex maintains the numbers of active and waiting readers,
 * a flag on whether the write lock is acquired, and the number of writers waiting
 * for the lock. This tuple represents the current state of this mutex and is split
 * into [waitingReaders] and [state] fields - it is impossible to store everything
 * in a single register since its maximal capacity is 64 bit, and this is not sufficient
 * for three counters and several flags. Additionally, separate [SegmentQueueSynchronizer]-s
 * are used for waiting readers and writers.
 *
 * In order to acquire a reader lock, the algorithm checks whether the writer lock is held,
 * increasing the number of _active_ readers and grabbing a read lock immediately if not and
 * increasing the number of _waiting_ readers and suspending otherwise. As for write lock
 * acquisition, the idea is the same -- it checks whether both reader and writer locks are
 * not acquired and takes the lock immediately in this case. Otherwise, if the writer should
 * wait for the lock, the algorithm increases the waiting writers counter and suspends.
 *
 * While releasing a reader lock, the algorithm decrements the number of active readers.
 * However, if the counter reaches zero, it checks whether a writer is waiting for the lock
 * and resumes the first waiter. The writer lock release resumes either the next waiting writer
 * decrementing the number of them or all waiting readers decrementing the corresponding counter.
 * When both readers and writers are waiting, a truly fair implementation should check whether
 * the first reader or the first writer came earlier and resume it. Simultaneously, it potentially
 * should not resume all waiting readers since it is possible for several readers to come following by
 * a writer and several readers again. In this case, only the first group of readers should be resumed
 * in a truly fair implementation. However, our implementation does not support this behavior and resumes
 * either the first writer or all the waiting readers. In some sense, it can prioritize readers or writers.
 * To make the algorithm practically fair, so that neither readers nor writers starve, we flip a coin
 * to choose whether writers or readers should be prioritized this time.
 *
 * As for cancellation, the main idea is to revert the state update. However, possible logical races
 * should be managed, which makes the revert part non-trivial. The details are discussed in the code
 * comments and appear almost everywhere.
 * */
internal class ReadWriteMutexImpl : ReadWriteMutex, Mutex {
    // The number of coroutines waiting for a reader lock in `sqsReaders`.
    private val waitingReaders = atomic(0)
    // This state field contains several counters
    // and is always updated atomically by `CAS`:
    // - `AR` (active readers) is a 30-bit counter which represents the number
    //                         of coroutines holding a read lock;
    // - `WLA` (write lock acquired) is a flag which is `true` when the write lock is acquired;
    // - `WW` (waiting writers) is a 30-bit counter which represents the number
    //                          of coroutines waiting for the write lock in `sqsWriters`;
    // - `RWR` (resuming waiting readers) is a flag which is `true` when waiting readers
    //                                    resumption is in progress.
    private val state = atomic(0L)

    private val sqsReaders = ReadersSQS() // The place where readers should suspend and be resumed.
    private val sqsWriters = WritersSQS() // The place where writers should suspend and be resumed.

    private var curUnlockPolicy = false // false -- prioritize readers
                                         // true -- prioritize writers

    @ExperimentalCoroutinesApi
    override val write: Mutex get() = this // we do not create an extra object this way.
    override val isLocked: Boolean get() = state.value.wla
    override fun tryLock(owner: Any?): Boolean = tryWriteLock()
    override suspend fun lock(owner: Any?) {
        if (owner != null) error("ReadWriteMutex.write does not support owners")
        writeLock()
    }
    override val onLock: SelectClause2<Any?, Mutex> get() = error("ReadWriteMutex.write does not support `onLock`")
    override fun holdsLock(owner: Any) = error("ReadWriteMutex.write does not support owners")
    override fun unlock(owner: Any?) {
        if (owner != null) error("ReadWriteMutex.write does not support owners")
        writeUnlock()
    }

    override suspend fun readLock() {
        // Try to acquire a reader lock without suspension at first.
        if (tryReadLock()) return
        // The attempt fails, invoke the slow-path. This slow-path
        // part is implemented in a separate function to guarantee
        // that the tail call optimization is applied here.
        readLockSlowPath()
    }

    override fun tryReadLock(): Boolean {
        while (true) {
            // Read the current state.
            val s = state.value
            // Is the write lock acquired or is there a waiting writer?
            if (s.wla || s.ww > 0) return false
            // A reader lock is available to acquire, try to do it!
            // Note that there can be a concurrent `writeUnlock` which is
            // resuming readers now, so that the `RWR` flag is set in this case.
            if (state.compareAndSet(s, state(s.ar + 1, false, 0, s.rwr)))
                return true
            // CAS failed => the state has changed.
            // Re-read it and try to acquire a reader lock again.
        }
    }

    private suspend fun readLockSlowPath() = suspendCancellableCoroutineReusable<Unit> sc@ { cont ->
        retry@while (true) {
            // Increment the number of waiting readers at first.
            // If the current invocation should not suspend,
            // the counter will be decremented back later.
            waitingReaders.incrementAndGet()
            // Check whether this operation should suspend. If not,
            // try to decrement the counter of waiting readers and re-start.
            while (true) {
                // Read the current state.
                val s = state.value
                // Is there a writer holding the lock or waiting for it?
                if (s.wla || s.ww > 0) {
                    // The number of waiting readers was incremented
                    // correctly, wait for a reader lock in `sqsReaders`.
                    if (sqsReaders.suspend(cont)) return@sc
                    else continue@retry
                } else {
                    // A race has detected! The number of waiting readers increment
                    // was wrong, try to decrement it back. However, it could
                    // already become zero due to a concurrent `writeUnlock`
                    // which gets the number of waiting readers, puts `0`
                    // there, and resumes all these readers. In this case,
                    // it is guaranteed that a reader lock will be provided
                    // via `sqsReaders`, so that we go wait for it.
                    while (true) {
                        // Read the current number of waiting readers.
                        val wr = waitingReaders.value
                        // Is our invocation already handled by a concurrent
                        // `writeUnlock` and a reader lock is going to be
                        // passed via `sqsReaders`? Suspend in this case --
                        // it is guaranteed that the lock will be provided
                        // when this concurrent `writeUnlock` completes.
                        if (wr == 0) {
                            if (sqsReaders.suspend(cont)) return@sc
                            else continue@retry
                        }
                        // Otherwise, try to decrement the number of waiting
                        // readers and re-try the operation from the beginning.
                        if (waitingReaders.compareAndSet(wr, wr - 1)) {
                            // Try again starting from the fast path
                            // since the state has changed.
                            if (tryReadLock()) {
                                cont.resume(Unit) { readUnlock() }
                                return@sc
                            }
                            continue@retry
                        }
                    }
                }
            }
        }
    }

    override fun readUnlock() {
        // When releasing a reader lock, the algorithm checks whether
        // this reader lock is the last acquired one and resumes
        // the first waiting writer (if applicable) in this case.
        while (true) {
            // Read the current state.
            val s = state.value
            check(!s.wla) { "Invalid `readUnlock` invocation: the writer lock is acquired. $INVALID_UNLOCK_INVOCATION_TIP" }
            check(s.ar > 0) { "Invalid `readUnlock` invocation: no reader lock is acquired. $INVALID_UNLOCK_INVOCATION_TIP" }
            // Is this reader the last one and the `RWR` flag unset (=> it is valid to resume the next writer)?
            if (s.ar == 1 && !s.rwr) {
                // The algorithm checks whether there is a waiting writer and resumes it.
                // Otherwise, it simply changes the state and finishes.
                if (s.ww > 0) {
                    // Try to decrement the number of waiting writer and set the `WLA` flag.
                    // Resume the first waiting writer on success.
                    if (state.compareAndSet(s, state(0, true, s.ww - 1, false))) {
                        if (!sqsWriters.resume(Unit)) writeUnlock(PRIORITIZE_WRITERS)
                        return
                    }
                } else {
                    // There is no waiting writer according to the state,
                    // try to clear the number of active readers and finish.
                    if (state.compareAndSet(s, state(0, false, 0, false)))
                        return
                }
            } else {
                // Try to decrement the number of active readers and finish.
                // Please note that the `RWR` flag can be set here if there is
                // a concurrent unfinished `writeUnlock` operation which
                // has resumed the current reader but the corresponding
                // `readUnlock` happened before this `writeUnlock` finished.
                if (state.compareAndSet(s, state(s.ar - 1, false, s.ww, s.rwr)))
                    return
            }
        }
    }

    /**
     * This customization of [SegmentQueueSynchronizer] for waiting readers
     * use the asynchronous resumption mode and smart cancellation mode,
     * so that neither [suspend] nor [resume] fail. However, in order to
     * support `tryReadLock()` the synchronous resumption mode should be used.
     */
    private inner class ReadersSQS : SegmentQueueSynchronizer<Unit>() {
        override val resumeMode get() = ResumeMode.SYNC
        override val cancellationMode get() = CancellationMode.SMART_SYNC

        override fun onCancellation(): Boolean {
            // The cancellation logic here is pretty similar to
            // the one in `readLock` where the number of waiting
            // readers is incremented incorrectly.
            while (true) {
                // First, read the current number of waiting readers.
                val wr = waitingReaders.value
                // Check whether it has already reached zero -- in this
                // case a concurrent `writeUnlock` already invoked `resume`
                // for us, and `onCancellation` should return `false` to
                // refuse the granted lock.
                if (wr == 0) return false
                // Otherwise, try to decrement the number of waiting readers
                // and successfully finish the cancellation.
                if (waitingReaders.compareAndSet(wr, wr - 1)) return true
            }
        }

        // Always fails since we have not changed the state in `onCancellation()`,
        // the value should be returned by `returnValue()` instead.
        override fun tryReturnRefusedValue(value: Unit) = false

        // This is also used for prompt cancellation.
        override fun returnValue(value: Unit) = readUnlock()
    }

    internal suspend fun writeLock() {
        if (tryWriteLock()) return
        writeLockSlowPath()
    }

    internal fun tryWriteLock(): Boolean {
        // The algorithm is straightforward -- it reads the state,
        // checks that there is no reader or writer lock acquired,
        // and tries to change the state by setting the `WLA` flag.
        while (true) {
            // Read the current state.
            val s = state.value
            // Is there an active writer, a concurrent `writeUnlock` operation
            // (thus, an uncompleted writer), or an active reader? Fail in this case.
            if (s.wla || s.rwr || s.ar != 0) return false
            assert { s.ww == 0 }
            // Try to acquire the writer lock, re-try the operation if this CAS fails.
            if (state.compareAndSet(s, state(0, true, 0, false)))
                return true
        }
    }

    private suspend fun writeLockSlowPath() = suspendCancellableCoroutineReusable<Unit> sc@ { cont ->
        retry@while (true) {
            // The algorithm is straightforward -- it reads the state,
            // checks that there is no reader or writer lock acquired,
            // and tries to change the state by setting the `WLA` flag.
            // Otherwise, it increments the number of waiting writers
            // and suspends in `sqsWriters` waiting for the lock.
            while (true) {
                // Read the current state.
                val s = state.value
                // Is there an active writer, a concurrent `writeUnlock` operation
                // which is releasing readers (thus, an uncompleted writer), or an active reader?
                if (!s.wla && !s.rwr && s.ar == 0) {
                    // Try to acquire the writer lock, re-try the operation if this CAS fails.
                    assert { s.ww == 0 }
                    if (state.compareAndSet(s, state(0, true, 0, false))) {
                        cont.resume(Unit) { writeUnlock() }
                        return@sc
                    }
                } else {
                    // The operation has to suspend. Try to increment the number of waiting
                    // writers waiters and suspend in `sqsWriters`.
                    if (state.compareAndSet(s, state(s.ar, s.wla, s.ww + 1, s.rwr))) {
                        if (sqsWriters.suspend(cont)) return@sc
                        else continue@retry
                    }
                }
            }
        }
    }

    internal fun writeUnlock() {
        // Since we store waiting readers and writers separately, it is not easy
        // to determine whether the next readers or the next writer should be resumed.
        // However, it is natural to have the following policies:
        // - PRIORITIZE_READERS -- always resume all waiting readers at first;
        //   the next waiting writer is resumed only if no reader is waiting for a lock.
        // - PRIORITIZE_WRITERS -- always resumed the next writer first;
        // - ROUND_ROBIN -- switches between the policies above on every invocation.
        //   We find the round-robin strategy fair enough in practice, but the others are used
        //   in Lincheck tests. However, it could be useful to have `PRIORITIZE_WRITERS`
        //   when the writer lock is used for UI updates or similar.
        writeUnlock(ROUND_ROBIN)
    }

    internal fun writeUnlock(policy: WriteUnlockPolicy) {
        // The algorithm for releasing the writer lock is straightforward by design.
        // If the next writer should be resumed (see `PRIORITIZE_WRITERS` policy),
        // it tries to decrement the number of waiting writers and keep the `WLA` flag
        // atomically, resuming the first writer in `sqsWriters` after that. Besides,
        // suppose there is no waiting writer or readers should be resumed at first.
        // In that case, it re-sets the `WLA` flag and sets the `RWR` (resuming waiting readers)
        // flag atomically, invoking `completeWaitingReadersResumption()` after that.
        // This function performs the rest and checks whether the next waiting writer
        // should be resumed on completion -- it is necessary since there could be no
        // waiting readers or a writer came in the meanwhile but was unable to acquire
        // the lock because of the `RWR` flag.
        while (true) {
            // Read the current state at first.
            val s = state.value
            check(s.wla) { "Invalid `writeUnlock` invocation: the writer lock is not acquired. $INVALID_UNLOCK_INVOCATION_TIP" }
            assert { !s.rwr }
            assert { s.ar == 0 }
            // Should we resume the next writer?
            curUnlockPolicy = !curUnlockPolicy // change the unlock policy for `ROUND_ROBIN`
            val resumeWriter = (s.ww > 0) && (policy == PRIORITIZE_WRITERS || policy == ROUND_ROBIN && curUnlockPolicy)
            if (resumeWriter) {
                // Resume the next writer -- try to decrement the number of waiting
                // writers and resume the first one in `sqsWriters` on success.
                if (state.compareAndSet(s, state(0, true, s.ww - 1, false))) {
                    if (!sqsWriters.resume(Unit)) writeUnlock(PRIORITIZE_WRITERS)
                    return
                }
            } else {
                // Resume waiting readers. Re-set the `WLA` flag and set the `RWR` flag atomically,
                // complete the resumption via `completeWaitingReadersResumption()` after that.
                // Note that this function also checks whether the next waiting writer should be resumed
                // on completion and performs it if required. It also re-sets the `RWR` flag at the end.
                //
                // While it is possible that no reader is waiting for a lock, so that this CAS can be omitted,
                // we do not add the corresponding code for simplicity since it does not improve the performance
                // significantly but degrades code readability.
                if (state.compareAndSet(s, state(0, false, s.ww, true))) {
                    completeWaitingReadersResumption(false)
                    return
                }
            }
        }
    }

    internal fun completeWaitingReadersResumption(xxx: Boolean): Boolean {
        // This function is called after the `RWR` flag is set
        // and completes the resumption process. Note that
        // it also checks whether the next waiting writer should be
        // resumed on completion and performs this resumption if required.
        assert { state.value.rwr }
        // At first, it atomically replaces the number of waiting
        // readers (to be resumed) with 0, taking the old value.
        val wr = waitingReaders.getAndSet(0)
        // After that, these waiting readers should be logically resumed
        // by incrementing the corresponding counter in the `state` field.
        // We also skip this step if the obtained number of waiting readers is zero.
        if (wr > 0) { // should we update the state?
            state.update { s ->
                assert { !s.wla } // the writer lock cannot be acquired at this point.
                assert { s.rwr } // the `RWR` flag should still be set.
                state(s.ar + wr, false, s.ww, true)
            }
        }
        // After the readers are resumed logically, they should be resumed physically in `sqsReaders`.
        var failedResumptions = 0
        repeat(wr) {
            if (!sqsReaders.resume(Unit)) failedResumptions++
        }
        if (failedResumptions != 0) {
            state.update { s ->
                assert { !s.wla } // the writer lock cannot be acquired at this point.
                assert { s.rwr } // the `RWR` flag should still be set.
                if (s.ar < failedResumptions) {
                    error("What should we do then??!")
                } else {
                    state(s.ar - failedResumptions, false, s.ww, true)
                }
            }
        }
        // Once all the waiting readers are resumed, the `RWR` flag should be re-set.
        // It is possible that all the resumed readers have already completed their
        // work and successfully invoked `readUnlock()` at this point, but since
        // the `RWR` flag was set, they were unable to resume the next waiting writer.
        // Thus, we check whether the number of active readers is 0 and resume
        // the next waiting writer in this case, if there exists one.
        var resumeWriter = false
        state.getAndUpdate { s ->
            resumeWriter = s.ar == 0 && s.ww > 0
            val newWW =  if (resumeWriter && !xxx) s.ww - 1 else s.ww
            state(s.ar, resumeWriter, newWW, false)
        }
        if (resumeWriter) {
            if (xxx) return false
            if (!sqsWriters.resume(Unit)) writeUnlock(PRIORITIZE_WRITERS)
            // Complete the procedure if the next writer is resumed.
            return true
        }
        // Meanwhile, it could be possible for a writer to come and suspend due to the `RWR` flag.
        // After that, all the following readers suspend since a writer is waiting for the lock.
        // However, if the writer becomes canceled, it cannot resume these waiting readers if the `RWR` flag
        // is still set, so that we have to help him. To detect such a situation, we re-read the number of
        // waiting readers and try to start the resumption process again if the writer lock is not acquired.
        if (waitingReaders.value > 0) { // Is there a waiting reader?
            while (true) {
                val s = state.value // Read the current state.
                if (s.wla || s.ww > 0 || s.rwr) return true // Check whether the readers resumption is valid.
                // Try to set the `RWR` flag and resume the waiting readers.
                if (state.compareAndSet(s, state(s.ar, false, 0, true))) {
                    return completeWaitingReadersResumption(xxx)
                }
            }
        }
        return true
    }

    /**
     * This customization of [SegmentQueueSynchronizer] for waiting writers
     * use the asynchronous resumption mode and smart cancellation mode,
     * so that neither [suspend] nor [resume] fail. However, in order to
     * support `tryWriteLock()` the synchronous resumption mode should be used.
     */
    private inner class WritersSQS : SegmentQueueSynchronizer<Unit>() {
        override val resumeMode get() = ResumeMode.SYNC
        override val cancellationMode get() = CancellationMode.SMART_SYNC

        override fun onCancellation(): Boolean {
            // In general, on cancellation, the algorithm tries to decrement the number of waiting writers.
            // Similarly to the cancellation logic for readers, if the number of waiting writers is already 0,
            // the current canceling writer will be resumed in `sqsWriters`. In this case, the function returns
            // `false`, and the permit will be returned with `returnValue()`. Otherwise, if the number of waiting
            // writers is greater than one, the decrement is sufficient. However, if this canceling writer is
            // the last waiting one, the algorithm sets the `RWR` flag and resumes waiting readers. This logic
            // is pretty similar to the one in `writeUnlock`.
            while (true) {
                val s = state.value // Read the current state.
                if (s.ww == 0) return false // Is this writer going to be resumed in `sqsWriters`?
                // Is this writer the last one and readers resumption is valid?
                if (s.ww == 1 && !s.wla && !s.rwr) {
                    // Set the `RWR` flag and resume the waiting readers.
                    // While it is possible that no reader is waiting for a lock, so that this CAS can be omitted,
                    // we do not add the corresponding code for simplicity since it does not improve the performance
                    // significantly but degrades code readability. Note that the same logic appears in `writeUnlock`,
                    // and the cancellation performance is less critical since cancellation does not come for free.
                    if (state.compareAndSet(s, state(s.ar, false, 0, true))) {
                        return completeWaitingReadersResumption(true)
                    }
                } else {
                    if (state.compareAndSet(s, state(s.ar, s.wla, s.ww - 1, s.rwr)))
                        return true
                }
            }
        }

        // Always fails since we have not changed the state in `onCancellation()`,
        // the value should be returned by `returnValue()` instead.
        override fun tryReturnRefusedValue(value: Unit): Boolean {
            writeUnlock(PRIORITIZE_WRITERS)
            return true
        }

        // This is also used for prompt cancellation.
        override fun returnValue(value: Unit) = writeUnlock()
    }

    // We use this state representation in Lincheck tests.
    internal val stateRepresentation: String get() =
        "<wr=${waitingReaders.value},ar=${state.value.ar}" +
        ",wla=${state.value.wla},ww=${state.value.ww}" +
        ",rwr=${state.value.rwr}" +
        ",sqs_r={$sqsReaders},sqs_w={$sqsWriters}>"

    internal enum class WriteUnlockPolicy { PRIORITIZE_READERS, PRIORITIZE_WRITERS, ROUND_ROBIN }
}

// Construct the value for [ReadWriteMutexImpl.state] field,
// it then can be parsed by the extension functions below.
private fun state(activeReaders: Int, writeLockAcquired: Boolean, waitingWriters: Int, resumingWaitingReaders: Boolean): Long =
    (if (writeLockAcquired) WLA_BIT else 0) +
    (if (resumingWaitingReaders) RWR_BIT else 0) +
    activeReaders * AR_MULTIPLIER +
    waitingWriters * WW_MULTIPLIER

// Returns `true` if the `WLA` flag is set in this state.
private val Long.wla: Boolean get() = this or WLA_BIT == this
// Returns `true` if the `RWR` flag is set in this state.
private val Long.rwr: Boolean get() = this or RWR_BIT == this
// Retrieves the number of waiting writers from this state.
private val Long.ww: Int get() = ((this % AR_MULTIPLIER) / WW_MULTIPLIER).toInt()
// Retrieves the number of active readers from this state.
private val Long.ar: Int get() = (this / AR_MULTIPLIER).toInt()

private const val WLA_BIT = 1L
private const val RWR_BIT = 1L shl 1
private const val WW_MULTIPLIER = 1L shl 2
private const val AR_MULTIPLIER = 1L shl 33

private const val INVALID_UNLOCK_INVOCATION_TIP = "This can be caused by releasing the lock without acquiring it at first, " +
    "or incorrectly putting the acquisition inside the \"try\" block of the \"try-finally\" section that safely releases " +
    "the lock in the \"finally\" block - the acquisition should be performed right before this \"try\" block."