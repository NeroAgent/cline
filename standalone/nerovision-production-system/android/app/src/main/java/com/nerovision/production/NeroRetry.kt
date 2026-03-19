package com.nerovision.production

object NeroRetry {
    fun <T> run(action: () -> T): T {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < NeroContract.Retry.MAX_ATTEMPTS) {
            try {
                return action()
            } catch (throwable: Throwable) {
                lastError = throwable
                val delayMs = NeroContract.Retry.BASE_DELAY_MS * (1L shl attempt)
                Thread.sleep(delayMs)
                attempt += 1
            }
        }
        throw lastError ?: IllegalStateException("Retry failed without an exception")
    }
}
