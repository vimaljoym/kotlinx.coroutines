/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:JvmMultifileClass
@file:JvmName("ChannelsKt")
@file:Suppress("DEPRECATION_ERROR")

package kotlinx.coroutines.channels

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.*
import kotlin.coroutines.*
import kotlin.jvm.*

internal const val DEFAULT_CLOSE_MESSAGE = "Channel was closed"


// -------- Operations on BroadcastChannel --------

/**
 * Opens subscription to this [BroadcastChannel] and makes sure that the given [block] consumes all elements
 * from it by always invoking [cancel][ReceiveChannel.cancel] after the execution of the block.
 *
 * **Note: This API will become obsolete in future updates with introduction of lazy asynchronous streams.**
 *           See [issue #254](https://github.com/Kotlin/kotlinx.coroutines/issues/254).
 */
@ObsoleteCoroutinesApi
public inline fun <E, R> BroadcastChannel<E>.consume(block: ReceiveChannel<E>.() -> R): R {
    val channel = openSubscription()
    try {
        return channel.block()
    } finally {
        channel.cancel()
    }
}

/**
 * Retrieves and removes the element from this channel suspending the caller while this channel [isEmpty]
 * or returns `null` if the channel is [closed][Channel.isClosedForReceive].
 *
 * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
 * function is suspended, this function immediately resumes with [CancellationException].
 * There is a **prompt cancellation guarantee**. If the job was cancelled while this function was
 * suspended, it will not resume successfully. If the `receiveOrNull` call threw [CancellationException] there is no way
 * to tell if some element was already received from the channel or not. See [Channel] documentation for details.
 *
 * Note, that this function does not check for cancellation when it is not suspended.
 * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
 *
 * This extension is defined only for channels on non-null types, so that generic functions defined using
 * these extensions do not accidentally confuse `null` value and a normally closed channel, leading to hard
 * to find bugs.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
@ExperimentalCoroutinesApi // since 1.3.0, tentatively stable in 1.4.x
public suspend fun <E : Any> ReceiveChannel<E>.receiveOrNull(): E? {
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    return (this as ReceiveChannel<E?>).receiveOrNull()
}

/**
 * Clause for [select] expression of [receiveOrNull] suspending function that selects with the element that
 * is received from the channel or selects with `null` if the channel
 * [isClosedForReceive][ReceiveChannel.isClosedForReceive] without cause. The [select] invocation fails with
 * the original [close][SendChannel.close] cause exception if the channel has _failed_.
 *
 * This extension is defined only for channels on non-null types, so that generic functions defined using
 * these extensions do not accidentally confuse `null` value and a normally closed channel, leading to hard
 * to find bugs.
 **/
@ExperimentalCoroutinesApi // since 1.3.0, tentatively stable in 1.4.x
public fun <E : Any> ReceiveChannel<E>.onReceiveOrNull(): SelectClause1<E?> {
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    return (this as ReceiveChannel<E?>).onReceiveOrNull
}

/**
 * Makes sure that the given [block] consumes all elements from the given channel
 * by always invoking [cancel][ReceiveChannel.cancel] after the execution of the block.
 *
 * The operation is _terminal_.
 */
public inline fun <E, R> ReceiveChannel<E>.consume(block: ReceiveChannel<E>.() -> R): R {
    var cause: Throwable? = null
    try {
        return block()
    } catch (e: Throwable) {
        cause = e
        throw e
    } finally {
        cancelConsumed(cause)
    }
}

/**
 * Performs the given [action] for each received element and [cancels][ReceiveChannel.cancel]
 * the channel after the execution of the block.
 * If you need to iterate over the channel without consuming it, a regular `for` loop should be used instead.
 *
 * The operation is _terminal_.
 * This function [consumes][ReceiveChannel.consume] all elements of the original [ReceiveChannel].
 */
public suspend inline fun <E> ReceiveChannel<E>.consumeEach(action: (E) -> Unit): Unit =
    consume {
        for (e in this) action(e)
    }

/**
 * Returns a [List] containing all elements.
 *
 * The operation is _terminal_.
 * This function [consumes][ReceiveChannel.consume] all elements of the original [ReceiveChannel].
 */
@OptIn(ExperimentalStdlibApi::class)
public suspend fun <E> ReceiveChannel<E>.toList(): List<E> = buildList {
    consumeEach {
        add(it)
    }
}

/**
 * Subscribes to this [BroadcastChannel] and performs the specified action for each received element.
 *
 * **Note: This API will become obsolete in future updates with introduction of lazy asynchronous streams.**
 *           See [issue #254](https://github.com/Kotlin/kotlinx.coroutines/issues/254).
 */
@ObsoleteCoroutinesApi
public suspend inline fun <E> BroadcastChannel<E>.consumeEach(action: (E) -> Unit): Unit =
    consume {
        for (element in this) action(element)
    }


@PublishedApi
internal fun ReceiveChannel<*>.cancelConsumed(cause: Throwable?) {
    cancel(cause?.let {
        it as? CancellationException ?: CancellationException("Channel was consumed, consumer had failed", it)
    })
}

