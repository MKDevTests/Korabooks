package snd.komelia.ui

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

sealed interface LoadState<out T> {
    data object Uninitialized : LoadState<Nothing>
    data object Loading : LoadState<Nothing>

    /**
     * Logged on construction, always.
     *
     * A screen showing "no transaction in context" and a logcat showing nothing
     * at all cost an afternoon: every failure a user can read deserves a stack
     * trace someone can read too, and the only place that is guaranteed to see
     * all of them is here.
     */
    data class Error(val exception: Throwable) : LoadState<Nothing> {
        init {
            logger.error(exception) { "screen load failed: ${exception.message}" }
        }
    }

    data class Success<T>(val value: T) : LoadState<T>
}
