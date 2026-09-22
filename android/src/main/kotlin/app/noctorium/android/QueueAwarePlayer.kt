package app.noctorium.android

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import app.noctorium.playback.RepeatMode

/**
 * The player as the lock screen, the notification and a headset see it.
 *
 * ExoPlayer is only ever given one track at a time. The queue lives in `core`, because it is the same
 * queue on the desktop, it survives a track failing to resolve, and filling ExoPlayer's own playlist
 * would mean resolving fifty stream URLs up front that expire before most of them are reached.
 *
 * The cost of that, until now, was silent: with a single item in its timeline ExoPlayer correctly reports
 * that there is nothing to skip to, Media3 believes it, and the skip button on the lock screen, in the
 * notification and on a pair of headphones does nothing at all. It is not disabled or missing -- it is
 * there, and pressing it has no effect, which is worse. The session advertised SKIP_TO_PREVIOUS and not
 * SKIP_TO_NEXT, which is exactly what a one-item timeline looks like from the outside.
 *
 * So the session is handed this instead: the same player, telling the truth about the real queue and
 * passing skips to the thing that owns it.
 */
class QueueAwarePlayer(
    player: Player,
    private val goNext: () -> Unit,
    private val goPrevious: () -> Unit,
    private val canGoNext: () -> Boolean,
    private val canGoPrevious: () -> Boolean,
    /** The queue's repeat mode, which is the truth; the bare player only knows whether it is looping. */
    private val repeatMode: () -> RepeatMode = { RepeatMode.OFF },
    private val setRepeat: (RepeatMode) -> Unit = {},
) : ForwardingPlayer(player) {

    /**
     * Repeat as the queue has it, not as ExoPlayer has it.
     *
     * ExoPlayer is told to loop only for repeat-one; repeat-all is the queue's business, and reporting
     * the player's own setting would tell a car's head unit that repeat was off while the queue went
     * round. Setting it from outside goes to the queue for the same reason.
     */
    override fun getRepeatMode(): Int = when (repeatMode()) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    override fun setRepeatMode(repeatMode: Int) = setRepeat(
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        },
    )

    /**
     * What Android is allowed to ask for.
     *
     * Media3 reads this once per state change and builds the notification's buttons from it, so a command
     * left out here is a button that does nothing rather than a button that is missing.
     */
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> canGoNext()
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> canGoPrevious()
        else -> super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean = canGoNext()

    override fun hasPreviousMediaItem(): Boolean = canGoPrevious()

    /**
     * All four go to the queue.
     *
     * seekToPrevious ordinarily restarts the current track when you are far enough into it, and that is
     * deliberately not done here: the button beside this one in the application moves through the queue,
     * and a skip that means two different things depending on where you pressed it is a worse surprise
     * than one that always means the same thing.
     */
    override fun seekToNext() = goNext()

    override fun seekToNextMediaItem() = goNext()

    override fun seekToPrevious() = goPrevious()

    override fun seekToPreviousMediaItem() = goPrevious()
}
