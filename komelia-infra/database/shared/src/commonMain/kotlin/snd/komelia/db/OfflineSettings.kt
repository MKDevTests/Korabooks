package snd.komelia.db

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.serialization.Serializable
import snd.komelia.offline.server.model.OfflineMediaServerId
import snd.komelia.offline.user.model.OfflineUser
import snd.komga.client.user.KomgaUserId
import kotlin.time.Instant

@Serializable
data class OfflineSettings(
    val isOfflineModeEnabled: Boolean = false,

    /**
     * Narrows every list to the books whose file is on disk.
     *
     * A catalogue mirror holds a row per book on the server, so the library
     * looks the same with or without a connection. This is the switch that makes
     * it show what can actually be opened.
     */
    val downloadedOnly: Boolean = false,
    val downloadDirectory: PlatformFile,
    val userId: KomgaUserId = OfflineUser.ROOT,
    val serverId: OfflineMediaServerId? = null,
    val readProgressSyncDate: Instant? = null,
    val dataSyncDate: Instant? = null,
)
