package de.corespace.shroud.core.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * [withContext] for a block that **produces a secret** (the history key, restored or derived
 * identity material): the result either reaches the caller or is wiped with [wipe].
 *
 * Plain `withContext` has a prompt-cancellation guarantee: when the caller is cancelled while the
 * block runs on another dispatcher, the block's finished result is dropped on the floor and a
 * `CancellationException` thrown — the caller's `finally { key.fill(0) }` never sees the array,
 * and the key stays on the heap until GC (plan §1.4, invariant 5: keys never outlive a lock in
 * RAM). A lock screen left mid-unlock, or the vault's 90 s timeout, cancels exactly there.
 *
 * Here the block parks its result in a slot first; the slot is emptied when the result is handed
 * over, and whatever is still in it when `withContext` throws is wiped.
 */
suspend fun <T> withContextHandingOver(
    context: CoroutineContext,
    wipe: (T & Any) -> Unit,
    block: suspend CoroutineScope.() -> T,
): T {
    val slot = AtomicReference<T?>(null)
    try {
        val result = withContext(context) { block().also { slot.set(it) } }
        slot.set(null)
        return result
    } finally {
        slot.getAndSet(null)?.let(wipe)
    }
}
