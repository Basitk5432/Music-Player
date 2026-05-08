package mbkk.example.musicplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class MainActivity : AppCompatActivity(), MusicPlayerListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var adapter: SongAdapter
    private val songList = mutableListOf<SongModel>()
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniAlbumArt: ImageView
    private lateinit var miniSongTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayPause: ImageButton
    private lateinit var miniNext: ImageButton

    private var musicService: MusicService? = null
    private var isBound = false

    // ✅ serviceConnection is back as a proper object
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            musicService?.listener = this@MainActivity

            val index = musicService?.currentSongIndex ?: -1
            val isPlaying = musicService?.isPlaying() ?: false

            if (index >= 0 && isPlaying) {
                val song = songList.getOrNull(index)
                    ?: musicService?.songsList?.getOrNull(index)
                if (song != null) updateMiniPlayer(song)
            } else {
                miniPlayer.visibility = View.GONE
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                loadSongs()
            } else {
                Toast.makeText(this, "Permission Denied!", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        etSearch = findViewById(R.id.etSearch)
        miniPlayer = findViewById(R.id.miniPlayer)
        miniAlbumArt = findViewById(R.id.miniAlbumArt)
        miniSongTitle = findViewById(R.id.miniSongTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniPlayPause = findViewById(R.id.miniPlayPause)
        miniNext = findViewById(R.id.miniNext)

        // Hide mini player by default on fresh start
        miniPlayer.visibility = View.GONE

        adapter = SongAdapter(songList) { song, index ->
            etSearch.setText("")
            PlayerActivity.songsList = ArrayList(songList)
            PlayerActivity.currentIndex = index
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra("SONG_INDEX", index)
            startActivity(intent)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            @SuppressLint("SetTextI18n")
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString(), songList)
                findViewById<TextView>(R.id.tvSongsCount).text = "${adapter.itemCount} Songs"
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        miniPlayPause.setOnClickListener {
            val currentlyPlaying = musicService?.isPlaying() ?: false
            miniPlayPause.setImageResource(
                if (!currentlyPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            musicService?.playPause()
        }

        miniNext.setOnClickListener {
            musicService?.nextSong()
        }

        miniPlayer.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra("SONG_INDEX", -1)
            startActivity(intent)
        }

        val serviceIntent = Intent(this, MusicService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        checkAndRequestPermissions()
    }

    override fun onSongChanged(index: Int) {
        runOnUiThread {
            val song = songList.getOrNull(index) ?: return@runOnUiThread
            updateMiniPlayer(song)
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            val index = musicService?.currentSongIndex ?: return@runOnUiThread
            val song = songList.getOrNull(index) ?: return@runOnUiThread
            updateMiniPlayer(song)
        }
    }

    override fun showSongListSheet() {}

    private fun updateMiniPlayer(song: SongModel) {
        miniPlayer.visibility = View.VISIBLE
        miniSongTitle.text = song.title
        miniArtist.text = song.artist
        Glide.with(this)
            .load(song.albumArt)
            .placeholder(android.R.drawable.ic_media_play)
            .error(android.R.drawable.ic_media_play)
            .into(miniAlbumArt)

        val isPlaying = musicService?.isPlaying() ?: false
        miniPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        etSearch.setText("")
        if (songList.isNotEmpty()) {
            adapter.filter("", songList)
            adapter.notifyDataSetChanged()
        }

        if (isBound && musicService != null) {
            musicService!!.listener = this
            val index = musicService!!.currentSongIndex
            val isPlaying = musicService!!.isPlaying()

            if (isPlaying || (index >= 0 && musicService!!.songsList.isNotEmpty())) {
                if (songList.isEmpty()) songList.addAll(musicService!!.songsList)
                val song = songList.getOrNull(index)
                if (song != null) {
                    updateMiniPlayer(song)
                    miniPlayPause.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    )
                }
            } else {
                miniPlayer.visibility = View.GONE
            }
        } else {
            miniPlayer.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            musicService?.listener = null
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) loadSongs()
        else requestPermissionLauncher.launch(notGranted.toTypedArray())
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun loadSongs() {
        songList.clear()
        songList.addAll(getAllSongs())
        adapter.setOriginalList(songList)
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.tvSongsCount).text = "${songList.size} Songs"
    }

    private fun getAllSongs(): List<SongModel> {
        val list = mutableListOf<SongModel>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val albumId = cursor.getLong(albumIdCol)
                list.add(
                    SongModel(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol),
                        artist = cursor.getString(artistCol),
                        duration = cursor.getLong(durationCol),
                        path = cursor.getString(dataCol),
                        albumArt = "content://media/external/audio/albumart/$albumId"
                    )
                )
            }
        }
        return list
    }
}