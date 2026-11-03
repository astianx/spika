package org.astianx.midori.one.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes transfers independently of state/crypto work. The state mutex is only
 * held for preparation and completion, never for blocking provider or network IO.
 */
internal class ChatTransferCoordinator(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val stateMutex: Mutex,
    private val onFailure: (Exception) -> Unit
) {
    private val transfers = Mutex()
    private val jobs = mutableSetOf<Job>()

    fun <T : Any, R> start(
        prepare: () -> T?,
        transfer: (T) -> R,
        commit: (T, R) -> Unit,
        isCurrent: (T) -> Boolean = { true },
        cleanup: (T, R?) -> Unit = { _, _ -> }
    ): Job {
        val job = scope.launch(io) {
            transfers.withLock {
                val input = try { stateMutex.withLock { prepare() } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { stateMutex.withLock { onFailure(e) }; null }
                    ?: return@withLock
                var result: R? = null
                try {
                    val completed = runInterruptible(io) { transfer(input).also { result = it } }
                    stateMutex.withLock { if (isCurrent(input)) commit(input, completed) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { stateMutex.withLock { if (isCurrent(input)) onFailure(e) } }
                finally {
                    // Cleanup must also run for cancellation while reacquiring the state mutex.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        stateMutex.withLock { cleanup(input, result) }
                    }
                }
            }
        }
        synchronized(jobs) { jobs.add(job) }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
        return job
    }

    fun cancel() { synchronized(jobs) { jobs.toList() }.forEach { it.cancel() } }
}
