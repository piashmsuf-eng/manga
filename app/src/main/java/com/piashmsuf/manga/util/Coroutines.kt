package com.piashmsuf.manga.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching] but does **not** swallow [CancellationException]. Suspending
 * code must always rethrow it so structured concurrency keeps working.
 */
inline fun <R> runSuspendCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Result.failure(t)
}
