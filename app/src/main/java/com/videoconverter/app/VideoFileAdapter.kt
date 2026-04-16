package com.videoconverter.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * RecyclerView adapter for displaying selected video files.
 */
class VideoFileAdapter(
    private val onRemove: (VideoFile) -> Unit
) : ListAdapter<VideoFile, VideoFileAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoFile>() {
            override fun areItemsTheSame(a: VideoFile, b: VideoFile) = a.uri == b.uri
            override fun areContentsTheSame(a: VideoFile, b: VideoFile) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvSize: TextView = view.findViewById(R.id.tvFileSize)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.displayName
        holder.tvSize.text = formatSize(item.sizeBytes)
        holder.btnRemove.setOnClickListener { onRemove(item) }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            else -> String.format(Locale.US, "%.1f KB", kb)
        }
    }
}
