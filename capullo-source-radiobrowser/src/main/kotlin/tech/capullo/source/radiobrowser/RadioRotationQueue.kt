package tech.capullo.source.radiobrowser

import tech.capullo.audio.contracts.PlaybackQueue

/**
 * A rotating single-station queue: next/previous are always meaningful because internet radio has
 * no "end" (contrast Telecloud's finite playlist). The engine reads [idAt] to get a station uuid,
 * which [RadioBrowserSource.mediaRequestFor] resolves back to a playable stream URL.
 */
internal class RadioRotationQueue(
    private val order: List<String>,
    override val currentIndex: Int,
) : PlaybackQueue {
    override val size: Int get() = order.size
    override val isRotating: Boolean get() = true
    override fun idAt(index: Int): String? = order.getOrNull(index)
}
