package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import snd.komelia.settings.CommonSettingsRepository
import snd.komelia.settings.SecretsRepository

private val logger = KotlinLogging.logger { }

/**
 * Fetches one cover, when something is about to show it.
 *
 * A catalogue of twenty thousand books is roughly six hundred megabytes of
 * covers; mirroring them during the sync would multiply its length by ten and
 * fill the phone with pictures of books nobody scrolled past. So the sync
 * stores the address of a cover and this fetches it on demand — the image cache
 * keeps whatever was actually looked at.
 *
 * A cover that fails to load is not an error worth showing anyone: the grid
 * draws its placeholder and the next scroll tries again.
 */
class OpdsCoverLoader(
    private val ktor: HttpClient,
    private val settings: CommonSettingsRepository,
    private val secrets: SecretsRepository,
) {
    private val credentials = OpdsCredentialStore(settings, secrets)

    suspend fun load(url: String): ByteArray? {
        if (url.isBlank()) return null
        return try {
            val config = credentials.current()
            OpdsClient(ktor, config?.credentials).bytes(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug(e) { "cover unavailable: $url" }
            null
        }
    }
}
