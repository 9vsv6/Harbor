package app.harbor.android.player

/**
 * Whether the engine's position may be saved as the progress of [currentVideoId]. While another
 * video is being prepared the engine still holds the previous file, so its position (or its end)
 * belongs to that one, not to the episode now shown.
 */
internal object ResumeGuard {
    fun canSave(preparing: Boolean, waitingForTitle: Boolean, loadedVideoId: String, currentVideoId: String): Boolean =
        !preparing && !waitingForTitle && currentVideoId.isNotEmpty() && loadedVideoId == currentVideoId
}
