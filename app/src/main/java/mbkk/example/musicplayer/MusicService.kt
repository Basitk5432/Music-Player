package mbkk.example.musicplayer

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri

interface MusicPlayerListener {
    fun onSongChanged(index: Int)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun showSongListSheet()
}

class MusicService : Service() {

    var listener: MusicPlayerListener? = null
    private val binder = MusicBinder()
    var mediaPlayer: MediaPlayer? = null
    var currentSongIndex = 0
    var songsList = mutableListOf<SongModel>()
    var isShuffle = false
        private set

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false
    private lateinit var mediaSession: MediaSessionCompat

    private var cachedAlbumArt: Bitmap? = null
    private var cachedAlbumArtIndex: Int = -1

    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationRunnable = object : Runnable {
        override fun run() {
            if (mediaPlayer?.isPlaying == true) {
                showNotification()
                updatePlaybackState(true)
            }
            notificationHandler.postDelayed(this, 1000)
        }
    }

    companion object {
        const val CHANNEL_ID = "music_channel"
        const val ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREVIOUS = "ACTION_PREVIOUS"
        const val ACTION_SHUFFLE = "ACTION_SHUFFLE"
        const val ACTION_FORWARD_10 = "ACTION_FORWARD_10"
        const val ACTION_REWIND_10 = "ACTION_REWIND_10"
        const val NOTIFICATION_ID = 1
        const val SEEK_INCREMENT_MS = 10_000
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        setupMediaSession()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playPause()
            ACTION_NEXT -> nextSong()
            ACTION_PREVIOUS -> previousSong()
            ACTION_FORWARD_10 -> forward10()
            ACTION_REWIND_10 -> rewind10()
        }
        return START_NOT_STICKY
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayerSession")

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                playPause()
            }

            override fun onPause() {
                playPause()
            }

            override fun onSkipToNext() {
                nextSong()
            }

            override fun onSkipToPrevious() {
                previousSong()
            }

            override fun onFastForward() {
                forward10()
            }

            override fun onRewind() {
                rewind10()
            }

            override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                when (action) {
                    ACTION_FORWARD_10 -> forward10()
                    ACTION_REWIND_10 -> rewind10()
                }
            }

            override fun onSeekTo(pos: Long) {
                mediaPlayer?.seekTo(pos.toInt())
                updatePlaybackState(isPlaying())
                listener?.onPlaybackStateChanged(isPlaying())
            }
        })

        mediaSession.isActive = true
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PAUSED

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_FAST_FORWARD

            )
            .setState(
                state,
                mediaPlayer?.currentPosition?.toLong() ?: 0L,
                if (isPlaying) 1f else 0f
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_REWIND_10,
                    "Rewind 10s",
                    android.R.drawable.ic_media_rew
                ).build()
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_FORWARD_10,
                    "Forward 10s",
                    android.R.drawable.ic_media_ff
                ).build()
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private val audioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    wasPlayingBeforeFocusLoss = mediaPlayer?.isPlaying == true
                    mediaPlayer?.pause()
                    updatePlaybackState(false)
                    listener?.onPlaybackStateChanged(false)
                    notificationHandler.removeCallbacks(notificationRunnable)
                    showNotification()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    wasPlayingBeforeFocusLoss = mediaPlayer?.isPlaying == true
                    mediaPlayer?.pause()
                    updatePlaybackState(false)
                    listener?.onPlaybackStateChanged(false)
                    notificationHandler.removeCallbacks(notificationRunnable)
                    showNotification()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    mediaPlayer?.setVolume(0.3f, 0.3f)
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    mediaPlayer?.setVolume(1.0f, 1.0f)
                    if (wasPlayingBeforeFocusLoss) {
                        mediaPlayer?.start()
                        updatePlaybackState(true)
                        listener?.onPlaybackStateChanged(true)
                        notificationHandler.removeCallbacks(notificationRunnable)
                        notificationHandler.post(notificationRunnable)
                    }
                }
            }
        }

    private fun requestAudioFocus(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(audioFocusRequest!!)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun loadHighResAlbumArt(song: SongModel): Bitmap? {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(song.path)
            val art = retriever.embeddedPicture
            retriever.release()
            if (art != null) {
                return BitmapFactory.decodeByteArray(art, 0, art.size)
            }
        } catch (_: Exception) {
        }

        return try {
            val uri = song.albumArt.toUri()
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getCachedAlbumArt(): Bitmap? {
        if (cachedAlbumArtIndex != currentSongIndex) {
            val song = songsList.getOrNull(currentSongIndex) ?: return null
            val raw = loadHighResAlbumArt(song)
            cachedAlbumArt = raw
            cachedAlbumArtIndex = currentSongIndex
        }
        return cachedAlbumArt
    }

    @SuppressLint("DefaultLocale")
    private fun formatTime(ms: Int): String {
        val minutes = (ms / 1000) / 60
        val seconds = (ms / 1000) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    fun playSong(index: Int) {
        currentSongIndex = index
        val song = songsList[index]

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        if (!requestAudioFocus()) return

        mediaPlayer = MediaPlayer().apply {
            setDataSource(song.path)
            prepare()
            start()
            setOnCompletionListener { nextSong() }
        }

        updateMediaMetadata()

        notificationHandler.removeCallbacks(notificationRunnable)
        notificationHandler.post(notificationRunnable)

        listener?.onSongChanged(currentSongIndex)
        listener?.onPlaybackStateChanged(true)
        updatePlaybackState(true)
        showNotification()
    }

    fun playPause() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            abandonAudioFocus()
            updatePlaybackState(false)
            listener?.onPlaybackStateChanged(false)
            notificationHandler.removeCallbacks(notificationRunnable)
        } else {
            if (requestAudioFocus()) {
                mediaPlayer?.start()
                updatePlaybackState(true)
                listener?.onPlaybackStateChanged(true)
                notificationHandler.removeCallbacks(notificationRunnable)
                notificationHandler.post(notificationRunnable)
            }
        }
        showNotification()
    }

    fun nextSong() {
        if (songsList.isEmpty()) return
        currentSongIndex = if (isShuffle && songsList.size > 1) {
            var rand: Int
            do {
                rand = (0 until songsList.size).random()
            } while (rand == currentSongIndex)
            rand
        } else if (currentSongIndex < songsList.size - 1) {
            currentSongIndex + 1
        } else {
            0
        }
        playSong(currentSongIndex)
    }

    fun previousSong() {
        if (songsList.isEmpty()) return
        currentSongIndex = if (isShuffle && songsList.size > 1) {
            var rand: Int
            do {
                rand = (0 until songsList.size).random()
            } while (rand == currentSongIndex)
            rand
        } else if (currentSongIndex > 0) {
            currentSongIndex - 1
        } else {
            songsList.size - 1
        }
        playSong(currentSongIndex)
    }


    fun forward10() {
        val mp = mediaPlayer ?: return
        val newPos = (mp.currentPosition + SEEK_INCREMENT_MS).coerceAtMost(mp.duration)
        mp.seekTo(newPos)
        updatePlaybackState(mp.isPlaying)
        showNotification()
    }

    fun rewind10() {
        val mp = mediaPlayer ?: return
        val newPos = (mp.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0)
        mp.seekTo(newPos)
        updatePlaybackState(mp.isPlaying)
        showNotification()
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun seekTo(position: Int) = mediaPlayer?.seekTo(position)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Player",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Music playback controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun showNotification() {
        val song = songsList.getOrNull(currentSongIndex) ?: return
        val isPlaying = mediaPlayer?.isPlaying == true

        val albumArtBitmap = getCachedAlbumArt()
            ?: BitmapFactory.decodeResource(resources, android.R.drawable.ic_media_play)

        val shuffleIntent = PendingIntent.getService(
            this, 10,
            Intent(this, MusicService::class.java).apply { action = ACTION_SHUFFLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rewindIntent = PendingIntent.getService(
            this, 11,
            Intent(this, MusicService::class.java).apply { action = ACTION_REWIND_10 },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MusicService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val forwardIntent = PendingIntent.getService(
            this, 12,
            Intent(this, MusicService::class.java).apply { action = ACTION_FORWARD_10 },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MusicService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = PendingIntent.getActivity(
            this, 3,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play


        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(albumArtBitmap)
            .setContentIntent(openAppIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

            .addAction(android.R.drawable.ic_media_rew, "Rewind 10s", rewindIntent)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", playPauseIntent)
            .addAction(android.R.drawable.ic_media_ff, "Forward 10s", forwardIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateMediaMetadata() {
        val song = songsList.getOrNull(currentSongIndex) ?: return
        val duration = mediaPlayer?.duration?.toLong() ?: 0L

        val albumArtBitmap = getCachedAlbumArt()

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArtBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, albumArtBitmap)
            .build()

        mediaSession.setMetadata(metadata)
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationHandler.removeCallbacks(notificationRunnable)
        listener = null
        abandonAudioFocus()
        mediaSession.release()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        cachedAlbumArt?.recycle()
        cachedAlbumArt = null
    }
}