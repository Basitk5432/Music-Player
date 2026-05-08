package mbkk.example.musicplayer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class SongAdapter(
    private val songs: MutableList<SongModel>,
    private val onItemClick: (SongModel, Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var originalList = mutableListOf<SongModel>()

    inner class SongViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val imgAlbumArt: ImageView = itemView.findViewById(R.id.imgAlbumArt)
        val tvTitle: TextView      = itemView.findViewById(R.id.tvTitle)
        val tvArtist: TextView     = itemView.findViewById(R.id.tvArtist)
        val tvDuration: TextView   = itemView.findViewById(R.id.tvDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]

        holder.tvTitle.text    = song.title
        holder.tvArtist.text   = song.artist
        holder.tvDuration.text = formatDuration(song.duration)

        Glide.with(holder.itemView.context)
            .load(song.albumArt)
            .placeholder(android.R.drawable.ic_media_play)
            .error(android.R.drawable.ic_media_play)
            .into(holder.imgAlbumArt)

        holder.itemView.setOnClickListener {
            val originalIndex = originalList.indexOfFirst { it.id == song.id }
            val indexToPlay   = if (originalIndex != -1) originalIndex else position
            onItemClick(song, indexToPlay)
        }
    }

    override fun getItemCount(): Int = songs.size

    fun setOriginalList(list: List<SongModel>) {
        originalList = list.toMutableList()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun filter(query: String, fullList: List<SongModel>) {
        if (originalList.isEmpty()) {
            originalList = fullList.toMutableList()
        }

        val filtered = if (query.isEmpty()) {
            originalList.toMutableList()
        } else {
            originalList.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true)
            }.toMutableList()
        }

        songs.clear()
        songs.addAll(filtered)
        notifyDataSetChanged()
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(duration: Long): String {
        val minutes = (duration / 1000) / 60
        val seconds = (duration / 1000) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}