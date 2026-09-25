package com.firebolt9907.tangentbrowser.ui.content

/** Main-thread identity/ordering guard for asynchronous compositor captures and bitmap conversion. */
internal class SnapshotCaptureState(initialUrl: String) {
    data class Ticket(val url: String, val generation: Long, val sequence: Long)

    var active = true
    var url = initialUrl
        private set
    var generation = 0L
        private set
    private var settled = false
    private var nextSequence = 0L
    private var acceptedSequence = 0L

    fun invalidate() {
        generation++
        settled = false
    }

    fun navigate(newUrl: String) {
        invalidate()
        url = newUrl
    }

    fun settle(expectedUrl: String, expectedGeneration: Long): Boolean {
        if (!active || url != expectedUrl || generation != expectedGeneration) return false
        settled = true
        return true
    }

    fun request(): Ticket? =
        if (active && settled && url.isNotBlank() && url != "about:blank") {
            Ticket(url, generation, ++nextSequence)
        } else null

    fun isCurrent(ticket: Ticket): Boolean =
        active && settled && ticket.url == url && ticket.generation == generation

    fun accept(ticket: Ticket): Boolean {
        if (!isCurrent(ticket) || ticket.sequence <= acceptedSequence) return false
        acceptedSequence = ticket.sequence
        return true
    }
}
