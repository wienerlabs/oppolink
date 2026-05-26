package link.oppolink.service

/**
 * Renders an elapsed-time millisecond count as either `mm:ss` (under
 * an hour) or `h:mm:ss` (an hour or more). Pure function so it is
 * trivially unit-testable from `src/test`.
 *
 * Used by [CallForegroundService] for the notification ticker and any
 * future surface that needs to display call duration in the same
 * shape.
 */
internal fun formatElapsed(elapsedMs: Long): String {
    val totalSec = (elapsedMs / 1000).coerceAtLeast(0).toInt()
    val hh = totalSec / 3600
    val mm = (totalSec % 3600) / 60
    val ss = totalSec % 60
    return if (hh > 0) {
        "%d:%02d:%02d".format(hh, mm, ss)
    } else {
        "%02d:%02d".format(mm, ss)
    }
}
