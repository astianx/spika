package org.astianx.midori.one.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ChatSendAttempt(val message: ChatMessage, val client: ChatTransport, val generation: Long)

/** A single network sender. State commits use the state writer only at transitions;
 * device discovery, encryption, and stanza emission never hold the UI state mutex.
 */
internal class ChatSendCoordinator(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val stateMutex: Mutex,
    private val onFailure: (Exception) -> Unit
) {
    private val sender = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    fun submit(key: String, prepare: () -> ChatSendAttempt?,
        emitting: (ChatSendAttempt) -> Unit, complete: (ChatSendAttempt, Result<Unit>) -> Unit) {
        synchronized(jobs) {
            if (jobs.containsKey(key)) return
            val job = scope.launch(io, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                sender.withLock {
                    val attempt = try { stateMutex.withLock { prepare() } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { stateMutex.withLock { onFailure(e) }; null } ?: return@withLock
                    val result = runCatching {
                        runInterruptible(io) {
                            attempt.client.sendDurably(attempt.message.peer, ChatPayload.encode(attempt.message), attempt.message.id) {
                                runBlocking { stateMutex.withLock { emitting(attempt) } }
                            }
                        }
                    }
                    withContext(NonCancellable) {
                        stateMutex.withLock {
                            try { complete(attempt, result) } catch (e: Exception) { onFailure(e) }
                        }
                    }
                    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                }
            }
            jobs[key] = job
            job.invokeOnCompletion { synchronized(jobs) { if (jobs[key] === job) jobs.remove(key) } }
            job.start()
        }
    }
    fun cancel() { synchronized(jobs) { jobs.values.toList() }.forEach { it.cancel() } }
}
