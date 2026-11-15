package com.eliosnotes.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Process-wide scope: saves started when an editor closes must outlive its UI. */
object AppScope : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
}

/**
 * Runs every database write/load strictly in submission order on one worker, so an old
 * snapshot can never overwrite a newer one and a reload always sees earlier saves.
 */
object SaveQueue {
    private class Queued(val task: suspend () -> Unit, val onError: (Throwable) -> Unit)

    private val channel = Channel<Queued>(Channel.UNLIMITED)

    init {
        AppScope.launch {
            for (item in channel) {
                try {
                    item.task()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Log the exception type only: messages may contain note content.
                    Log.e("SaveQueue", "task failed: ${e.javaClass.simpleName}")
                    try { item.onError(e) } catch (ignored: Throwable) {}
                }
            }
        }
    }

    /** [onError] runs on the queue worker when [task] throws, so callers can retry or warn the user. */
    fun submit(onError: (Throwable) -> Unit = {}, task: suspend () -> Unit) {
        channel.trySend(Queued(task, onError))
    }

    suspend fun <T> run(task: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        submit {
            try {
                result.complete(task())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result.await()
    }
}
