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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playButton: Button
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var fileName: TextView
    private lateinit var repeatStatus: TextView
    private lateinit var bookmarkStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bbc_player", MODE_PRIVATE) }
    private var seekStepMs = 10_000L
    private var repeatStartMs = -1L
    private var repeatEndMs = -1L
    private var bookmarkMs = -1L

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            repeatStartMs = -1L
            repeatEndMs = -1L
            bookmarkMs = -1L
            loadAudio(it, 0L)
            prefs.edit().putString("last_uri", it.toString()).putLong("last_position", 0L)
                .remove("repeat_start").remove("repeat_end").remove("bookmark").apply()
            updateLearningLabels()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        player = ExoPlayer.Builder(this).build()
        bindViews()
        restoreLearningState()
        configurePlaybackControls()
        configureSpeedControl()
        configureSeekStepControl()
        configureLearningControls()
        prefs.getString("last_uri", null)?.let {
            loadAudio(Uri.parse(it), prefs.getLong("last_position", 0L))
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "일시정지" else "재생"
            }
        })
        handler.post(progressUpdater)
    }

    private fun bindViews() {
        playButton = findViewById(R.id.playButton)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        seekBar = findViewById(R.id.seekBar)
        timeText = findViewById(R.id.timeText)
        fileName = findViewById(R.id.fileName)
        repeatStatus = findViewById(R.id.repeatStatus)
        bookmarkStatus = findViewById(R.id.bookmarkStatus)
    }

    private fun restoreLearningState() {
        seekStepMs = prefs.getLong("seek_step", 10_000L)
        repeatStartMs = prefs.getLong("repeat_start", -1L)
        repeatEndMs = prefs.getLong("repeat_end", -1L)
        bookmarkMs = prefs.getLong("bookmark", -1L)
        updateLearningLabels()
        updateSeekButtonLabels()
    }

    private fun configurePlaybackControls() {
        findViewById<Button>(R.id.openButton).setOnClickListener { openAudio.launch(arrayOf("audio/*")) }
        playButton.setOnClickListener {
            if (player.mediaItemCount == 0) {
                Toast.makeText(this, "먼저 오디오 파일을 열어 주세요.", Toast.LENGTH_SHORT).show()
            } else if (player.isPlaying) player.pause() else player.play()
        }
        backButton.setOnClickListener {
            player.seekTo((player.currentPosition - seekStepMs).coerceAtLeast(0))
        }
        forwardButton.setOnClickListener {
            player.seekTo((player.currentPosition + seekStepMs).coerceAtMost(player.duration.coerceAtLeast(0)))
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) player.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun configureSpeedControl() {
        val speeds = listOf("0.5x", "0.75x", "0.85x", "1.0x", "1.1x", "1.25x", "1.5x", "1.75x", "2.0x")
        val spinner = findViewById<Spinner>(R.id.speedSpinner)
        spinner.adapter = spinnerAdapter(speeds)
        val savedSpeed = prefs.getFloat("speed", 1.0f)
        spinner.setSelection(speeds.indexOf("${savedSpeed}x").let { if (it >= 0) it else 3 })
        spinner.onItemSelectedListener = SimpleItemSelectedListener { selected ->
            val speed = speeds[selected].removeSuffix("x").toFloat()
            player.playbackParameters = PlaybackParameters(speed)
            prefs.edit().putFloat("speed", speed).apply()
        }
    }

    private fun configureSeekStepControl() {
        val labels = listOf("5초", "10초", "30초", "60초")
        val values = listOf(5_000L, 10_000L, 30_000L, 60_000L)
        val spinner = findViewById<Spinner>(R.id.seekStepSpinner)
        spinner.adapter = spinnerAdapter(labels)
        spinner.setSelection(values.indexOf(seekStepMs).let { if (it >= 0) it else 1 })
        spinner.onItemSelectedListener = SimpleItemSelectedListener { selected ->
            seekStepMs = values[selected]
            prefs.edit().putLong("seek_step", seekStepMs).apply()
            updateSeekButtonLabels()
        }
    }

    private fun configureLearningControls() {
        findViewById<Button>(R.id.aButton).setOnClickListener {
            repeatStartMs = player.currentPosition.coerceAtLeast(0)
            if (repeatEndMs <= repeatStartMs) repeatEndMs = -1L
            saveRepeat()
        }
        findViewById<Button>(R.id.bButton).setOnClickListener {
            val now = player.currentPosition.coerceAtLeast(0)
            when {
                repeatStartMs < 0 -> Toast.makeText(this, "먼저 A 시작점을 지정해 주세요.", Toast.LENGTH_SHORT).show()
                now <= repeatStartMs -> Toast.makeText(this, "B 종료점은 A보다 뒤에 있어야 합니다.", Toast.LENGTH_SHORT).show()
                else -> {
                    repeatEndMs = now
                    saveRepeat()
                    player.seekTo(repeatStartMs)
                }
            }
        }
        findViewById<Button>(R.id.clearRepeatButton).setOnClickListener {
            repeatStartMs = -1L
            repeatEndMs = -1L
            saveRepeat()
        }
        findViewById<Button>(R.id.bookmarkButton).setOnClickListener {
            bookmarkMs = player.currentPosition.coerceAtLeast(0)
            prefs.edit().putLong("bookmark", bookmarkMs).apply()
            updateLearningLabels()
            Toast.makeText(this, "현재 위치를 저장했습니다.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.bookmarkGoButton).setOnClickListener {
            if (bookmarkMs >= 0) player.seekTo(bookmarkMs)
            else Toast.makeText(this, "저장된 위치가 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, R.layout.item_spinner, R.id.spinnerText, items).also {
            it.setDropDownViewResource(R.layout.item_spinner)
        }

    private fun saveRepeat() {
        prefs.edit().putLong("repeat_start", repeatStartMs).putLong("repeat_end", repeatEndMs).apply()
        updateLearningLabels()
    }

    private fun updateSeekButtonLabels() {
        val seconds = seekStepMs / 1000
        backButton.text = "−${seconds}초"
        forwardButton.text = "+${seconds}초"
    }

    private fun updateLearningLabels() {
        repeatStatus.text = when {
            repeatStartMs >= 0 && repeatEndMs >= 0 ->
                "구간 반복  ${formatTime(repeatStartMs)} – ${formatTime(repeatEndMs)}"
            repeatStartMs >= 0 -> "A 시작점  ${formatTime(repeatStartMs)}  ·  B를 지정하세요"
            else -> "구간 반복이 꺼져 있습니다"
        }
        bookmarkStatus.text =
            if (bookmarkMs >= 0) "저장 위치  ${formatTime(bookmarkMs)}" else "저장된 위치가 없습니다"
    }

    private fun loadAudio(uri: Uri, startPosition: Long) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.seekTo(startPosition)
        fileName.text = getDisplayName(uri)
    }

    private fun getDisplayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return "선택한 오디오"
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val duration = player.duration.coerceAtLeast(0)
            var position = player.currentPosition.coerceAtLeast(0)
            if (repeatStartMs >= 0 && repeatEndMs > repeatStartMs && position >= repeatEndMs) {
                player.seekTo(repeatStartMs)
                position = repeatStartMs
            }
            seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            seekBar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
            handler.postDelayed(this, 250)
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) prefs.edit().putLong("last_position", player.currentPosition).apply()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        player.release()
        super.onDestroy()
    }
}

private class SimpleItemSelectedListener(private val select: (Int) -> Unit) :
    android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = select(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
