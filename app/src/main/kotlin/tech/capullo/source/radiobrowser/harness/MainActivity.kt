package tech.capullo.source.radiobrowser.harness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import tech.capullo.source.radiobrowser.RadioBrowserSource
import tech.capullo.source.radiobrowser.data.model.Station

/**
 * Minimal harness proving `capullo-source-radiobrowser` is consumable against the SPI: construct
 * [RadioBrowserSource], seed a rotation queue, and read back the [tech.capullo.audio.contracts.PlaybackQueue]
 * + [tech.capullo.audio.contracts.NowPlaying] it exposes. (mediaRequestFor / Shazam do live network
 * I/O and are exercised by the real app, not here.)
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = CoroutineScope(Dispatchers.Main)
        val source = RadioBrowserSource(this, scope)
        source.setQueue(
            listOf(
                Station(
                    uuid = "demo-1",
                    name = "Capullo FM",
                    url = "https://example.com/stream.mp3",
                    country = "Spain",
                    countryCode = "ES",
                    codec = "MP3",
                    bitrate = 128,
                ),
            ),
        )
        val queue = source.queue()
        val np = source.nowPlaying.value
        setContentView(
            TextView(this).apply {
                text = buildString {
                    appendLine("capullo-source-radiobrowser harness")
                    appendLine("source: ${source.javaClass.simpleName}")
                    appendLine("queue: size=${queue.size} rotating=${queue.isRotating} id0=${queue.idAt(0)}")
                    appendLine("nowPlaying: album=${np.album} extras=${np.extras}")
                }
            },
        )
    }
}
