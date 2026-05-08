package mbkk.example.musicplayer

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog

class PlayerActivity : AppCompatActivity(), MusicPlayerListener {
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var imgAlbumArt: ImageView

    private var isShuffleOn = false

    private var musicService: MusicService? = null
    private var isBound = false
    private val handler = Handler(Looper.getMainLooper())

    private var openedFromNotification = false

    companion object {
        var songsList = mutableListOf<SongModel>()
        var currentIndex = 0
    }

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            musicService?.listener = this@PlayerActivity

            if (openedFromNotification) {
                currentIndex = musicService?.currentSongIndex ?: 0
                if (songsList.isEmpty()) {
                    songsList = musicService?.songsList ?: mutableListOf()
                }
                updateUI()
            } else {
                if (songsList.isNotEmpty()) {
                    musicService?.songsList = songsList

                    if (currentIndex >= 0 && currentIndex < songsList.size) {
                        musicService?.playSong(currentIndex)
                        updateUI()
                    } else {
                        currentIndex = 0
                        musicService?.playSong(0)
                        updateUI()
                    }
                }
            }

            startSeekBarUpdate()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val songIndex = intent.getIntExtra("SONG_INDEX", -1)
        openedFromNotification = songIndex == -1

        if (!openedFromNotification) {
            currentIndex = songIndex
        }

        btnShuffle = findViewById(R.id.btnshuffle)
        btnMenu = findViewById(R.id.btnmenu)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnBack = findViewById(R.id.btnBack)
        seekBar = findViewById(R.id.seekBar)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        imgAlbumArt = findViewById(R.id.imgAlbumArt)

        val serviceIntent = Intent(this, MusicService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        btnShuffle.setOnClickListener {
            isShuffleOn = !isShuffleOn
            updateShuffleButton()
        }

        btnMenu.setOnClickListener {
            showSongListSheet()
        }

        btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        btnPlayPause.setOnClickListener {
            musicService?.playPause()
        }

        btnNext.setOnClickListener {
            playNext()
        }

        btnPrevious.setOnClickListener {
            playPrevious()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) musicService?.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val songIndex = intent.getIntExtra("SONG_INDEX", -1)
        openedFromNotification = songIndex == -1

        if (!openedFromNotification) {
            currentIndex = songIndex
        }

        musicService?.let {
            if (openedFromNotification) {
                currentIndex = it.currentSongIndex
                if (songsList.isEmpty()) songsList = it.songsList
                updateUI()
            } else {
                it.songsList = songsList
                it.playSong(currentIndex)
                updateUI()
            }
        }
    }

    override fun onSongChanged(index: Int) {
        runOnUiThread {
            currentIndex = index
            updateUI()
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            updatePlayPauseButton()
        }
    }

    override fun onResume() {
        super.onResume()
        musicService?.let {
            it.listener = this
            currentIndex = it.currentSongIndex
            if (songsList.isEmpty() && it.songsList.isNotEmpty()) {
                songsList = it.songsList
            }
            updateUI()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    private fun playNext() {
        if (isShuffleOn && songsList.size > 1) {
            var randomIndex: Int
            do {
                randomIndex = (songsList.indices).random()
            } while (randomIndex == currentIndex)
            currentIndex = randomIndex
            musicService?.playSong(currentIndex)
            updateUI()
        } else {
            musicService?.nextSong()
        }
    }

    private fun playPrevious() {
        if (isShuffleOn && songsList.size > 1) {
            var randomIndex: Int
            do {
                randomIndex = (songsList.indices).random()
            } while (randomIndex == currentIndex)
            currentIndex = randomIndex
            musicService?.playSong(currentIndex)
            updateUI()
        } else {
            musicService?.previousSong()
        }
    }

    private fun updateShuffleButton() {
        if (isShuffleOn) {
            btnShuffle.setColorFilter("#1DB954".toColorInt())
        } else {
            btnShuffle.setColorFilter("#9B9B9B".toColorInt())
        }
    }

    @SuppressLint("SetTextI18n")
    override fun showSongListSheet() {
        if (songsList.isEmpty()) return

        val dialog = BottomSheetDialog(this)

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor("#181818".toColorInt())
            setPadding(0, 24, 0, 0)
        }

        val header = TextView(this).apply {
            text = "Song List"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(48, 16, 48, 24)
        }
        root.addView(header)

        val divider = View(this).apply {
            setBackgroundColor("#333333".toColorInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        root.addView(divider)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = SongListAdapter(songsList, currentIndex) { index ->
                currentIndex = index
                musicService?.playSong(index)
                updateUI()
                dialog.dismiss()
            }
        }
        root.addView(rv)

        dialog.setContentView(root)
        dialog.show()
    }

    private inner class SongListAdapter(
        private val songs: List<SongModel>,
        private val playingIndex: Int,
        private val onSongClick: (Int) -> Unit
    ) : RecyclerView.Adapter<SongListAdapter.VH>() {

        inner class VH(val row: android.widget.LinearLayout) : RecyclerView.ViewHolder(row) {
            val tvTitle: TextView = row.getChildAt(0) as TextView
            val tvArtist: TextView = row.getChildAt(1) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = android.widget.LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 20, 48, 20)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val title = TextView(parent.context).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val artist = TextView(parent.context).apply {
                textSize = 12f
                setTextColor("#9B9B9B".toColorInt())
                setPadding(0, 4, 0, 0)
            }
            row.addView(title)
            row.addView(artist)
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val song = songs[position]
            holder.tvTitle.text = song.title
            holder.tvArtist.text = song.artist

            if (position == playingIndex) {
                holder.tvTitle.setTextColor("#1DB954".toColorInt())
            } else {
                holder.tvTitle.setTextColor(Color.WHITE)
            }

            holder.row.setOnClickListener { onSongClick(position) }
        }

        override fun getItemCount() = songs.size
    }


    private fun updateUI() {
        if (songsList.isEmpty()) return
        if (currentIndex < 0 || currentIndex >= songsList.size) return

        val song = songsList[currentIndex]
        tvSongTitle.text = song.title
        tvArtist.text = song.artist

        Glide.with(this)
            .load(song.albumArt)
            .placeholder(android.R.drawable.ic_media_play)
            .error(android.R.drawable.ic_media_play)
            .into(imgAlbumArt)

        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        if (musicService?.isPlaying() == true) {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun startSeekBarUpdate() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                musicService?.let {
                    val current = it.getCurrentPosition()
                    val duration = it.getDuration()
                    seekBar.max = duration
                    seekBar.progress = current
                    tvCurrentTime.text = formatTime(current)
                    tvTotalTime.text = formatTime(duration)
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    @SuppressLint("DefaultLocale")
    private fun formatTime(ms: Int): String {
        val minutes = (ms / 1000) / 60
        val seconds = (ms / 1000) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        musicService?.listener = null
        handler.removeCallbacksAndMessages(null)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}