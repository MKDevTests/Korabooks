package snd.komelia.settings

import androidx.datastore.core.DataStore
import io.github.snd_r.komelia.settings.AppSettings
import io.github.snd_r.komelia.settings.copy
import kotlinx.coroutines.flow.firstOrNull

/**
 * Per-key secrets, in the app's protobuf DataStore.
 *
 * This used to ignore [url] entirely and keep one `rememberMe` string for every
 * key at once — while the interface has always been keyed, and the desktop
 * keyring implementation has always honoured the key. Two callers share the
 * store: the Komga session cookie under the server url, and the OPDS catalogue
 * login under `opds:<url>`. Saving the second therefore destroyed the first, and
 * reading the cookie back handed `<user><password>` to the cookie parser,
 * which produced a cookie whose name held a NUL. Ktor then refused to build the
 * request and logging in failed with an IllegalHeaderValueException — on an
 * account that was, as far as anyone could tell, already logged in.
 */
class AndroidSecretsRepository(
    private val dataStore: DataStore<AppSettings>
) : SecretsRepository {

    override suspend fun getCookie(url: String): String? {
        val user = (dataStore.data.firstOrNull() ?: return null).user
        user.secretsMap[url]?.ifBlank { null }?.let { return it }
        return user.rememberMe.ifBlank { null }?.takeIf { legacyValueBelongsTo(url, it) }
    }

    override suspend fun setCookie(url: String, cookie: String) {
        dataStore.updateData {
            it.copy {
                user = user.copy {
                    secrets[url] = cookie
                    // One-way: once a key is in the map, the old shared field has
                    // nothing left to say about it.
                    if (legacyValueBelongsTo(url, rememberMe)) rememberMe = ""
                }
            }
        }
    }

    override suspend fun deleteCookie(url: String) {
        dataStore.updateData {
            it.copy {
                user = user.copy {
                    secrets.remove(url)
                    if (legacyValueBelongsTo(url, rememberMe)) rememberMe = ""
                }
            }
        }
    }
}

/**
 * Whether the old shared field can be read as the value for [key].
 *
 * Existing installs have a value in there and no map, and it would be rude to
 * make someone type their catalogue password again over an implementation
 * detail. But the field belongs to whichever caller wrote last, so it is claimed
 * by shape rather than assumed: an OPDS login is two fields separated by a NUL,
 * a Set-Cookie header has an `=` and no control characters. Nothing else is
 * accepted, which is what keeps the corrupted case from coming back.
 *
 * Delete this once installs from before the `secrets` map are gone.
 */
internal const val NUL = '\u0000'

internal fun legacyValueBelongsTo(key: String, legacy: String): Boolean {
    if (legacy.isBlank()) return false
    val looksLikeOpdsLogin = legacy.contains(NUL)
    return if (key.startsWith("opds:")) looksLikeOpdsLogin
    else !looksLikeOpdsLogin && legacy.contains('=')
}
