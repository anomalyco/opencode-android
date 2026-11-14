package ai.opencode.feature.composer

import android.os.CancellationSignal
import android.os.OperationCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * One cancellable platform request (a MediaStore thumbnail or image read) that resumes exactly once
 * (iOS `OneShotRequestGate`): cancelling the coroutine cancels the request through its
 * [CancellationSignal], and a cancelled or failed request reads as null.
 */
class OneShotRequestGate {
    val signal = CancellationSignal()

    val isCancelled: Boolean get() = signal.isCanceled

    fun cancel() {
        if (!signal.isCanceled) signal.cancel()
    }

    /** Runs [load] on the IO dispatcher, cancelling the platform request if the caller is cancelled. */
    suspend fun <T : Any> run(load: (CancellationSignal) -> T?): T? = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { if (it != null) cancel() }
        try {
            if (isCancelled) null else load(signal)
        } catch (_: OperationCanceledException) {
            null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } finally {
            handle?.dispose()
        }
    }
}
