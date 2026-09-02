package com.example.bbcplayer

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Xml
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

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
    private val bookmarks = LongArray(3) { -1L }

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            repeatStartMs = -1L
            repeatEndMs = -1L
            bookmarks.fill(-1L)
            loadAudio(it, 0L)
            val edit = prefs.edit().putString("last_uri", it.toString()).putLong("last_position", 0L)
                .remove("repeat_start").remove("repeat_end")
            repeat(3) { slot -> edit.remove("bookmark_" + slot) }
            edit.apply()
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
        configureRepeatControls()
        configureBookmarkControls()
        findViewById<Button>(R.id.bbcButton).setOnClickListener { fetchLatestBbcEpisode() }

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
        repeat(3) { slot -> bookmarks[slot] = prefs.getLong("bookmark_" + slot, -1L) }
        if (bookmarks[0] < 0) bookmarks[0] = prefs.getLong("bookmark", -1L)
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
        val saved = prefs.getFloat("speed", 1.0f)
        spinner.setSelection(speeds.indexOf(saved.toString() + "x").let { if (it >= 0) it else 3 })
        spinner.onItemSelectedListener = SimpleItemSelectedListener { selected ->
            val speed = speeds[selected].removeSuffix("x").toFloat()
            player.playbackParameters = PlaybackParameters(speed)
            prefs.edit().putFloat("speed", speed).apply()
        }
    }

    private fun configureSeekStepControl() {
        val labels = listOf("2초", "5초", "10초", "20초")
        val values = listOf(2_000L, 5_000L, 10_000L, 20_000L)
        val spinner = findViewById<Spinner>(R.id.seekStepSpinner)
        spinner.adapter = spinnerAdapter(labels)
        spinner.setSelection(values.indexOf(seekStepMs).let { if (it >= 0) it else 2 })
        spinner.onItemSelectedListener = SimpleItemSelectedListener { selected ->
            seekStepMs = values[selected]
            prefs.edit().putLong("seek_step", seekStepMs).apply()
            updateSeekButtonLabels()
        }
    }

    private fun configureRepeatControls() {
        findViewById<Button>(R.id.aButton).setOnClickListener {
            repeatStartMs = player.currentPosition.coerceAtLeast(0)
            if (repeatEndMs <= repeatStartMs) repeatEndMs = -1L
            saveRepeat()
        }
        findViewById<Button>(R.id.bButton).setOnClickListener {
            val now = player.currentPosition.coerceAtLeast(0)
            when {
                repeatStartMs < 0 -> toast("먼저 A 시작점을 지정해 주세요.")
                now <= repeatStartMs -> toast("B 종료점은 A보다 뒤에 있어야 합니다.")
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
    }

    private fun configureBookmarkControls() {
        val buttons = listOf<Button>(
            findViewById(R.id.bookmark1Button),
            findViewById(R.id.bookmark2Button),
            findViewById(R.id.bookmark3Button)
        )
        buttons.forEachIndexed { slot, button ->
            button.setOnClickListener {
                if (bookmarks[slot] >= 0) player.seekTo(bookmarks[slot])
                else toast((slot + 1).toString() + "번 위치가 비어 있습니다. 길게 눌러 저장하세요.")
            }
            button.setOnLongClickListener {
                bookmarks[slot] = player.currentPosition.coerceAtLeast(0)
                prefs.edit().putLong("bookmark_" + slot, bookmarks[slot]).apply()
                updateLearningLabels()
                toast((slot + 1).toString() + "번 위치에 저장했습니다.")
                true
            }
        }
    }

    private fun fetchLatestBbcEpisode() {
        toast("BBC 최신 회차를 확인하고 있습니다.")
        Thread {
            try {
                val connection = URL(BBC_FEED).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "HD-MP3-Player/1.0")
                val parser = Xml.newPullParser()
                connection.inputStream.use { parser.setInput(it, "UTF-8"); showLatestEpisode(parseFirstEpisode(parser)) }
                connection.disconnect()
            } catch (error: Exception) {
                runOnUiThread { toast("BBC 정보를 불러오지 못했습니다. 인터넷 연결을 확인해 주세요.") }
            }
        }.start()
    }

    private fun parseFirstEpisode(parser: XmlPullParser): BbcEpisode {
        var inItem = false
        var title = ""
        var audioUrl = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> inItem = true
                    "title" -> if (inItem) title = parser.nextText()
                    "enclosure" -> if (inItem) audioUrl = parser.getAttributeValue(null, "url") ?: ""
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item" && inItem) {
                break
            }
            parser.next()
        }
        if (title.isBlank() || audioUrl.isBlank()) error("No BBC episode in feed")
        return BbcEpisode(title, audioUrl)
    }

    private fun showLatestEpisode(episode: BbcEpisode) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("BBC 6 Minute English")
                .setMessage("최신 회차\n\n" + episode.title)
                .setNegativeButton("취소", null)
                .setPositiveButton("MP3 다운로드") { _, _ -> downloadEpisode(episode) }
                .show()
        }
    }

    private fun downloadEpisode(episode: BbcEpisode) {
        val safeTitle = episode.title.replace(Regex("[^A-Za-z0-9가-힣 _-]"), "").take(80)
        val request = DownloadManager.Request(Uri.parse(episode.audioUrl))
            .setTitle(episode.title)
            .setDescription("BBC 6 Minute English")
            .setMimeType("audio/mpeg")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeTitle + ".mp3")
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        prefs.edit().putString("last_bbc_url", episode.audioUrl).apply()
        toast("다운로드를 시작했습니다. 완료 후 파일 열기에서 선택하세요.")
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
        val value = (seekStepMs / 1000).toString()
        backButton.text = "−" + value + "초"
        forwardButton.text = "+" + value + "초"
    }

    private fun updateLearningLabels() {
        repeatStatus.text = when {
            repeatStartMs >= 0 && repeatEndMs >= 0 ->
                "↻  " + formatTime(repeatStartMs) + " – " + formatTime(repeatEndMs)
            repeatStartMs >= 0 -> "A  " + formatTime(repeatStartMs) + "  ·  B를 지정하세요"
            else -> "↻  구간 반복 꺼짐"
        }
        bookmarkStatus.text = bookmarks.mapIndexed { index, value ->
            (index + 1).toString() + "  " + if (value >= 0) formatTime(value) else "--:--"
        }.joinToString("     ")
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
            timeText.text = formatTime(position) + " / " + formatTime(duration)
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) prefs.edit().putLong("last_position", player.currentPosition).apply()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val BBC_FEED = "https://podcasts.files.bbci.co.uk/p02pc9tn.rss"
    }
}

private data class BbcEpisode(val title: String, val audioUrl: String)

private class SimpleItemSelectedListener(private val selected: (Int) -> Unit) :
    AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) =
        selected(position)
    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
