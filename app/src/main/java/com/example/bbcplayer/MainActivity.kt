package com.example.bbcplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var fileName: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bbc_player", MODE_PRIVATE) }

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            loadAudio(it, 0L)
            prefs.edit().putString("last_uri", it.toString()).putLong("last_position", 0L).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        player = ExoPlayer.Builder(this).build()

        playButton = findViewById(R.id.playButton)
        seekBar = findViewById(R.id.seekBar)
        timeText = findViewById(R.id.timeText)
        fileName = findViewById(R.id.fileName)

        findViewById<Button>(R.id.openButton).setOnClickListener { openAudio.launch(arrayOf("audio/mpeg", "audio/*")) }
        playButton.setOnClickListener {
            if (player.isPlaying) { player.pause(); playButton.text = "재생" }
            else { player.play(); playButton.text = "일시정지" }
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)) }
        findViewById<Button>(R.id.forwardButton).setOnClickListener { player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration.coerceAtLeast(0))) }

        val speeds = listOf("0.75x", "0.85x", "1.0x", "1.1x", "1.25x", "1.5x", "2.0x")
        val spinner = findViewById<Spinner>(R.id.speedSpinner)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speeds)
        val savedSpeed = prefs.getFloat("speed", 1.0f)
        spinner.setSelection(speeds.indexOf("${savedSpeed}x").let { if (it >= 0) it else 2 })
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val speed = speeds[position].removeSuffix("x").toFloat()
                player.playbackParameters = PlaybackParameters(speed)
                prefs.edit().putFloat("speed", speed).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) player.seekTo(progress.toLong()) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        prefs.getString("last_uri", null)?.let { loadAudio(Uri.parse(it), prefs.getLong("last_position", 0L)) }
        handler.post(progressUpdater)
    }

    private fun loadAudio(uri: Uri, position: Long) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.seekTo(position)
        fileName.text = getDisplayName(uri)
    }

    private fun getDisplayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && c.moveToFirst()) return c.getString(index)
        }
        return "선택한 MP3"
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val duration = player.duration.coerceAtLeast(0)
            val position = player.currentPosition.coerceAtLeast(0)
            seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            seekBar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
            handler.postDelayed(this, 500)
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putLong("last_position", player.currentPosition).apply()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        player.release()
        super.onDestroy()
    }
}
