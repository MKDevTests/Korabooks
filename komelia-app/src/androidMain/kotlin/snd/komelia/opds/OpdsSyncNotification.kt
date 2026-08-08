package snd.komelia.opds

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import snd.komelia.dependencies

const val catalogueSyncChannelId = "catalogue_sync_channel"
private const val CATALOGUE_SYNC_NOTIFICATION_ID = 4301

/**
 * Keeps a running catalogue sync visible outside the app.
 *
 * The sync deliberately belongs to the application rather than to the settings
 * screen, so that walking away does not kill it. That was the right call and it
 * created this problem: a job that takes **hours** on a real library — one
 * request every three seconds or so, thousands of them — was then running with
 * nothing on screen to say so. Measured on the reference library, a full
 * resynchronisation is a whole evening; a reader who forgot they started one has
 * no way to tell whether the app is working or idle, and no way to stop it
 * without going back to find the button.
 *
 * So: an ongoing notification carrying the same sentence the settings screen
 * shows (see `OpdsSyncProgress.describe`), plus the one action that matters when
 * you are not in the app — stopping.
 *
 * Deliberately *not* a foreground service. That would also protect the sync from
 * being killed while backgrounded, which is a real and separate problem, but it
 * needs a service declaration, a permission and a type, and it changes how the
 * sync is owned. This is the visibility half, and it is the half that was asked
 * for.
 */
class OpdsSyncNotifier(
    private val context: Context,
    private val catalogue: OpdsCatalogueService,
) {

    fun observe(scope: CoroutineScope) {
        scope.launch {
            catalogue.syncState.collect { state ->
                when (state) {
                    is OpdsSyncState.Running -> notify(
                        text = state.progress?.describe() ?: "Démarrage…",
                        ongoing = true,
                    )

                    // Terminal states replace the ongoing notification with one
                    // the reader can dismiss: the result of a job that ran for
                    // hours is worth reading after the fact, and a sync that
                    // failed at hour three must not disappear silently.
                    is OpdsSyncState.Done -> notify(
                        text = "${state.result.shelves} séries, ${state.result.books} livres",
                        ongoing = false,
                    )

                    is OpdsSyncState.Failed -> notify(
                        text = "Échec — ${state.message}",
                        ongoing = false,
                    )

                    is OpdsSyncState.Idle -> cancel()
                }
            }
        }
    }

    private fun notify(text: String, ongoing: Boolean) {
        if (!allowed()) return

        val builder = NotificationCompat.Builder(context, catalogueSyncChannelId)
            .setContentTitle("Synchronisation du catalogue")
            .setContentText(text)
            // The progress line is long — "Regroupement — 2 780 séries, <titre>"
            // — and a notification shows one line unless told otherwise.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)

        if (ongoing) {
            // Indeterminate: the total is not known until the walk is over, and
            // a bar that fills up and restarts twice reads as a bug.
            builder.setProgress(0, 0, true)
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Arrêter",
                stopIntent(),
            )
        }

        NotificationManagerCompat.from(context)
            .notify(CATALOGUE_SYNC_NOTIFICATION_ID, builder.build())
    }

    private fun cancel() {
        if (!allowed()) return
        NotificationManagerCompat.from(context).cancel(CATALOGUE_SYNC_NOTIFICATION_ID)
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, OpdsSyncStopReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Same check the download notifications make: on Android 13 and later,
     * posting without the runtime permission throws.
     */
    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PermissionChecker.PERMISSION_GRANTED
}

/**
 * The notification's "Arrêter" button.
 *
 * Reads the service off the global dependency holder rather than through a
 * static callback: the receiver can be created by the system long after the
 * process was restarted, and a callback set at startup would be null exactly
 * then — which is precisely when someone is trying to stop a sync they no longer
 * remember starting.
 */
class OpdsSyncStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        dependencies.value?.opdsCatalogue?.cancelSync()
    }
}
