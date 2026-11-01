package com.truelockmc.streambert

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-screen TV player. Provider pages are resolved to an HLS/MP4/DASH URL,
 * then Media3 plays that URL with the remote.
 */
@UnstableApi
class PlayerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var resolver: StreamResolver? = null
    private var sources = JSONArray()
    private var index = 0
    private var sourceId = ""
    private var sourceLabel = ""
    private var ready = false
    private var reported = false
    private var startMs = 0L
    private var subsOn = true
    private var hideControls: Runnable? = null
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var controls: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        root = findViewById(R.id.player_root)
        playerView = findViewById(R.id.player_view)
        status = findViewById(R.id.player_status)
        statusText = findViewById(R.id.player_status_text)
        controls = findViewById(R.id.player_controls)
        titleView = findViewById(R.id.player_title)
        timeView = findViewById(R.id.player_time)
        titleView.text = intent.getStringExtra("title") ?: "Emberlane"
        startMs = intent.getLongExtra("startMs", 0L)
        val direct = intent.getStringExtra("directUrl")
        if (!direct.isNullOrBlank()) {
            val headers = linkedMapOf("User-Agent" to StreambertEngine.UA)
            play(StreamResolver.Hit(direct, headers, direct, null), "direct", "Direct")
            return
        }
        val raw = intent.getStringExtra("sources") ?: "[]"
        sources = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
        if (sources.length() == 0) {
            showStatus("No sources were provided for this title.")
            return
        }
        resolver = StreamResolver(this)
        tryNext()
    }

    private fun tryNext() {
        releasePlayer()
        if (index >= sources.length()) {
            showStatus("None of the sources started. Try another title, or check the network.")
            return
        }
        val item = sources.optJSONObject(index) ?: run {
            index += 1
            tryNext()
            return
        }
        index += 1
        sourceId = item.optString("id")
        sourceLabel = item.optString("label").ifBlank { sourceId }
        val url = item.optString("url")
        if (url.isBlank()) {
            tryNext()
            return
        }
        android.util.Log.i("Emberlane", "open $sourceLabel $url")
        ready = false
        showStatus("Opening $sourceLabel…")
        resolver?.resolve(root, url) { hit ->
            if (isFinishing) return@resolve
            if (hit == null) {
                showStatus("$sourceLabel had no stream. Trying the next source…")
                main.postDelayed({ if (!isFinishing) tryNext() }, 400)
            } else {
                play(hit, sourceId, sourceLabel)
            }
        }
    }

    private fun play(hit: StreamResolver.Hit, id: String, label: String) {
        sourceId = id
        sourceLabel = label
        showStatus("Starting $label…")
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(hit.headers["User-Agent"] ?: StreambertEngine.UA)
            .setDefaultRequestProperties(hit.headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        val builder = MediaItem.Builder().setUri(hit.url)
        val sub = hit.subtitle
        if (!sub.isNullOrBlank() && (sub.startsWith("http://") || sub.startsWith("https://"))) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                        .setMimeType(if (sub.contains(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        exo.setMediaItem(builder.build(), startMs.coerceAtLeast(0L))
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready = true
                    status.visibility = View.GONE
                    showControls()
                    updateTime()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("Emberlane", "player ${error.errorCodeName} ${error.message}")
                if (!ready) {
                    showStatus("$label failed. Trying the next source…")
                    main.post { tryNext() }
                } else {
                    showStatus(error.errorCodeName)
                }
            }
        })
        exo.prepare()
        playerView.player = exo
        player = exo
        applySubtitles()
    }

    private fun applySubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
            .build()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun showStatus(text: String) {
        statusText.text = text
        status.visibility = View.VISIBLE
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        updateTime()
        hideControls?.let { main.removeCallbacks(it) }
        val task = Runnable { controls.visibility = View.GONE }
        hideControls = task
        main.postDelayed(task, 5000)
    }

    private fun updateTime() {
        val exo = player ?: return
        val pos = exo.currentPosition.coerceAtLeast(0L)
        val dur = exo.duration.coerceAtLeast(0L)
        timeView.text = "${format(pos)}  /  ${if (dur > 0) format(dur) else "--:--"}   ·   $sourceLabel"
    }

    private fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun toggle() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        showControls()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration
        var next = exo.currentPosition + deltaMs
        if (next < 0) next = 0
        if (dur > 0 && next > dur) next = dur
        exo.seekTo(next)
        showControls()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                toggle()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                showControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-10_000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(10_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                seekBy(30_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                seekBy(-10_000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                showControls()
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_BOOKMARK -> {
                subsOn = !subsOn
                applySubtitles()
                showStatus(if (subsOn) "Subtitles on" else "Subtitles off")
                main.postDelayed({ if (ready) status.visibility = View.GONE }, 900)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (controls.visibility == View.VISIBLE && ready) {
                    controls.visibility = View.GONE
                    return true
                }
                close()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun close() {
        report()
        finish()
    }

    private fun report() {
        if (reported) return
        reported = true
        val exo = player
        val result = JSONObject()
            .put("ok", ready)
            .put("positionMs", exo?.currentPosition ?: 0L)
            .put("durationMs", exo?.duration ?: 0L)
            .put("sourceId", sourceId)
            .put("ended", exo?.playbackState == Player.STATE_ENDED)
        if (!ready) result.put("error", statusText.text?.toString() ?: "Playback did not start")
        StreambertEngine.finishPlayback(result)
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        hideControls?.let { main.removeCallbacks(it) }
        report()
        resolver?.stop()
        resolver = null
        releasePlayer()
        super.onDestroy()
    }
}
