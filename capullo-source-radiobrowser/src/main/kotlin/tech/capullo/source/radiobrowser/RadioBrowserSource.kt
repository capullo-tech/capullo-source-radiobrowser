package tech.capullo.source.radiobrowser

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.NowPlayingSource
import tech.capullo.audio.contracts.PlaybackQueue
import tech.capullo.source.radiobrowser.data.model.Station
import tech.capullo.source.radiobrowser.resolver.PlaylistResolver
import tech.capullo.source.radiobrowser.shazam.ShazamRecognizer

/**
 * The radiobrowser source - the *first* real implementation of the platform SPI
 * ([MediaSourceProvider] + [NowPlayingSource]), for QuantumCast-style internet radio. It proves the
 * `capullo-audio-contracts` seam is implementable end-to-end .
 *
 * The ingress concerns that live here:
 *  - resolve a station uuid → playable stream URL, unwrapping container-less playlists
 *    (`.pls`/`.m3u`/`.asx`) and hinting HLS, via [PlaylistResolver] → [MediaRequest];
 *  - present the current station list as a rotating [PlaybackQueue];
 *  - assemble [NowPlaying] from the station's own tags, then enrich title/artist and streaming
 *    links via the Shazam loop. Shazam re-fetches [Station.streamUrl] on its own (see AudioCapturer),
 *    so identification needs nothing from the engine - the source→engine direction stays one-way.
 *
 * What stays in the app (and gets thinned in the app): the search/favorites UI, the random-station
 * rotation timers, sleep timer, and snapclient control. Those drive this source by calling
 * [setQueue] and observing [nowPlaying]; the Radio Browser API + favorites DB access they need lives
 * in `data/` (RadioRepository), whose instance the app owns.
 */
public class RadioBrowserSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val shazamIntervalSeconds: Int = 30,
) : MediaSourceProvider, NowPlayingSource {

    private val _nowPlaying = MutableStateFlow(NowPlaying.EMPTY)
    override val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    // The current rotation, addressed by the engine via station uuid (the queue's logical id).
    private val stationsByUuid = LinkedHashMap<String, Station>()
    private var order: List<String> = emptyList()
    @Volatile private var currentIndex: Int = 0

    private var shazamJob: Job? = null

    /**
     * Replace the rotation queue. The app calls this with search results, a favorites group, or a
     * freshly pulled random batch; [startIndex] is the station to treat as current.
     */
    public fun setQueue(stations: List<Station>, startIndex: Int = 0) {
        stationsByUuid.clear()
        stations.forEach { stationsByUuid[it.uuid] = it }
        order = stations.map { it.uuid }
        currentIndex = startIndex.coerceIn(0, (stations.size - 1).coerceAtLeast(0))
        onStationChanged()
    }

    // --- MediaSourceProvider ---

    override suspend fun mediaRequestFor(id: String): MediaRequest = withContext(Dispatchers.IO) {
        val station = stationsByUuid[id] ?: error("Unknown station id: $id")
        val url = station.streamUrl
        // Only container-less playlists (.pls/.m3u/.asx) need unwrapping to their first stream entry
        // before ExoPlayer; a direct stream URL is handed through untouched. Parity with
        // QuantumCast's PlaybackService.startExoToFifo.
        if (PlaylistResolver.hasPlaylistExtension(url)) {
            val r = PlaylistResolver.resolve(url)
            MediaRequest(uri = r?.url ?: url, mimeType = r?.mimeType)
        } else {
            MediaRequest(uri = url)
        }
    }

    override fun queue(): PlaybackQueue = RadioRotationQueue(order, currentIndex)

    override fun onQueueAdvanced(currentIndex: Int) {
        this.currentIndex = currentIndex
        onStationChanged()
    }

    // --- Now-playing assembly + Shazam enrichment ---

    private fun currentStation(): Station? = order.getOrNull(currentIndex)?.let { stationsByUuid[it] }

    /** Reset now-playing to the current station's own metadata and (re)start identification. */
    private fun onStationChanged() {
        val station = currentStation()
        if (station == null) {
            _nowPlaying.value = NowPlaying.EMPTY
            shazamJob?.cancel(); shazamJob = null
            return
        }
        _nowPlaying.value = baseNowPlaying(station)
        startShazamLoop(station)
    }

    /** Station-only now-playing (no identification yet): station name as both title and album. */
    private fun baseNowPlaying(station: Station): NowPlaying = NowPlaying(
        title = station.name,
        artist = "",
        album = station.name,
        streamUrl = station.streamUrl,
        isPlaying = true,
        canGoNext = order.size > 1,
        canGoPrevious = order.size > 1,
        extras = buildMap {
            if (station.country.isNotBlank()) put("country", station.country)
            if (station.countryCode.isNotBlank()) put("countryCode", station.countryCode)
            if (station.codec.isNotBlank()) put("codec", station.codec)
            if (station.bitrate > 0) put("bitrate", station.bitrate.toString())
        },
    )

    /**
     * Periodically identify the current track: capture a few seconds of the live stream and run the
     * Shazam pipeline. On a hit, title/artist and streaming links merge into [NowPlaying]; on a miss
     * the station-name base is kept. Cadence mirrors QuantumCast's ~4s warm-up + interval.
     */
    private fun startShazamLoop(station: Station) {
        shazamJob?.cancel()
        shazamJob = scope.launch(Dispatchers.IO) {
            delay(WARMUP_MS)
            while (isActive) {
                val result = runCatching { ShazamRecognizer.recognize(station.streamUrl, context) }.getOrNull()
                if (result != null && result.trackName.isNotBlank()) {
                    _nowPlaying.update { np ->
                        // Drop a late result that arrives after the station already changed.
                        if (np.streamUrl != station.streamUrl) {
                            np
                        } else {
                            np.copy(
                                title = result.trackName,
                                artist = result.artistName,
                                extras = np.extras + buildMap {
                                    if (result.youtubeUrl.isNotBlank()) put("youtubeUrl", result.youtubeUrl)
                                    if (result.spotifyUrl.isNotBlank()) put("spotifyUrl", result.spotifyUrl)
                                    if (result.appleMusicUrl.isNotBlank()) put("appleMusicUrl", result.appleMusicUrl)
                                    if (result.artworkUrl.isNotBlank()) put("artworkUrl", result.artworkUrl)
                                },
                            )
                        }
                    }
                }
                delay((shazamIntervalSeconds - WARMUP_SECONDS).coerceAtLeast(MIN_INTERVAL_SECONDS) * 1000L)
            }
        }
    }

    /** Stop identification and clear state (call from the app's onDestroy/stop). */
    public fun stop() {
        shazamJob?.cancel()
        shazamJob = null
        _nowPlaying.value = NowPlaying.EMPTY
    }

    private companion object {
        const val WARMUP_SECONDS = 4
        const val WARMUP_MS = WARMUP_SECONDS * 1000L
        const val MIN_INTERVAL_SECONDS = 10
    }
}
