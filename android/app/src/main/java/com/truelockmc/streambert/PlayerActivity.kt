package com.truelockmc.streambert

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayList
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-screen player. Provider pages resolve to a direct stream, then Media3
 * plays it. The remote overlay is a row of focusable controls.
 */
@UnstableApi
class PlayerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var resolver: StreamResolver? = null
    private var sources = JSONArray()
    private var index = 0
    private var playingIndex = -1
    private var sourceId = ""
    private var sourceLabel = ""
    private var ready = false
    private var reported = false
    private var startMs = 0L
    private var subsOn = true
    private var handoff = ""
    private var preferredLang = ""
    private var chosenLang = ""
    private var audioLocked = false
    private var nextRequested = false
    private var currentHit: StreamResolver.Hit? = null
    private var overlay = false
    private var menuOpen = false
    private var subSize = 1
    private var subStyle = 0
    private var speed = 1f
    private var aspect = 0
    private var hideControls: Runnable? = null
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var controls: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var seekRow: LinearLayout
    private lateinit var timePos: TextView
    private lateinit var timeDur: TextView
    private lateinit var seekBar: ProgressBar
    private lateinit var actionsRow: LinearLayout
    private lateinit var menuScroll: ScrollView
    private lateinit var menuList: LinearLayout
    private lateinit var hintView: TextView
    private lateinit var playButton: TextView
    private lateinit var volumeButton: TextView
    private var menuAnchor: View? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (overlay) refreshChrome()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val navy = Color.parseColor("#040F2D")
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.setBackgroundDrawable(ColorDrawable(navy))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        root = findViewById(R.id.player_root)
        root.setBackgroundColor(navy)
        playerView = findViewById(R.id.player_view)
        status = findViewById(R.id.player_status)
        statusText = findViewById(R.id.player_status_text)
        controls = findViewById(R.id.player_controls)
        titleView = findViewById(R.id.player_title)
        seekRow = findViewById(R.id.seek_row)
        timePos = findViewById(R.id.time_pos)
        timeDur = findViewById(R.id.time_dur)
        seekBar = findViewById(R.id.seek_bar)
        actionsRow = findViewById(R.id.player_actions)
        menuScroll = findViewById(R.id.player_menu_scroll)
        menuList = findViewById(R.id.player_menu)
        hintView = findViewById(R.id.player_hint)
        titleView.text = intent.getStringExtra("title") ?: "Reelix"
        startMs = intent.getLongExtra("startMs", 0L)
        handoff = intent.getStringExtra("handoff").orEmpty()
        preferredLang = AudioLabels.languageCode(intent.getStringExtra("audioLang"))
        wireControls()
        main.post(ticker)
        val direct = intent.getStringExtra("directUrl")
        if (!direct.isNullOrBlank()) {
            val headers = linkedMapOf("User-Agent" to StreambertEngine.UA, "Referer" to direct)
            deliver(StreamResolver.Hit(direct, headers, direct, emptyList()), "direct", "Direct", -1)
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

    private fun wireControls() {
        styleSeek(false)
        seekRow.setOnFocusChangeListener { _, focused -> styleSeek(focused) }
        seekRow.setOnClickListener { seekBy(10_000) }
        actionsRow.removeAllViews()
        playButton = pill("Play") { toggle() }
        volumeButton = pill("Volume") { openMenu("volume", volumeButton) }
        actionsRow.addView(playButton)
        actionsRow.addView(pill("-10s") { seekBy(-10_000); poke() })
        actionsRow.addView(pill("+10s") { seekBy(10_000); poke() })
        actionsRow.addView(volumeButton)
        actionsRow.addView(pill("Subtitles") { openMenu("subs", it) })
        actionsRow.addView(pill("Audio") { openMenu("audio", it) })
        actionsRow.addView(pill("Speed") { openMenu("speed", it) })
        actionsRow.addView(pill("Zoom") { openMenu("aspect", it) })
        actionsRow.addView(pill("Quality") { openMenu("quality", it) })
        actionsRow.addView(pill("Source") { openMenu("source", it) })
        actionsRow.addView(pill("Next") { playNext() })
        actionsRow.addView(pill("External") { openExternal() })
    }

    private fun pill(label: String, onClick: (View) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 18f
            setPadding(28, 16, 28, 16)
            isFocusable = true
            isClickable = true
            isSingleLine = true
            setOnClickListener { onClick(this) }
            setOnFocusChangeListener { v, focused -> stylePill(v as TextView, focused) }
            stylePill(this, false)
            val pad = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            pad.marginEnd = 12
            layoutParams = pad
        }
    }

    private fun stylePill(view: TextView, focused: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = 18f
        bg.setColor(if (focused) Color.parseColor("#F4F5F7") else Color.parseColor("#22FFFFFF"))
        view.background = bg
        view.setTextColor(if (focused) Color.parseColor("#111318") else Color.parseColor("#F4F2FB"))
    }

    private fun styleSeek(focused: Boolean) {
        seekRow.setBackgroundColor(if (focused) Color.parseColor("#33E23CA8") else Color.TRANSPARENT)
    }

    private fun tryNext() {
        releasePlayer()
        audioLocked = false
        if (index >= sources.length()) {
            showStatus("None of the sources started. Try another title, or check the network.")
            return
        }
        val item = sources.optJSONObject(index) ?: run {
            index += 1
            tryNext()
            return
        }
        val sourceIndex = index
        index += 1
        sourceId = item.optString("id")
        sourceLabel = item.optString("label").ifBlank { sourceId }
        val url = item.optString("url")
        if (url.isBlank()) {
            tryNext()
            return
        }
        Log.i(TAG, "open $sourceLabel $url")
        ready = false
        showStatus("Opening $sourceLabel…")
        resolver?.resolve(root, url) { hit ->
            if (isFinishing) return@resolve
            if (hit == null) {
                showStatus("$sourceLabel had no stream. Trying the next source…")
                main.postDelayed({ if (!isFinishing) tryNext() }, 400)
            } else {
                deliver(hit, sourceId, sourceLabel, sourceIndex)
            }
        }
    }

    private fun switchTo(sourceIndex: Int) {
        if (sourceIndex < 0 || sourceIndex >= sources.length()) return
        val pos = player?.currentPosition ?: startMs
        if (pos > 0) startMs = pos
        resolver?.stop()
        resolver = StreamResolver(this)
        index = sourceIndex
        ready = false
        closeMenu()
        tryNext()
    }

    private fun deliver(hit: StreamResolver.Hit, id: String, label: String, sourceIndex: Int) {
        if (isFinishing) return
        currentHit = hit
        sourceId = id
        sourceLabel = label
        playingIndex = sourceIndex
        if (handoff == "external") {
            val title = intent.getStringExtra("title") ?: label
            val opened = ExternalPlayer.open(this, hit.url, title, hit.headers, hit.subtitles, startMs)
            if (opened.ok) {
                ready = true
                report()
                finish()
            } else {
                showStatus(opened.detail)
            }
            return
        }
        play(hit, label)
    }

    private fun play(hit: StreamResolver.Hit, label: String) {
        showStatus("Starting $label…")
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(hit.headers["User-Agent"] ?: StreambertEngine.UA)
            .setDefaultRequestProperties(hit.headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val exo = newPlayer(http)
        val builder = MediaItem.Builder().setUri(hit.url)
        val configs = ArrayList<MediaItem.SubtitleConfiguration>()
        hit.subtitles.take(8).forEachIndexed { i, url ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEachIndexed
            val mime = if (url.contains(".srt", ignoreCase = true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            configs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                    .setMimeType(mime)
                    .setLanguage("und")
                    .setLabel("External ${i + 1}")
                    .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                    .build(),
            )
        }
        if (configs.isNotEmpty()) builder.setSubtitleConfigurations(configs)
        exo.setMediaItem(builder.build(), startMs.coerceAtLeast(0L))
        exo.playWhenReady = true
        configureAudio(exo)
        exo.setPlaybackParameters(PlaybackParameters(speed))
        player = exo
        playerView.player = exo
        applySubtitles()
        applyAspect()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready = true
                    status.visibility = View.GONE
                    ensureAudible()
                    refreshChrome()
                    if (!overlay) showOverlay()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                ensureAudio(tracks)
                if (menuOpen) renderMenu(menuAnchor)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player ${error.errorCodeName} ${error.message}")
                if (!ready) {
                    showStatus("$label did not start. Trying the next source…")
                    main.post { tryNext() }
                } else {
                    showStatus("Playback stopped (${error.errorCodeName}).")
                }
            }
        })
        exo.prepare()
    }

    private fun newPlayer(http: DefaultHttpDataSource.Factory): ExoPlayer {
        fun build(factory: DefaultRenderersFactory): ExoPlayer {
            return ExoPlayer.Builder(this)
                .setRenderersFactory(factory)
                .setTrackSelector(DefaultTrackSelector(this))
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .build()
        }
        return try {
            val factory = ReelixRenderersFactory(this).apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }
            build(factory)
        } catch (error: Throwable) {
            Log.w(TAG, "ffmpeg player ${error.javaClass.simpleName} ${error.message}")
            build(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
        }
    }

    private fun selection(exo: ExoPlayer): DefaultTrackSelector.Parameters.Builder {
        val current = exo.trackSelectionParameters
        return if (current is DefaultTrackSelector.Parameters) current.buildUpon()
        else DefaultTrackSelector.Parameters.Builder(this)
    }

    private fun configureAudio(exo: ExoPlayer) {
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        exo.volume = 1f
        val selection = selection(exo)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setMaxAudioChannelCount(2)
            .setPreferredAudioMimeTypes(
                MimeTypes.AUDIO_AAC,
                MimeTypes.AUDIO_MPEG,
                MimeTypes.AUDIO_OPUS,
                MimeTypes.AUDIO_VORBIS,
                MimeTypes.AUDIO_FLAC,
                MimeTypes.AUDIO_AC3,
                MimeTypes.AUDIO_E_AC3,
                MimeTypes.AUDIO_DTS,
            )
            .setTunnelingEnabled(false)
            .setAudioOffloadPreferences(
                androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                    )
                    .build(),
            )
        if (preferredLang.isNotBlank()) selection.setPreferredAudioLanguage(preferredLang)
        exo.trackSelectionParameters = selection.build()
        ensureAudible()
    }

    private fun ensureAudible() {
        player?.volume = 1f
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max > 0 && manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.4f).toInt().coerceAtLeast(1), 0)
        }
    }

    private fun ensureAudio(tracks: Tracks) {
        if (audioLocked) return
        val exo = player ?: return
        val flat = ArrayList<AudioRef>()
        val policy = ArrayList<AudioPolicy.Track>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                flat.add(AudioRef(group, i))
                policy.add(
                    AudioPolicy.Track(
                        format.sampleMimeType,
                        format.channelCount,
                        format.language,
                        group.isTrackSupported(i),
                    ),
                )
            }
        }
        if (flat.isEmpty()) return
        val pick = AudioPolicy.index(policy, preferredLang)
        if (pick < 0) return
        val chosen = flat[pick]
        val format = chosen.group.getTrackFormat(chosen.index)
        audioLocked = true
        if (!chosen.group.isTrackSelected(chosen.index)) {
            exo.trackSelectionParameters = selection(exo)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(chosen.group.mediaTrackGroup, chosen.index))
                .build()
        }
        exo.volume = 1f
        Log.i(TAG, "audio selected ${format.sampleMimeType} ch=${format.channelCount} lang=${format.language}")
    }

    private fun applySubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = selection(exo)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
            .build()
        val view = playerView.subtitleView ?: return
        val scale = when (subSize) {
            0 -> 0.7f
            2 -> 1.45f
            else -> 1f
        }
        view.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
        view.setStyle(captionStyle())
    }

    private fun captionStyle(): CaptionStyleCompat {
        val white = Color.WHITE
        val yellow = Color.parseColor("#FFE14A")
        return when (subStyle) {
            1 -> CaptionStyleCompat(yellow, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT_BOLD)
            2 -> CaptionStyleCompat(white, Color.parseColor("#CC000000"), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, Typeface.DEFAULT)
            else -> CaptionStyleCompat(white, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT)
        }
    }

    private fun applyAspect() {
        playerView.resizeMode = when (aspect) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
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

    private fun showOverlay() {
        overlay = true
        controls.visibility = View.VISIBLE
        refreshChrome()
        if (currentFocus == null || currentFocus === root) playButton.requestFocus()
        poke()
    }

    private fun hideOverlay() {
        overlay = false
        closeMenu()
        hideControls?.let { main.removeCallbacks(it) }
        controls.visibility = View.GONE
    }

    private fun poke() {
        hideControls?.let { main.removeCallbacks(it) }
        if (!overlay || menuOpen) return
        val task = Runnable { hideOverlay() }
        hideControls = task
        main.postDelayed(task, 7000)
    }

    private fun closeMenu() {
        menuOpen = false
        menuAnchor = null
        menuScroll.visibility = View.GONE
        menuList.removeAllViews()
        hintView.text = "Left and right move    OK selects    Back closes"
    }

    private fun openMenu(kind: String, anchor: View) {
        menuAnchor = anchor
        menuOpen = true
        renderMenu(anchor)
        poke()
    }

    private fun renderMenu(anchor: View?) {
        val kind = when (anchor) {
            volumeButton -> "volume"
            else -> menuKind(anchor)
        }
        val choices = choicesFor(kind)
        menuScroll.visibility = View.VISIBLE
        menuList.removeAllViews()
        hintView.text = "Up and down move    OK applies    Back closes the list"
        choices.forEachIndexed { i, choice ->
            val row = TextView(this).apply {
                text = (if (choice.selected) "✓  " else "    ") + choice.label
                textSize = 20f
                setPadding(24, 16, 24, 16)
                isFocusable = true
                isClickable = true
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { choice.apply() }
                setOnFocusChangeListener { v, focused ->
                    v.setBackgroundColor(if (focused) Color.parseColor("#F4F5F7") else Color.TRANSPARENT)
                    (v as TextView).setTextColor(if (focused) Color.parseColor("#111318") else Color.parseColor("#F4F2FB"))
                    val child = menuList.indexOfChild(v)
                    if (child >= 0) menuScroll.smoothScrollTo(0, v.top)
                }
            }
            menuList.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            if (choice.selected || (choices.none { it.selected } && i == 0)) {
                row.post { row.requestFocus() }
            }
        }
    }

    private fun menuKind(anchor: View?): String {
        val label = (anchor as? TextView)?.text?.toString()?.substringBefore(" ") ?: ""
        return when {
            label.startsWith("Subtitle") -> "subs"
            label.startsWith("Audio") -> "audio"
            label.startsWith("Speed") -> "speed"
            label.startsWith("Zoom") -> "aspect"
            label.startsWith("Quality") -> "quality"
            label.startsWith("Source") -> "source"
            label.startsWith("Volume") -> "volume"
            else -> "subs"
        }
    }

    private fun refreshChrome() {
        val exo = player
        val pos = exo?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val dur = exo?.duration?.takeIf { it > 0 } ?: 0L
        timePos.text = format(pos)
        timeDur.text = if (dur > 0) format(dur) else "--:--"
        seekBar.progress = if (dur > 0) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
        playButton.text = if (exo?.isPlaying == true) "Pause" else "Play"
        volumeButton.text = volumeLabel()
        titleView.text = intent.getStringExtra("title")?.ifBlank { sourceLabel } ?: sourceLabel
    }

    private fun volumeLabel(): String {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Volume ${cur * 100 / max}%"
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
        refreshChrome()
        poke()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration
        var next = exo.currentPosition + deltaMs
        if (next < 0) next = 0
        if (dur > 0 && next > dur) next = dur
        exo.seekTo(next)
        refreshChrome()
    }

    private fun nudgeVolume(up: Boolean) {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        manager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
        player?.volume = 1f
        refreshChrome()
    }

    private fun playNext() {
        if (intent.getBooleanExtra("hasNext", false)) {
            nextRequested = true
            ready = true
            close()
        } else {
            showStatus("No next episode for this title.")
            main.postDelayed({ if (ready) status.visibility = View.GONE }, 1400)
        }
    }

    private fun openExternal() {
        val hit = currentHit
        if (hit == null) {
            showStatus("The stream is not ready yet.")
            return
        }
        player?.pause()
        val title = intent.getStringExtra("title") ?: sourceLabel
        val pos = player?.currentPosition ?: 0L
        val opened = ExternalPlayer.open(this, hit.url, title, hit.headers, hit.subtitles, pos)
        if (!opened.ok) showStatus(opened.detail)
        refreshChrome()
    }

    private fun choicesFor(kind: String): List<Choice> {
        return when (kind) {
            "subs" -> subtitleChoices()
            "audio" -> audioChoices()
            "source" -> sourceChoices()
            "quality" -> qualityChoices()
            "speed" -> speedChoices()
            "aspect" -> aspectChoices()
            "volume" -> volumeChoices()
            else -> emptyList()
        }
    }

    private fun subtitleChoices(): List<Choice> {
        val exo = player ?: return listOf(Choice("No subtitle tracks", false) { closeMenu() })
        val out = ArrayList<Choice>()
        val textDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        out.add(Choice("Off", textDisabled || !subsOn) {
            subsOn = false
            applySubtitles()
            closeMenu()
        })
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val name = format.label?.takeIf { it.isNotBlank() } ?: AudioLabels.languageName(format.language)
                val selected = group.isTrackSelected(i) && subsOn && !textDisabled
                out.add(Choice(name, selected) {
                    subsOn = true
                    exo.trackSelectionParameters = selection(exo)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    applySubtitles()
                    closeMenu()
                })
            }
        }
        out.add(Choice("Size · Small", subSize == 0) { subSize = 0; applySubtitles(); closeMenu() })
        out.add(Choice("Size · Medium", subSize == 1) { subSize = 1; applySubtitles(); closeMenu() })
        out.add(Choice("Size · Large", subSize == 2) { subSize = 2; applySubtitles(); closeMenu() })
        out.add(Choice("Style · White", subStyle == 0) { subStyle = 0; applySubtitles(); closeMenu() })
        out.add(Choice("Style · Yellow", subStyle == 1) { subStyle = 1; applySubtitles(); closeMenu() })
        out.add(Choice("Style · Boxed", subStyle == 2) { subStyle = 2; applySubtitles(); closeMenu() })
        return out
    }

    private fun audioChoices(): List<Choice> {
        val out = ArrayList<Choice>()
        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val variant = item.optString("variant")
            if (variant != "sub" && variant != "dub") continue
            val label = item.optString("label").ifBlank { if (variant == "dub") "Dubbed" else "Subbed" }
            out.add(Choice(label, i == playingIndex) {
                closeMenu()
                if (i != playingIndex) switchTo(i)
            })
        }
        val exo = player
        if (exo == null) {
            if (out.isEmpty()) out.add(Choice("No audio tracks", false) { closeMenu() })
            return out
        }
        var any = false
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = AudioLabels.track(format.language, format.channelCount, format.sampleMimeType)
                any = true
                out.add(Choice(label, group.isTrackSelected(i)) {
                    selectAudio(group, i, format.language)
                    closeMenu()
                })
            }
        }
        if (!any && out.isEmpty()) out.add(Choice("No audio tracks", false) { closeMenu() })
        return out
    }

    private fun selectAudio(group: Tracks.Group, trackIndex: Int, language: String?) {
        val exo = player ?: return
        exo.trackSelectionParameters = selection(exo)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        val code = AudioLabels.languageCode(language)
        if (code.isNotBlank()) chosenLang = code
        audioLocked = true
        exo.volume = 1f
        val format = group.getTrackFormat(trackIndex)
        Log.i(TAG, "audio pick ${format.sampleMimeType} ch=${format.channelCount} lang=$code")
    }

    private fun sourceChoices(): List<Choice> {
        if (sources.length() == 0) return listOf(Choice("No other sources", false) { closeMenu() })
        val out = ArrayList<Choice>()
        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val label = item.optString("label").ifBlank { item.optString("id") }
            out.add(Choice(label, i == playingIndex) {
                closeMenu()
                if (i != playingIndex) switchTo(i)
            })
        }
        return out
    }

    private fun qualityChoices(): List<Choice> {
        val exo = player ?: return listOf(Choice("Auto", true) { closeMenu() })
        val out = ArrayList<Choice>()
        val overridden = exo.trackSelectionParameters.overrides.values.any { it.mediaTrackGroup.type == C.TRACK_TYPE_VIDEO }
        out.add(Choice("Auto", !overridden) {
            exo.trackSelectionParameters = selection(exo)
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
            closeMenu()
        })
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (format.height <= 0) continue
                val label = if (format.bitrate > 0) "${format.height}p · ${format.bitrate / 1000} kbps" else "${format.height}p"
                out.add(Choice(label, group.isTrackSelected(i)) {
                    exo.trackSelectionParameters = selection(exo)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    closeMenu()
                })
            }
        }
        if (out.size == 1) out.add(Choice("No quality choices", false) { closeMenu() })
        return out
    }

    private fun speedChoices(): List<Choice> {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        return speeds.map { value ->
            val label = if (value == 1f) "Normal" else "${value}×"
            Choice(label, speed == value) {
                speed = value
                player?.setPlaybackParameters(PlaybackParameters(value))
                closeMenu()
            }
        }
    }

    private fun aspectChoices(): List<Choice> {
        val labels = arrayOf("Fit", "Fill", "Zoom", "16:9")
        return labels.mapIndexed { i, label ->
            Choice(label, aspect == i) {
                aspect = i
                applyAspect()
                closeMenu()
            }
        }
    }

    private fun volumeChoices(): List<Choice> {
        return listOf(
            Choice("Louder", false) { nudgeVolume(true) },
            Choice("Quieter", false) { nudgeVolume(false) },
            Choice("Player volume full", player?.volume == 1f) {
                player?.volume = 1f
                closeMenu()
            },
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val back = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (back && event.action != KeyEvent.ACTION_DOWN) return true
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event.keyCode)) return true
        if (overlay && event.action == KeyEvent.ACTION_DOWN) poke()
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(code: Int): Boolean {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (menuOpen) {
                val back = menuAnchor
                closeMenu()
                back?.requestFocus()
                return true
            }
            if (overlay) {
                hideOverlay()
                return true
            }
            close()
            return true
        }
        if (!overlay) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    seekBy(-10_000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(10_000)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNext()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> {
                    showOverlay()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    toggle()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause()
                    return true
                }
            }
            return false
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK) {
            toggle()
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY) {
            player?.play()
            refreshChrome()
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            player?.pause()
            refreshChrome()
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            seekBy(-10_000)
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            seekBy(10_000)
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT) {
            playNext()
            return true
        }
        val seekFocused = currentFocus?.id == R.id.seek_row
        if (seekFocused && code == KeyEvent.KEYCODE_DPAD_LEFT) {
            seekBy(-10_000)
            return true
        }
        if (seekFocused && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            seekBy(10_000)
            return true
        }
        return false
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
            .put("ok", ready || nextRequested)
            .put("positionMs", exo?.currentPosition ?: 0L)
            .put("durationMs", exo?.duration ?: 0L)
            .put("sourceId", sourceId)
            .put("ended", exo?.playbackState == Player.STATE_ENDED)
        if (chosenLang.isNotBlank()) result.put("audioLang", chosenLang)
        if (nextRequested) result.put("next", true)
        if (!ready && !nextRequested) result.put("error", statusText.text?.toString() ?: "Playback did not start")
        StreambertEngine.finishPlayback(result)
    }

    override fun onStart() {
        super.onStart()
        if (handoff != "external") player?.play()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        hideControls?.let { main.removeCallbacks(it) }
        report()
        resolver?.stop()
        resolver = null
        releasePlayer()
        super.onDestroy()
    }

    private data class Choice(val label: String, val selected: Boolean, val apply: () -> Unit)
    private data class AudioRef(val group: Tracks.Group, val index: Int)

    companion object {
        private const val TAG = "Emberlane"
    }
}
