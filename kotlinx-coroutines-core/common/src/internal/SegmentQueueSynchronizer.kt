/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.SegmentQueueSynchronizer.*
import kotlinx.coroutines.internal.SegmentQueueSynchronizer.CancellationMode.*
import kotlinx.coroutines.internal.SegmentQueueSynchronizer.ResumeMode.*
import kotlinx.coroutines.sync.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.native.concurrent.*

/**
 * [SegmentQueueSynchronizer] is an abstraction for implementing _fair_ synchronization and communication primitives.
 * It maintains a FIFO queue of waiting requests and provides two main functions:
 *   - [suspend] stores the specified waiter in the queue, and
 *   - [resume] tries to retrieve and resume the first waiter, passing the specified value to it.
 *
 * One may consider this abstraction an infinite array with two counters that reference the next cells
 * for enqueueing a continuation in [suspend] and for retrieving it in [resume]. In short, when [suspend] is invoked,
 * it increments the corresponding counter via fast `Fetch-And-Add` and stores the continuation in the cell.
 * Likewise, [resume] increments its counter, comes to the corresponding cell, and resumes the stored continuation
 * with the specified value. A typical implementation via [SegmentQueueSynchronizer] performs some synchronization
 * at first (e.g., [Semaphore] modifies the number of available permits in the beginning of [Semaphore.acquire])
 * and invokes [suspend] or [resume] after that. Note that in this case, it is possible in a concurrent environment
 * that [resume] comes to the cell before [suspend] and finds the cell in the empty state. This race is similar to
 * the one between concurrent `park` and `unpark` invocations for threads. We support two elimination [strategies][ResumeMode]
 * to solve the race, [asynchronous][ASYNC] and [synchronous][SYNC]:
 *   - In the [asynchronous][ASYNC] mode, [resume] performs an elimination by putting the value into the cell
 *     if it is still empty and finishes immediately; this way, the corresponding [suspend] comes to this cell
 *     and grabs the element without suspension.
 *   - In the [synchronous][SYNC] mode, [resume] waits in a bounded spin-loop cycle until the put element is taken
 *     by a concurrent [suspend], marking the cell as broken if the element is not taken during the spin-loop.
 *     In this case, both [resume] and [suspend] that come to this broken cell fail, and the outer operations
 *     on the data structure typically restart from the very beginning.
 *
 * Since [suspend] can store [CancellableContinuation]-s, it is possible for [resume] to fail if the continuation
 * is already cancelled. In this case, most of the algorithms retry the whole operation. In [Semaphore], for example,
 * when `release` fails on the next waiter resumption, it logically provides the permit to the waiter but takes it back
 * after if the waiter is canceled. In the latter case, the permit should be released again so that the operation restarts.
 *
 * However, the cancellation logic above requires to process all canceled cells. Thus, the operation that resumes waiters
 * (e.g., `release` in `Semaphore`) works in linear time in the number of consecutive canceled waiters. This way,
 * if N coroutines come to a semaphore, suspend, and cancel, the following `release` works in O(N) since it should
 * process all these N cells (and increment the counter of available permits in our implementation). While the complexity
 * is amortized by cancellations, it would be better to make such operations as `release` in `Semaphore` work predictably
 * fast, independently on the number of cancelled requests.
 *
 * The main idea to improve cancellation is skipping these `CANCELLED` cells so that [resume] always succeeds
 * if no elimination happens (remember that [resume] may fail in the [synchronous][SYNC] resumption mode if
 * it finds the cell empty). Additionally, a cancellation handler that modifies the data structure consequently
 * should be specified (in [Semaphore] it should increment the available permits counter back). Thus, we support
 * several cancellation policies in [SegmentQueueSynchronizer]. The [SIMPLE] cancellation mode is already described above
 * and used by default. In this mode, [resume] fails if it finds the cell in the `CANCELLED` state or if the waiter resumption
 * (see [CancellableContinuation.tryResume]) does not succeed. As we discussed, these failures are typically handled
 * by re-starting the whole operation from the beginning. With the other two smart cancellation modes,
 * [SMART_SYNC] and [SMART_ASYNC], [resume] skips `CANCELLED` cells (the cells where waiter resumption failed are also
 * considered as `CANCELLED`). This way, even if a million of canceled continuations are stored in [SegmentQueueSynchronizer],
 * one [resume] invocation is sufficient to pass the value to a waiter since it skips all these canceled waiters.
 * However, these modes provide less intuitive contracts and require users to write more complicated code;
 * the details are described further.
 *
 * The main issue with skipping `CANCELLED` cells in [resume] is that it can become illegal to put the value into
 * the next cell. Consider the following execution: [suspend] is called, then [resume] starts, but the suspended
 * waiter becomes canceled. This way, no waiter is waiting in [SegmentQueueSynchronizer] anymore. Thus, if [resume]
 * skips this canceled cell, puts the value into the next empty cell, and completes, the data structure's state becomes
 * incorrect. Instead, the value provided by this [resume] should be refused and returned to the outer data structure.
 * There is no way for [SegmentQueueSynchronizer] to decide whether the value should be refused automatically.
 * Instead, users should implement a cancellation handler by overriding the [onCancellation] function, which returns
 * `true` if the cancellation completes successfully and `false` if the [resume] that will come to this cell
 * should be refused. In the latter case, the [resume] that comes to this cell refuses its value by invoking
 * [tryReturnRefusedValue] to return it back to the outer data structure. However, it is possible for
 * [tryReturnRefusedValue] to fail, and [returnValue] is called in this case. Typically, this [returnValue] function
 * coincides with the one that resumes waiters (e.g., with [release][Semaphore.release] in [Semaphore]).
 * The difference between [SMART_SYNC] and [SMART_ASYNC] modes is that in the [SMART_SYNC] mode, a [resume] that comes
 * to a cell with a canceled waiter waits in a spin-loop until the cancellation handler is invoked and the cell
 * is moved to either `CANCELLED` or `REFUSE` state. In contrast, in the [SMART_ASYNC] mode, [resume] replaces
 * the canceled waiter with the value of this resumption and finishes immediately -- the cancellation handler
 * completes this [resume] eventually. This way, in [SMART_ASYNC] mode, the value passed to [resume] can be out
 * of the data structure for a while but is guaranteed to be processed eventually.
 *
 * It is worth noting that [SegmentQueueSynchronizer] supports prompt cancellation -- [returnValue] is called
 * when continuation is cancelled while dispatching.
 *
 * Here is a state machine for cells. Note that only one [suspend]
 * and at most one [resume] operation can deal with each cell.
 *
 * todo CANCELLING state
 *
 *  +-------+   `suspend` succeeds.   +--------------+  `resume` is   +---------------+  store `RESUMED` to   +---------+  ( `cont` HAS BEEN   )
 *  |  NULL | ----------------------> | cont: active | -------------> | cont: resumed | --------------------> | RESUMED |  ( RESUMED AND THIS  )
 *  +-------+                         +--------------+  successful.   +---------------+  avoid memory leaks.  +---------+  ( `resume` SUCCEEDS )
 *     |                                     |
 *     |                                     | The continuation
 *     | `resume` comes to                   | is cancelled.
 *     | the cell before                     |
 *     | `suspend` and puts                  V                                                                          ( THE CORRESPONDING `resume` SHOULD BE    )
 *     | the element into               +-----------------+    The concurrent `resume` should be refused,    +--------+ ( REFUSED AND `tryReturnRefusedValue`, OR )
 *     | the cell, waiting for          | cont: cancelled | -----------------------------------------------> | REFUSE | ( `returnValue` IF IT FAILS, IS USED TO   )
 *     | `suspend` if the resume        +-----------------+        `onCancellation` returned `false`.        +--------+ ( RETURN THE VALUE BACK TO THE OUTER      )
 *     | mode is `SYNC`.                     |         \                                                     ^          ( SYNCHRONIZATION PRIMITIVE               )
 *     |                                     |          \                                                    |
 *     |        Mark the cell as `CANCELLED` |           \                                                   |
 *     |         if the cancellation mode is |            \  `resume` delegates its completion to            | `onCancellation` returned `false,
 *     |        `SIMPLE` or `onCancellation` |             \   the concurrent cancellation handler if        | mark the state accordingly and
 *     |                    returned `true`. |              \   `SMART_ASYNC` cancellation mode is used.     | complete the hung `resume`.
 *     |                                     |               +------------------------------------------+    |
 *     |                                     |                                                           \   |
 *     |    ( THE CONTINUATION IS    )       V                                                            V  |
 *     |    ( CANCELLED AND `resume` ) +-----------+                                                     +-------+
 *     |    ( FAILS IN THE SIMPLE    ) | CANCELLED | <-------------------------------------------------- | value |
 *     |    ( CANCELLATION MODE OR   ) +-----------+   Mark the cell as `CANCELLED` if `onCancellation`  +-------+
 *     |    ( SKIPS THIS CELL IN THE )              returned true, complete the hung `resume` accordingly.
 *     |    ( SMART MODE             )
 *     |
 *     |
 *     |            `suspend` gets   +-------+  ( RENDEZVOUS HAPPENED, )
 *     |         +-----------------> | TAKEN |  (  BOTH `resume` AND   )
 *     V         |   the element.    +-------+  (  `suspend` SUCCEED   )
 *  +-------+    |
 *  | value | --<
 *  +-------+   |
 *              | `tryResume` has waited a bounded time,  +--------+
 *              +---------------------------------------> | BROKEN | (BOTH `suspend` AND `resume` FAIL)
 *                     but `suspend` has not come.        +--------+
 *
 * The infinite array implementation is organized as a linked list of [segments][SQSSegment];
 * each segment contains a fixed number of cells. In order to determine the working cell
 * for each [suspend] and [resume]  invocation, the algorithm reads the current [tail] or [head],
 * increments [enqIdx] or [deqIdx], and finds the required segment starting from the initially read one.
 * It is also essential that this infinite array implementation does not store segments full of cells
 * in the CANCELLED state; thus, avoiding memory leaks on cancellation and guaranteeing constant complexity
 * for `resume`.
 */
internal abstract class SegmentQueueSynchronizer<T : Any> {
    private val head: AtomicRef<SQSSegment>
    private val deqIdx = atomic(0L)
    private val tail: AtomicRef<SQSSegment>
    private val enqIdx = atomic(0L)

    init {
        val s = SQSSegment(0, null, 2)
        head = atomic(s)
        tail = atomic(s)
    }

    protected open val useBackoff: Boolean = false
    private var backoffSize = MAX_BACKOFF / 2

    /**
     * Specifies whether [resume] should work in
     * [synchronous][SYNC] or [asynchronous][ASYNC] mode.
     */
    protected open val resumeMode: ResumeMode get() = SYNC

    /**
     * Specifies whether [resume] should fail on cancelled waiters ([SIMPLE]),
     * or skip them in either [synchronous][SMART_SYNC] or [asynchronous][SMART_ASYNC]
     * way. In the [asynchronous][SMART_ASYNC] mode, [resume] may pass the element to the
     * cancellation handler in order not to wait, so that the element can be "hung"
     * for a while, but it is guaranteed that the element will be processed eventually.
     */
    protected open val cancellationMode: CancellationMode get() = SIMPLE

    /**
     * This function is invoked when waiter is cancelled and smart
     * cancellation mode is used (so that cancelled cells are skipped by
     * [resume]). Typically, this function performs the logical cancellation.
     * It returns `true` if the cancellation succeeds and the cell can be
     * marked as [CANCELLED]. This way, [resume] skips this cell and passes
     * the value to another waiter in the waiting queue. However, if the [resume]
     * that comes to this cell should be refused, [onCancellation] should return false.
     * In this case, [tryReturnRefusedValue] is invoked with the value of this [resume],
     * following by [returnValue] if [tryReturnRefusedValue] fails.
     */
    protected open fun onCancellation() : Boolean = false

    /**
     * This function specifies how the value refused
     * by this [SegmentQueueSynchronizer] should
     * be returned back to the data structure. It
     * returns `true` on success or `false` if the
     * attempt fails, so that [returnValue] should
     * be used to complete the returning.
     */
    protected open fun tryReturnRefusedValue(value: T): Boolean = true

    /**
     * This function specifies how the value from
     * a failed [resume] should be returned back to
     * the data structure. It is typically the function
     * that invokes [resume] (e.g., [release][Semaphore.release]
     * in [Semaphore]).
     *
     * This function is invoked when [onCancellation] returns `false`
     * and the following [tryReturnRefusedValue] fails, or when prompt
     * cancellation occurs.
     */
    protected open fun returnValue(value: T) {}

    /**
     * This is a shortcut for [tryReturnRefusedValue] and
     * the following [returnValue] invocation on failure.
     */
    private fun returnRefusedValue(value: T) {
        if (tryReturnRefusedValue(value)) return
        returnValue(value)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun suspend(waiter: Any): Boolean {
        // Increment `enqIdx` and find the segment
        // with the corresponding id. It is guaranteed
        // that this segment is not removed since at
        // least the cell for this [suspend] invocation
        // is not in the `CANCELLED` state.
        val curTail = this.tail.value
        val enqIdx = enqIdx.getAndIncrement()
        val segment = this.tail.findSegmentAndMoveForward(id = enqIdx / SEGMENT_SIZE, startFrom = curTail,
            createNewSegment = ::createSegment).segment
        assert { segment.id == enqIdx / SEGMENT_SIZE }
        // Try to install the continuation in the cell,
        // this is the regular path.
        val i = (enqIdx % SEGMENT_SIZE).toInt()
        if (segment.cas(i, null, waiter)) {
            if (useBackoff) {
                repeat(backoffSize) {
                    if (it % 32 == 0 && segment.get(i) !== waiter) {
                        backoffSize = (backoffSize * 2).coerceAtMost(MAX_BACKOFF)
                        return true
                    }
                }
                backoffSize = (backoffSize + 1) * 7 / 8
            }
            // The continuation is successfully installed, and
            // `resume` cannot break the cell now, so that this
            // suspension is successful.
            // Add a cancellation handler if required and finish.
            if (waiter is CancellableContinuation<*>) {
                waiter.invokeOnCancellation(SQSCancellationHandler(segment, i).asHandler)
            } else if (waiter !is Continuation<*>) {
                do {
                    suspendWaiter(waiter)
                } while (segment.get(i) === waiter)
            }
            return true
        }
        // The continuation installation has failed. This happens
        // if a concurrent `resume` came earlier to this cell and put
        // its value into it. Note that in `SYNC` resumption mode
        // this concurrent `resume` can mark the cell as broken.
        //
        // Try to grab the value if the cell is not in the `BROKEN` state.
        val value = segment.get(i)
        if (value !== BROKEN && segment.cas(i, value, TAKEN)) {
            // The elimination is successfully performed,
            // resume the continuation with the value and complete.
            value as T
            when (waiter) {
                is CancellableContinuation<*> -> {
                    waiter as CancellableContinuation<T>
                    waiter.resume(value, { returnValue(value) }) // TODO do we really need this?
                }
                is Continuation<*> -> {
                    waiter as Continuation<T>
                    waiter.resume(value)
                }
            }
            return true
        }
        // The cell is broken, this can happen only in `SYNC` resumption mode.
        assert { resumeMode == SYNC && segment.get(i) === BROKEN }
        return false
    }

    /**
     * Puts the specified continuation into the waiting queue, and returns `true` on success.
     * Since [suspend] and [resume] can be invoked concurrently (similarly to `park` and `unpark`
     * for threads), it is possible that [resume] comes earlier. In this case, [resume] puts the
     * value into the cell, and [suspend] is supposed to come and grab the value after that.
     * However, if the [synchronous][SYNC] resumption mode is used, the concurrent [resume]
     * can mark the cell as [broken][BROKEN]. Thus, the [suspend] invocation that comes to
     * this broken cell fails and also returns `false`. The typical patter is retrying
     * both operations, the one that failed on [suspend] and the one that failed on [resume],
     * from the beginning.
     */
    fun suspend(cont: Continuation<T>): Boolean = suspend(waiter = cont)

    /**
     * Tries to resume the next waiter and returns `true` if
     * the resumption succeeds. However, it can fail due to
     * several reasons. First, if the [resumption mode][resumeMode]
     * is [synchronous][SYNC], this [resume] invocation may come
     * before [suspend] and mark the cell as [broken][BROKEN];
     * `false` is returned in this case. Another reason for [resume]
     * to fail is waiter cancellation if the [simple cancellation mode][SIMPLE]
     * is used.
     *
     * Note that when smart cancellation ([SMART_SYNC] or [SMART_ASYNC])
     * is used, [resume] skips cancelled waiters and can fail only in
     * case of unsuccessful elimination due to [synchronous][SYNC]
     * resumption mode.
     */
    fun resume(value: T): Boolean {
        // Should we skip cancelled cells?
        val skipCancelled = cancellationMode != SIMPLE
        while (true) {
            // Try to resume the next waiter, adjust [deqIdx] if
            // cancelled cells should be skipped anyway.
            when (tryResumeImpl(value, adjustDeqIdx = skipCancelled)) {
                TRY_RESUME_SUCCESS -> return true
                TRY_RESUME_FAIL_CANCELLED -> if (!skipCancelled) return false
                TRY_RESUME_FAIL_BROKEN -> return false
            }
        }
    }

    /**
     * Tries to resume the next waiter, and returns [TRY_RESUME_SUCCESS] on
     * success, [TRY_RESUME_FAIL_CANCELLED] if the next waiter is cancelled,
     * or [TRY_RESUME_FAIL_BROKEN] if the next cell is marked as broken by
     * this [tryResumeImpl] invocation due to the [SYNC] resumption mode.
     *
     * In the smart cancellation modes ([SMART_SYNC] and [SMART_ASYNC]) the
     * cells marked as [cancelled][CANCELLED] should be skipped, so that
     * there is no need to increment [deqIdx] one-by-one if there is a
     * removed segment (logically full of [cancelled][CANCELLED] cells);
     * it is faster to point [deqIdx] to the first possibly non-cancelled
     * cell instead, i.e. to the first segment id multiplied by the
     * [segment size][SEGMENT_SIZE].
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryResumeImpl(value: T, adjustDeqIdx: Boolean): Int {
        // Check that `adjustDeqIdx` is `false`
        // in the simple cancellation mode.
        assert { !(cancellationMode == SIMPLE && adjustDeqIdx) }
        // Increment `deqIdx` and find the first segment with
        // the corresponding or higher (if the required segment
        // is physically removed) id.
        val curHead = this.head.value
        val deqIdx = deqIdx.getAndIncrement()
        val id = deqIdx / SEGMENT_SIZE
        val segment = this.head.findSegmentAndMoveForward(id, startFrom = curHead,
            createNewSegment = ::createSegment).segment
        // The previous segments can be safely collected
        // by GC, clean the pointer to them.
        segment.cleanPrev()
        // Is the required segment physically removed?
        if (segment.id > id) {
            // Adjust `deqIdx` to the first
            // non-removed segment if needed.
            if (adjustDeqIdx) adjustDeqIdx(segment.id * SEGMENT_SIZE)
            // The cell #deqIdx is in the `CANCELLED` state,
            // return the corresponding failure.
            return TRY_RESUME_FAIL_CANCELLED
        }
        // Modify the cell according to the state machine,
        // all the transitions are performed atomically.
        val i = (deqIdx % SEGMENT_SIZE).toInt()
        modify_cell@while (true) {
            val cellState = segment.get(i)
            when {
                // Is the cell empty?
                cellState === null -> {
                    // Try to perform an elimination by putting the
                    // value to the empty cell and wait until it is
                    // taken by a concurrent `suspend` in case of
                    // using the synchronous resumption mode.
                    if (!segment.cas(i, null, value)) continue@modify_cell
                    // Finish immediately in the async mode.
                    if (resumeMode == ASYNC) return TRY_RESUME_SUCCESS
                    // Wait for a concurrent `suspend`, which should mark
                    // the cell as taken, for a bounded time in a spin-loop.
                    repeat(MAX_SPIN_CYCLES) {
                        if (segment.get(i) === TAKEN) return TRY_RESUME_SUCCESS
                    }
                    // The value is still not taken, try to
                    // atomically mark the cell as broken.
                    // A failure indicates that the value is taken.
                    return if (segment.cas(i, value, BROKEN)) TRY_RESUME_FAIL_BROKEN else TRY_RESUME_SUCCESS
                }
                // Is the waiter cancelled?
                cellState === CANCELLED -> {
                    // Return the corresponding failure.
                    return TRY_RESUME_FAIL_CANCELLED
                }
                // Should the current `resume` be refused
                // by this `SegmentQueueSynchronizer`?
                cellState === REFUSE -> {
                    // This state should not occur
                    // in the simple cancellation mode.
                    assert { cancellationMode != SIMPLE }
                    // Return the refused value back to
                    // the data structure and succeed.
                    returnRefusedValue(value)
                    return TRY_RESUME_SUCCESS
                }
                // Does the cell store a cancellable continuation?
                cellState is CancellableContinuation<*> -> {
                    // Change the cell state to `RESUMED`, so that
                    // the cancellation handler cannot be invoked.
                    if (!segment.cas(i, cellState, RESUMED)) continue@modify_cell
                    // Try to resume the continuation.
                    val token = (cellState as CancellableContinuation<T>).tryResume(value, null, { returnValue(value) })
                    if (token != null) {
                        // `tryResume` has succeeded.
                        cellState.completeResume(token)
                    } else {
                        // `tryResume` has failed.
                        // Fail the current `resume` in the simple cancellation mode.
                        if (cancellationMode === SIMPLE)
                            return TRY_RESUME_FAIL_CANCELLED
                        // In smart cancellation mode, the cancellation
                        // handler should be invoked.
                        val cancelled = onCancellation()
                        if (cancelled) {
                            // Try to resume the next waiter. However,
                            // it can fail dur to synchronous mode.
                            // Return the value to the data structure
                            // in this case.
                            if (!resume(value)) returnValue(value)
                        } else {
                            // The value is refused, return
                            // it to the data structure.
                            returnRefusedValue(value)
                        }
                    }
                    // Once the state is changed to `RESUMED`, this
                    // `resume` is considered as successful. Note that
                    // possible cancellation is properly handled above,
                    // so that it does not break this `resume`.
                    return TRY_RESUME_SUCCESS
                }
                cellState === CANCELLING -> {
                    // The continuation is cancelled but the
                    // cancellation handler is not completed yet.
                    when (cancellationMode) {
                        // Fail in the simple mode.
                        SIMPLE -> return TRY_RESUME_FAIL_CANCELLED
                        // In the smart cancellation mode this cell
                        // can be either skipped (if it is going to
                        // be marked as cancelled) or this `resume`
                        // should be refused. The `SMART_SYNC` mode
                        // waits in a an infinite spin-loop until
                        // the state of this cell is changed to
                        // either `CANCELLED` or `REFUSE`.
                        SMART_SYNC -> continue@modify_cell
                        // At the same time, in `SMART_ASYNC` mode,
                        // `resume` replaces the cancelled continuation
                        // with the resumption value and completes.
                        // Thus, the concurrent cancellation handler
                        // detects this value and completes this `resume`.
                        SMART_ASYNC -> {
                            // Try to put the value into the cell if there is
                            // no decision on whether the cell is in the `CANCELLED`
                            // or `REFUSE` state, and finish if the put is performed.
                            val valueToStore: Any = if (value is Continuation<*>) WrappedContinuationValue(value) else value
                            if (segment.cas(i, cellState, valueToStore)) return TRY_RESUME_SUCCESS
                        }
                    }
                }
                // The cell stores a non-cancellable
                // continuation, we can simply resume it.
                cellState is Continuation<*> -> {
                    // Resume the continuation and mark the cell
                    // as `RESUMED` to avoid memory leaks.
                    segment.set(i, RESUMED)
                    (cellState as Continuation<T>).resume(value)
                    return TRY_RESUME_SUCCESS
                }
                else -> {
                    segment.set(i, RESUMED)
                    resumeWaiter(cellState, value)
                    return TRY_RESUME_SUCCESS
                }
            }
        }
    }

    /**
     * Updates [deqIdx] to [newValue] if the current value is lower.
     * Thus, it is guaranteed that either the update is successfully
     * performed or the value of [deqIdx] is greater or equal to [newValue].
     */
    private fun adjustDeqIdx(newValue: Long): Unit = deqIdx.loop { cur ->
        if (cur >= newValue) return
        if (deqIdx.compareAndSet(cur, newValue)) return
    }

    /**
     * These modes define the strategy that [resume] should
     * use if it comes to the cell before [suspend] and finds it empty.
     * In the [asynchronous][ASYNC] mode, it puts the value into the cell,
     * so that [suspend] grabs it and immediately resumes without actual
     * suspension; in other words, an elimination happens in this case.
     * However, this strategy produces an incorrect behavior when used for some
     * data structures (e.g., for [tryAcquire][Semaphore.tryAcquire] in [Semaphore]),
     * so that the [synchronous][SYNC] was introduced. Similarly to the asynchronous one,
     * [resume] puts the value into the cell, but do not finish right after that.
     * In opposite, it waits in a bounded spin-loop (see [MAX_SPIN_CYCLES]) until
     * the value is taken, and completes after that. If the value is not taken after
     * this spin-loop is finished, [resume] marks the cell as [broken][BROKEN]
     * and fails, so that the corresponding [suspend] invocation finds the cell
     * [broken][BROKEN] and fails as well.
     */
    internal enum class ResumeMode { SYNC, ASYNC }

    /**
     * These modes define the mode that should be used for cancellation.
     * Essentially, there are two modes, simple and smart, which
     * specify whether [resume] should fail on cancelled waiters ([SIMPLE]),
     * or skip them in either [synchronous][SMART_SYNC] or [asynchronous][SMART_ASYNC]
     * way. In the asynchronous mode [resume] may pass the element to the
     * cancellation handler in order not to wait, so that the element can be
     * "hung" for a while.
     */
    internal enum class CancellationMode { SIMPLE, SMART_SYNC, SMART_ASYNC }

    /**
     * This cancellation handler is invoked when
     * the waiter located by ```segment[index]```
     * is cancelled.
     */
    private inner class SQSCancellationHandler(
        private val segment: SQSSegment,
        private val index: Int
    ) : CancelHandler() {
        override fun invoke(cause: Throwable?) {
            // Invoke the cancellation handler
            // only if the state is not `RESUMED`.
            if (!segment.tryMarkCancelling(index)) return
            // Do we use simple or smart cancellation?
            if (cancellationMode === SIMPLE) {
                // In the simple cancellation mode the logic
                // is straightforward -- mark the cell as
                // cancelled to avoid memory leaks and complete.
                segment.markCancelled(index)
                return
            }
            // We are in a smart cancellation mode.
            // Perform the cancellation-related logic and
            // check whether the value of the `resume` that
            // comes to this cell should be processed in the
            // `SegmentQueueSynchronizer` or refused by it.
            val cancelled = onCancellation()
            if (cancelled) {
                // The cell should be considered as cancelled.
                // Mark the cell correspondingly and help a
                // concurrent `resume` to process its value if
                // needed (see `SMART_ASYNC` cancellation mode).
                val value = segment.markCancelled(index) ?: return
                if (value === REFUSE) return
                // Try to resume the next waiter with the value
                // provided by a concurrent `resume`.
                if (resume(value as T)) return
                // The resumption has been failed because of the
                // `SYNC` resume mode. Return the value back to
                // the original data structure.
                returnValue(value)
            } else {
                // The value of the `resume` that comes to this
                // cell should be refused by this `SegmentQueueSynchronizer`.
                // Mark the cell correspondingly and help a concurrent
                // `resume` to process its value if needed
                // (see `SMART_ASYNC` cancellation mode).
                val value = segment.markRefused(index) ?: return
                returnRefusedValue(value as T)
            }
        }

        override fun toString() = "SQSCancellationHandler[$segment, $index]"
    }

    override fun toString(): String {
        val waiters = ArrayList<String>()
        var curSegment = head.value
        var curIdx = deqIdx.value
        while (curIdx < max(enqIdx.value, deqIdx.value)) {
            val i = (curIdx % SEGMENT_SIZE).toInt()
            waiters += when {
                curIdx < curSegment.id * SEGMENT_SIZE -> "CANCELLED"
                curSegment.get(i) is Continuation<*> -> "<cont>"
                else -> curSegment.get(i).toString()
            }
            curIdx++
            if (curIdx == (curSegment.id + 1) * SEGMENT_SIZE)
                curSegment = curSegment.next!!
        }
        return "enqIdx=${enqIdx.value},deqIdx=${deqIdx.value},waiters=$waiters"
    }
}

private fun createSegment(id: Long, prev: SQSSegment?) = SQSSegment(id, prev, 0)

/**
 * The queue of waiters in [SegmentQueueSynchronizer]
 * is represented as a linked list of these segments.
 */
private class SQSSegment(id: Long, prev: SQSSegment?, pointers: Int) : Segment<SQSSegment>(id, prev, pointers) {
    private val waiters = atomicArrayOfNulls<Any?>(SEGMENT_SIZE)
    override val maxSlots: Int get() = SEGMENT_SIZE

    @Suppress("NOTHING_TO_INLINE")
    inline fun get(index: Int): Any? = waiters[index].value

    @Suppress("NOTHING_TO_INLINE")
    inline fun set(index: Int, value: Any?) {
        waiters[index].value = value
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun cas(index: Int, expected: Any?, value: Any?): Boolean = waiters[index].compareAndSet(expected, value)

    @Suppress("NOTHING_TO_INLINE")
    inline fun getAndSet(index: Int, value: Any?): Any? = waiters[index].getAndSet(value)

    /**
     * Marks the cell as cancelled and returns `null`, so that the [resume]
     * that comes to this cell detects that it is in the `CANCELLED` state
     * and should fail or skip it depending on the cancellation mode.
     * However, in [SMART_ASYNC] cancellation mode [resume] that comes to the cell
     * with cancelled continuation asynchronously puts its value into the cell,
     * and the cancellation handler completes the resumption.
     * In this case, [markCancelled] returns this non-null value.
     *
     * If the whole segment contains [CANCELLED] markers after
     * this invocation, [onSlotCleaned] is invoked and this segment
     * is going to be removed if [head][SegmentQueueSynchronizer.head]
     * and [tail][SegmentQueueSynchronizer.tail] do not reference it.
     * Note that the segments that are not stored physically are still
     * considered as logically stored but being full of cancelled waiters.
     */
    fun markCancelled(index: Int): Any? = mark(index, CANCELLED).also {
        onSlotCleaned()
    }

    /**
     * In [SegmentQueueSynchronizer] we use different cancellation handlers for
     * normal and prompt cancellations. However, there is no way to split them
     * in the current [CancellableContinuation] API: the handler set by
     * [CancellableContinuation.invokeOnCancellation] is always invoked,
     * even on prompt cancellation. In order to guarantee that only one of
     * the handler is invoked (either the one installed by `invokeOnCancellation`
     * or the one passed in `tryResume`) we use a special intermediate state
     * `CANCELLING` for normal cancellation. Thus, if the state is already
     * `RESUMED`, then [tryMarkCancelling] returns `false` and the normal
     * cancellation handler (installed by `invokeOnCancellation`) is not
     * executed (we try to move the state to `CANCELLING` in the beginning).
     */
    fun tryMarkCancelling(index: Int): Boolean {
        while (true) {
            val cellState = get(index)
            when {
                cellState === RESUMED -> return false
                cellState is CancellableContinuation<*> -> {
                    if (cas(index, cellState, CANCELLING)) return true
                }
                else -> {
                    if (cellState is Continuation<*>)
                        error("Only cancellable continuations can be cancelled, ${cellState::class.simpleName} is found")
                    else
                        error("Unexpected cell state: $cellState")
                }
            }
        }
    }

    /**
     * Marks the cell as refused and returns `null`, so that
     * the [resume] that comes to the cell should notice
     * that its value is refused by the [SegmentQueueSynchronizer],
     * and [SegmentQueueSynchronizer.tryReturnRefusedValue]
     * is invoked in this case (if it fails, the value is put back via
     * [SegmentQueueSynchronizer.returnValue]). Since in [SMART_ASYNC]
     * cancellation mode [resume] that comes to the cell with cancelled
     * continuation asynchronously puts its value into the cell.
     * In this case, [markRefused] returns this non-null value.
     */
    fun markRefused(index: Int): Any? = mark(index, REFUSE)

    /**
     * Marks the cell with the specified [marker]
     * and returns `null` if the cell contains the
     * cancelled continuation. However, in the [SMART_ASYNC]
     * cancellation mode it is possible that [resume] comes
     * to the cell with cancelled continuation and asynchronously
     * puts its value into the cell, so that the cancellation
     * handler decides whether this value should be used for
     * resuming the next waiter or be refused. In the latter case,
     * the corresponding non-null value is returned as a result.
     */
    private fun mark(index: Int, marker: Any?): Any? =
        when (val old = getAndSet(index, marker)) {
            // Did the cell contain already cancelled or cancelling continuation?
            CANCELLING -> null
            is Continuation<*> -> {
                assert { if (old is CancellableContinuation<*>) old.isCancelled else true }
                null
            }
            // Did the cell contain an asynchronously put value?
            // (both branches deal with values)
            is WrappedContinuationValue -> old.cont
            else -> old
        }

    override fun toString() = "SQSSegment[id=$id, hashCode=${hashCode()}]"
}

/**
 * In the [smart asynchronous cancellation mode][SegmentQueueSynchronizer.CancellationMode.SMART_ASYNC]
 * it is possible that [resume] comes to the cell with cancelled continuation and
 * asynchronously puts its value into the cell, so that the cancellation handler decides whether
 * this value should be used for resuming the next waiter or be refused. When this
 * value is a continuation, it is hard to distinguish it with the one related to the cancelled
 * waiter. Thus, such values are wrapped with [WrappedContinuationValue] in this case. Note that the
 * wrapper is required only in [SegmentQueueSynchronizer.CancellationMode.SMART_ASYNC] mode
 * and is used in the asynchronous race resolution logic between cancellation and [resume]
 * invocation; this way, it is used relatively rare.
 */
private class WrappedContinuationValue(val cont: Continuation<*>)

internal expect fun <T> resumeWaiter(waiter: Any, value: T)
// allows spurious resumptions
internal expect fun suspendWaiter(waiter: Any)

@SharedImmutable
private val SEGMENT_SIZE = systemProp("kotlinx.coroutines.sqs.segmentSize", 16)
@SharedImmutable
private val MAX_SPIN_CYCLES = systemProp("kotlinx.coroutines.sqs.maxSpinCycles", 100)
@SharedImmutable
private val TAKEN = Symbol("TAKEN")
@SharedImmutable
private val BROKEN = Symbol("BROKEN")
@SharedImmutable
private val CANCELLING = Symbol("CANCELLING")
@SharedImmutable
private val CANCELLED = Symbol("CANCELLED")
@SharedImmutable
private val REFUSE = Symbol("REFUSE")
@SharedImmutable
private val RESUMED = Symbol("RESUMED")

private const val TRY_RESUME_SUCCESS = 0
private const val TRY_RESUME_FAIL_CANCELLED = 1
private const val TRY_RESUME_FAIL_BROKEN = 2

private val MAX_BACKOFF = systemProp("kotlinx.coroutines.sqs.maxBackoff", 10_000)