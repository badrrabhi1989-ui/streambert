package com.truelockmc.streambert

import android.app.Activity
import android.graphics.Color
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-screen TV player. Provider pages are resolved to an HLS/MP4/DASH URL,
 * then Media3 plays that URL. OK or Down opens the remote overlay.
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
    private var audioEnsured = false
    private var currentHit: StreamResolver.Hit? = null
    private var overlay = false
    private var menuOpen = false
    private var actionFocus = 0
    private var menuFocus = 0
    private var menu = emptyList<Choice>()
    private var hideControls: Runnable? = null
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var controls: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var actionsRow: LinearLayout
    private lateinit var menuScroll: ScrollView
    private lateinit var menuList: LinearLayout
    private lateinit var hintView: TextView

    private val ticker = object : Runnable {
        override fun run() {
            if (overlay) updateTime()
            main.postDelayed(this, 1000)
        }
    }

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
        actionsRow = findViewById(R.id.player_actions)
        menuScroll = findViewById(R.id.player_menu_scroll)
        menuList = findViewById(R.id.player_menu)
        hintView = findViewById(R.id.player_hint)
        titleView.text = intent.getStringExtra("title") ?: "Reelix"
        startMs = intent.getLongExtra("startMs", 0L)
        handoff = intent.getStringExtra("handoff").orEmpty()
        preferredLang = AudioLabels.languageCode(intent.getStringExtra("audioLang"))
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

    private fun tryNext() {
        releasePlayer()
        audioEnsured = false
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
        menuOpen = false
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
            val opened = ExternalPlayer.open(this, hit.url, title, hit.headers, hit.subtitles, 0L)
            if (opened) {
                ready = true
                report()
                finish()
            } else {
                showStatus("No external player found. Install VLC, MX Player, or Just Player.")
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
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready = true
                    status.visibility = View.GONE
                    updateTime()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                ensureAudio(tracks)
                if (menuOpen) render()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player ${error.errorCodeName} ${error.message}")
                if (!ready) {
                    showStatus("$label failed. Trying the next source…")
                    main.post { tryNext() }
                } else {
                    showStatus(error.errorCodeName)
                }
            }
        })
        exo.prepare()
        applySubtitles()
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
            val factory = NextRenderersFactory(this).apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                setMediaCodecSelector(object : MediaCodecSelector {
                    override fun getDecoderInfos(
                        mimeType: String,
                        requiresSecureDecoder: Boolean,
                        requiresTunnelingDecoder: Boolean,
                    ): List<MediaCodecInfo> {
                        if (mimeType in SURROUND) return emptyList()
                        return MediaCodecSelector.DEFAULT.getDecoderInfos(
                            mimeType,
                            requiresSecureDecoder,
                            requiresTunnelingDecoder,
                        )
                    }
                })
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
            .setPreferredAudioMimeTypes(
                MimeTypes.AUDIO_AAC,
                MimeTypes.AUDIO_MPEG,
                MimeTypes.AUDIO_OPUS,
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
    }

    private fun ensureAudio(tracks: Tracks) {
        if (audioEnsured) return
        val exo = player ?: return
        val selected = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        if (selected != null) {
            audioEnsured = true
            logAudio(selected)
            return
        }
        val pick = bestAudio(tracks) ?: return
        audioEnsured = true
        exo.trackSelectionParameters = selection(exo)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(pick.group.mediaTrackGroup, pick.index))
            .build()
        val format = pick.group.getTrackFormat(pick.index)
        Log.i(TAG, "audio ${format.sampleMimeType} ch=${format.channelCount} lang=${format.language}")
    }

    private fun logAudio(group: Tracks.Group) {
        for (i in 0 until group.length) {
            if (!group.isTrackSelected(i)) continue
            val format = group.getTrackFormat(i)
            Log.i(TAG, "audio ${format.sampleMimeType} ch=${format.channelCount} lang=${format.language}")
        }
    }

    private fun bestAudio(tracks: Tracks): AudioPick? {
        var best: AudioPick? = null
        var bestLang = Int.MAX_VALUE
        var bestMime = Int.MAX_VALUE
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val langScore = when {
                    preferredLang.isNotBlank() && AudioLabels.languageCode(format.language) == preferredLang -> 0
                    preferredLang.isBlank() -> 1
                    else -> 2
                }
                val mimeScore = mimeRank(format.sampleMimeType)
                if (best == null || langScore < bestLang || (langScore == bestLang && mimeScore < bestMime)) {
                    best = AudioPick(group, i)
                    bestLang = langScore
                    bestMime = mimeScore
                }
            }
        }
        return best
    }

    private fun mimeRank(mime: String?): Int = when (mime) {
        MimeTypes.AUDIO_AAC -> 0
        MimeTypes.AUDIO_MPEG -> 1
        MimeTypes.AUDIO_OPUS -> 2
        MimeTypes.AUDIO_AC3 -> 3
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> 4
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> 5
        else -> 6
    }

    private fun applySubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = selection(exo)
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

    private fun showOverlay() {
        overlay = true
        controls.visibility = View.VISIBLE
        updateTime()
        render()
        poke()
    }

    private fun hideOverlay() {
        overlay = false
        menuOpen = false
        menu = emptyList()
        hideControls?.let { main.removeCallbacks(it) }
        controls.visibility = View.GONE
    }

    private fun poke() {
        hideControls?.let { main.removeCallbacks(it) }
        if (!overlay || menuOpen) return
        val task = Runnable { hideOverlay() }
        hideControls = task
        main.postDelayed(task, 6000)
    }

    private fun closeMenu() {
        menuOpen = false
        menu = emptyList()
        render()
        poke()
    }

    private fun render() {
        actionsRow.removeAllViews()
        ACTION_IDS.forEachIndexed { i, id ->
            val label = when (id) {
                "play" -> if (player?.isPlaying == true) "Pause" else "Play"
                "subs" -> "Subtitles"
                "audio" -> "Audio"
                "source" -> "Source"
                "quality" -> "Quality"
                else -> "External"
            }
            val focused = overlay && !menuOpen && i == actionFocus
            actionsRow.addView(actionLabel(label, focused))
        }
        if (!menuOpen) {
            menuScroll.visibility = View.GONE
            hintView.text = "OK select    Left / Right move    Down opens the list    Back closes"
            return
        }
        menuScroll.visibility = View.VISIBLE
        menuList.removeAllViews()
        hintView.text = "Up / Down move    OK applies    Back closes the list"
        menu.forEachIndexed { i, choice ->
            val prefix = if (choice.selected) "✓  " else "    "
            val view = TextView(this).apply {
                text = prefix + choice.label
                textSize = 18f
                setTextColor(Color.parseColor("#F4F2FB"))
                setPadding(24, 16, 24, 16)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                isFocusable = false
                setBackgroundColor(if (i == menuFocus) Color.parseColor("#33E23CA8") else Color.TRANSPARENT)
            }
            menuList.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        menuList.post {
            val child = menuList.getChildAt(menuFocus) ?: return@post
            menuScroll.smoothScrollTo(0, child.top)
        }
    }

    private fun actionLabel(label: String, focused: Boolean): TextView {
        return TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(if (focused) Color.parseColor("#111318") else Color.parseColor("#F4F2FB"))
            setPadding(28, 16, 28, 16)
            isSingleLine = true
            isFocusable = false
            setBackgroundColor(if (focused) Color.parseColor("#F4F5F7") else Color.TRANSPARENT)
            val pad = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            pad.marginEnd = 12
            layoutParams = pad
        }
    }

    private fun updateTime() {
        val exo = player
        val pos = exo?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val dur = exo?.duration?.coerceAtLeast(0L) ?: 0L
        timeView.text = "${format(pos)}  /  ${if (dur > 0) format(dur) else "--:--"}   ·   $sourceLabel"
        val play = actionsRow.getChildAt(0) as? TextView
        if (play != null && !menuOpen) play.text = if (exo?.isPlaying == true) "Pause" else "Play"
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
        if (overlay) {
            render()
            poke()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration
        var next = exo.currentPosition + deltaMs
        if (next < 0) next = 0
        if (dur > 0 && next > dur) next = dur
        exo.seekTo(next)
    }

    private fun activateAction() {
        when (ACTION_IDS[actionFocus]) {
            "play" -> toggle()
            "external" -> openExternal()
            else -> openMenu(ACTION_IDS[actionFocus])
        }
    }

    private fun onDown() {
        val id = ACTION_IDS[actionFocus]
        if (id == "subs" || id == "audio" || id == "source" || id == "quality") openMenu(id)
    }

    private fun openMenu(kind: String) {
        menu = when (kind) {
            "subs" -> subtitleChoices()
            "audio" -> audioChoices()
            "source" -> sourceChoices()
            "quality" -> qualityChoices()
            else -> emptyList()
        }
        val current = menu.indexOfFirst { it.selected }
        menuFocus = if (current < 0) 0 else current
        menuOpen = true
        render()
        poke()
    }

    private fun openExternal() {
        val hit = currentHit
        if (hit == null) {
            timeView.text = "The stream is not ready yet"
            return
        }
        player?.pause()
        val title = intent.getStringExtra("title") ?: sourceLabel
        val pos = player?.currentPosition ?: 0L
        val opened = ExternalPlayer.open(this, hit.url, title, hit.headers, hit.subtitles, pos)
        if (!opened) timeView.text = "No external player found. Install VLC, MX Player, or Just Player."
        render()
    }

    private fun subtitleChoices(): List<Choice> {
        val exo = player ?: return listOf(Choice("No subtitle tracks", false) { closeMenu() })
        val out = ArrayList<Choice>()
        val textDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        out.add(Choice("Off", textDisabled || !subsOn) {
            subsOn = false
            exo.trackSelectionParameters = selection(exo)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
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
                    closeMenu()
                })
            }
        }
        if (out.size == 1) out.add(Choice("No subtitle tracks", false) { closeMenu() })
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
        audioEnsured = true
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (handleKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(code: Int): Boolean {
        if (menuOpen) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    menuFocus = (menuFocus - 1).coerceAtLeast(0)
                    render()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    menuFocus = (menuFocus + 1).coerceAtMost(menu.lastIndex.coerceAtLeast(0))
                    render()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    menu.getOrNull(menuFocus)?.apply?.invoke()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    closeMenu()
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
        if (overlay) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    actionFocus = (actionFocus - 1 + ACTION_IDS.size) % ACTION_IDS.size
                    render()
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    actionFocus = (actionFocus + 1) % ACTION_IDS.size
                    render()
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onDown()
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    activateAction()
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    toggle()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.play()
                    render()
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause()
                    render()
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    seekBy(-10_000)
                    updateTime()
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    seekBy(if (code == KeyEvent.KEYCODE_MEDIA_NEXT) 30_000 else 10_000)
                    updateTime()
                    poke()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    hideOverlay()
                    return true
                }
            }
            return false
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_DOWN -> {
                actionFocus = 0
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
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> return true
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_BOOKMARK -> {
                subsOn = !subsOn
                applySubtitles()
                showStatus(if (subsOn) "Subtitles on" else "Subtitles off")
                main.postDelayed({ if (ready) status.visibility = View.GONE }, 900)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                close()
                return true
            }
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
            .put("ok", ready)
            .put("positionMs", exo?.currentPosition ?: 0L)
            .put("durationMs", exo?.duration ?: 0L)
            .put("sourceId", sourceId)
            .put("ended", exo?.playbackState == Player.STATE_ENDED)
        if (chosenLang.isNotBlank()) result.put("audioLang", chosenLang)
        if (!ready) result.put("error", statusText.text?.toString() ?: "Playback did not start")
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
    private data class AudioPick(val group: Tracks.Group, val index: Int)

    companion object {
        private const val TAG = "Emberlane"
        private val ACTION_IDS = arrayOf("play", "subs", "audio", "source", "quality", "external")
        private val SURROUND = setOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_TRUEHD,
        )
    }
}
