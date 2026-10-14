package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.exoplayer2.SimpleExoPlayer
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.playlist.SourceQualityResolver

@OptIn(UnstableApi::class)
class PlayerFragment : Fragment(), SurfaceHolder.Callback {
    private var binding: PlayerBinding? = null
    private var playerView: PlayerView? = null
    private var tvViewModel: TVViewModel? = null
    private var activeSource = 0
    private var playbackStartedAt = 0L
    private var recordedFirstFrame = false
    private lateinit var surfaceView: SurfaceView
    private var exoPlayer: SimpleExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastPosition = -1L
    private var lastProgressAt = 0L
    private var monitoring = false
    private var countedStall = false
    private val recentStalls = ArrayDeque<Long>()
    private val stallMonitor = object : Runnable {
        override fun run() {
            if (!isResumed) return
            val player = playerView?.player
            val shouldPlay = player?.playWhenReady ?: exoPlayer?.playWhenReady ?: false
            val position = player?.currentPosition ?: exoPlayer?.currentPosition ?: 0L
            val now = SystemClock.elapsedRealtime()
            if (monitoring && shouldPlay) {
                if (position != lastPosition) {
                    lastPosition = position
                    lastProgressAt = now
                    countedStall = false
                } else {
                    val stalledFor = now - lastProgressAt
                    while (recentStalls.isNotEmpty() && now - recentStalls.first() > 60_000) {
                        recentStalls.removeFirst()
                    }
                    if (stalledFor >= 3_000 && !countedStall) {
                        recentStalls.addLast(now)
                        countedStall = true
                    }
                    if (stalledFor >= 12_000 || recentStalls.size >= 3) {
                        monitoring = false
                        tvViewModel?.takeIf { it.videoIndex.value == activeSource }?.let {
                            (activity as? MainActivity)?.onPlaybackFailed(it, "直播持续卡顿")
                        }
                    }
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun resetStallMonitor() {
        monitoring = false
        lastPosition = -1L
        lastProgressAt = SystemClock.elapsedRealtime()
        recentStalls.clear()
        countedStall = false
    }

    private fun markPlaying() {
        if (!monitoring) {
            monitoring = true
            lastProgressAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val views = PlayerBinding.inflate(inflater, container, false)
        binding = views
        if (Utils.isTmallDevice()) {
            views.playerView.visibility = View.GONE
            views.standbyPlayerView.visibility = View.GONE
            surfaceView = views.surfaceView
            surfaceView.holder.addCallback(this)
        } else {
            views.surfaceView.visibility = View.GONE
            views.standbyPlayerView.visibility = View.GONE
            playerView = views.playerView
            playerView?.setKeepContentOnPlayerReset(true)
            val player = newPlayer()
            playerView?.player = player
            player.addListener(foregroundListener(player))
            player.playWhenReady = true
        }
        (activity as? MainActivity)?.fragmentReady("PlayerFragment")
        return views.root
    }

    private fun newPlayer(): ExoPlayer {
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(requireContext())
        selector.setParameters(selector.buildUponParameters()
            .setExceedRendererCapabilitiesIfNecessary(false)
            .setExceedVideoConstraintsIfNecessary(false)
            .setForceHighestSupportedBitrate(true)
            .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, true))
        return ExoPlayer.Builder(requireContext()).setTrackSelector(selector).build()
    }

    private fun recordPlayingSource() {
        if (recordedFirstFrame) return
        val channel = tvViewModel ?: return
        if (channel.videoIndex.value != activeSource) return
        val url = channel.videoUrl.value?.getOrNull(activeSource) ?: return
        val measured = playbackQuality()
        if (measured.width <= 0 || measured.height <= 0) return
        context?.let {
            recordedFirstFrame = true
            SourceQualityResolver.markPlaying(it, url, measured.copy(
                loadMs = (SystemClock.elapsedRealtime() - playbackStartedAt).coerceAtLeast(0)))
        }
    }

    private fun foregroundListener(player: ExoPlayer) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && playerView?.player === player && player.playWhenReady) {
                tvViewModel?.takeIf { it.videoIndex.value == activeSource }?.let {
                    (activity as? MainActivity)?.onPlaybackFailed(it, "直播源已结束")
                }
            }
        }

        override fun onRenderedFirstFrame() {
            if (playerView?.player === player) recordPlayingSource()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (playerView?.player === player) {
                Log.i(TAG, "video size: ${videoSize.width}x${videoSize.height}")
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (playerView?.player !== player) return
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (video.isNotEmpty() && video.none { group ->
                    (0 until group.length).any { group.isTrackSelected(it) &&
                        group.getTrackSupport(it) == C.FORMAT_HANDLED }
                }) {
                tvViewModel?.takeIf { it.videoIndex.value == activeSource }?.let {
                    (activity as? MainActivity)?.onPlaybackFailed(it, "设备不支持该源的视频解码")
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (playerView?.player !== player) return
            Log.e(TAG, "PlaybackException $error")
            tvViewModel?.takeIf { it.videoIndex.value == activeSource }?.let {
                (activity as? MainActivity)?.onPlaybackFailed(it, error.message ?: "播放失败")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && playerView?.player === player) {
                markPlaying()
                tvViewModel?.takeIf { it.videoIndex.value == activeSource }?.let {
                    (activity as? MainActivity)?.onPlaybackStarted(it)
                }
            }
        }
    }

    fun play(channel: TVViewModel) {
        tvViewModel = channel
        activeSource = channel.videoIndex.value ?: 0
        resetStallMonitor()
        playbackStartedAt = SystemClock.elapsedRealtime()
        recordedFirstFrame = false
        try {
            playerView?.player?.run {
                setMediaItem(mediaItem(channel, activeSource))
                prepare()
                play()
            }
            exoPlayer?.run {
                setMediaItem(legacyMediaItem(channel, activeSource))
                prepare()
                play()
            }
        } catch (error: Exception) {
            (activity as? MainActivity)?.onPlaybackFailed(channel, error.message ?: "播放格式不支持")
        }
    }

    private fun mediaItem(channel: TVViewModel, source: Int): MediaItem {
        val url = channel.videoUrl.value!![source]
        return MediaItem.Builder().setUri(url).apply {
            mimeType(channel, url)?.let { setMimeType(it) }
        }.build()
    }

    private fun legacyMediaItem(channel: TVViewModel, source: Int):
        com.google.android.exoplayer2.MediaItem {
        val url = channel.videoUrl.value!![source]
        return com.google.android.exoplayer2.MediaItem.Builder().setUri(url).apply {
            mimeType(channel, url)?.let { setMimeType(it) }
        }.build()
    }

    private fun mimeType(channel: TVViewModel, url: String): String? =
        if (channel.getTV().programType == ProgramType.M3U) TVList.mimeTypeOf(url)
        else channel.getTV().mimeType

    fun playbackQuality(): SourceQualityResolver.Quality {
        val format = (playerView?.player as? ExoPlayer)?.videoFormat
        if (format != null) {
            return SourceQualityResolver.Quality(
                format.width.coerceAtLeast(0).toLong() * format.height.coerceAtLeast(0),
                format.bitrate.coerceAtLeast(0), width = format.width, height = format.height
            )
        }
        val legacyFormat = exoPlayer?.videoFormat
        return SourceQualityResolver.Quality(
            (legacyFormat?.width ?: 0).coerceAtLeast(0).toLong() *
                (legacyFormat?.height ?: 0).coerceAtLeast(0),
            (legacyFormat?.bitrate ?: 0).coerceAtLeast(0),
            width = legacyFormat?.width ?: 0, height = legacyFormat?.height ?: 0
        )
    }

    private fun newLegacyPlayer(): SimpleExoPlayer {
        val selector = com.google.android.exoplayer2.trackselection.DefaultTrackSelector(requireContext())
        selector.setParameters(selector.buildUponParameters()
            .setExceedRendererCapabilitiesIfNecessary(false)
            .setExceedVideoConstraintsIfNecessary(false)
            .setForceHighestSupportedBitrate(true)
            .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, true))
        return SimpleExoPlayer.Builder(requireContext()).setTrackSelector(selector).build()
    }

    private fun legacyListener(player: SimpleExoPlayer) =
        object : com.google.android.exoplayer2.Player.EventListener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == com.google.android.exoplayer2.Player.STATE_ENDED &&
                    exoPlayer === player && player.playWhenReady) {
                    tvViewModel?.takeIf { it.videoIndex.value == activeSource }?.let {
                        (activity as? MainActivity)?.onPlaybackFailed(it, "直播源已结束")
                    }
                }
            }

            override fun onPlayerError(error: com.google.android.exoplayer2.ExoPlaybackException) {
                if (exoPlayer === player) {
                    tvViewModel?.takeIf { it.videoIndex.value == activeSource }?.let {
                        (activity as? MainActivity)?.onPlaybackFailed(it, error.message ?: "播放失败")
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && exoPlayer === player) {
                    markPlaying()
                    tvViewModel?.takeIf { it.videoIndex.value == activeSource }?.let {
                        (activity as? MainActivity)?.onPlaybackStarted(it)
                    }
                }
            }
        }

    fun syncSourceIndex(channel: TVViewModel) {
        if (tvViewModel === channel) activeSource = channel.videoIndex.value ?: 0
    }

    fun stopPlayback() {
        resetStallMonitor()
        playerView?.player?.clearMediaItems()
        exoPlayer?.clearMediaItems()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(stallMonitor)
        handler.postDelayed(stallMonitor, 1_000)
        playerView?.player?.let {
            if (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0) it.prepare()
            it.play()
        }
        exoPlayer?.let {
            if (it.playbackState == com.google.android.exoplayer2.Player.STATE_IDLE &&
                it.mediaItemCount > 0) it.prepare()
            it.play()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(stallMonitor)
        resetStallMonitor()
        playerView?.player?.pause()
        exoPlayer?.pause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(stallMonitor)
        playerView?.player?.release()
        playerView?.player = null
        exoPlayer?.release()
        exoPlayer = null
        playerView = null
        binding = null
        super.onDestroyView()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        exoPlayer?.release()
        exoPlayer = newLegacyPlayer().also {
            it.addListener(legacyListener(it))
            it.addVideoListener(object : com.google.android.exoplayer2.video.VideoListener {
                override fun onRenderedFirstFrame() { recordPlayingSource() }
            })
            it.setVideoSurfaceHolder(holder)
        }
        tvViewModel?.let { play(it) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        exoPlayer?.clearVideoSurfaceHolder(holder)
    }

    companion object {
        private const val TAG = "PlaybackVideoFragment"
    }
}
