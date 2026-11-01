package com.truelockmc.streambert

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.ArrayList

/**
 * Video stays on the platform decoder. Audio prefers the FFmpeg renderer so
 * AC3, E-AC3, and DTS are decoded to PCM instead of silent passthrough.
 */
@UnstableApi
class ReelixRenderersFactory(context: Context) : NextRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val platform = object : MediaCodecSelector {
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean,
            ): List<MediaCodecInfo> {
                if (mimeType in SURROUND) return emptyList()
                return mediaCodecSelector.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
            }
        }
        super.buildAudioRenderers(
            context,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            platform,
            true,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }

    companion object {
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
