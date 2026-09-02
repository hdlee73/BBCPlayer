package com.example.bbcplayer

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Xml
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playButton: Button
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var seekBar: RepeatSeekBar
    private lateinit var timeText: TextView
    private lateinit var fileName: TextView
    private lateinit var playlistStatus: TextView
    private lateinit var repeatStatus: TextView
    private lateinit var bookmarkStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bbc_player", MODE_PRIVATE) }
    private val playlist = mutableListOf<Uri>()
    private val bookmarks = LongArray(3) { -1L }
    private var currentIndex = 0
    private var seekStepMs = 10_000L
    private var repeatStartMs = -1L
    private var repeatEndMs = -1L
    private var repeatMode = 0
    private var repeatLimit = 1
    private var fileRepeatsDone = 0
    private var playlistCyclesDone = 0

    private val openAudios = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) {}
            }
            playlist.clear()
            playlist.addAll(uris)
            currentIndex = 0
            clearFileLearningState()
            savePlaylist()
            loadCurrent(0L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        player = ExoPlayer.Builder(this).build()
        bindViews()
        restoreState()
        configurePlaybackControls()
        configureSpinners()
        configureRepeatRangeControls()
        configureBookmarks()
        findViewById<Button>(R.id.bbcButton).setOnClickListener { fetchLatestBbcEpisode() }
        if (playlist.isNotEmpty()) loadCurrent(prefs.getLong("last_position", 0L))
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "일시정지" else "재생"
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) handlePlaybackEnded()
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
        playlistStatus = findViewById(R.id.playlistStatus)
        repeatStatus = findViewById(R.id.repeatStatus)
        bookmarkStatus = findViewById(R.id.bookmarkStatus)
    }

    private fun restoreState() {
        seekStepMs = prefs.getLong("seek_step", 10_000L)
        repeatStartMs = prefs.getLong("repeat_start", -1L)
        repeatEndMs = prefs.getLong("repeat_end", -1L)
        repeatMode = prefs.getInt("repeat_mode", 0)
        repeatLimit = prefs.getInt("repeat_limit", 1)
        repeat(3) { bookmarks[it] = prefs.getLong("bookmark_" + it, -1L) }
        val saved = prefs.getString("playlist", null)
        if (saved != null) {
            runCatching {
                val array = JSONArray(saved)
                repeat(array.length()) { playlist.add(Uri.parse(array.getString(it))) }
            }
        } else prefs.getString("last_uri", null)?.let { playlist.add(Uri.parse(it)) }
        currentIndex = prefs.getInt("playlist_index", 0).coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        updateLabels()
        updateSeekButtonLabels()
    }

    private fun configurePlaybackControls() {
        findViewById<Button>(R.id.openButton).setOnClickListener { openAudios.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.previousFileButton).setOnClickListener { moveFile(-1) }
        findViewById<Button>(R.id.nextFileButton).setOnClickListener { moveFile(1) }
        playButton.setOnClickListener {
            if (playlist.isEmpty()) toast("먼저 오디오 파일을 열어 주세요.")
            else if (player.isPlaying) player.pause() else player.play()
        }
        backButton.setOnClickListener { player.seekTo((player.currentPosition - seekStepMs).coerceAtLeast(0)) }
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

    private fun configureSpinners() {
        setSpinner(R.id.speedSpinner, listOf("0.5x", "0.75x", "0.85x", "1.0x", "1.1x", "1.25x", "1.5x", "1.75x", "2.0x"),
            prefs.getFloat("speed", 1.0f).toString() + "x") { value ->
            val speed = value.removeSuffix("x").toFloat()
            player.playbackParameters = PlaybackParameters(speed)
            prefs.edit().putFloat("speed", speed).apply()
        }
        val seekLabels = listOf("2초", "5초", "10초", "20초")
        setSpinner(R.id.seekStepSpinner, seekLabels, (seekStepMs / 1000).toString() + "초") { value ->
            seekStepMs = value.removeSuffix("초").toLong() * 1000
            prefs.edit().putLong("seek_step", seekStepMs).apply()
            updateSeekButtonLabels()
        }
        val modes = listOf("반복 끔", "파일 반복", "목록 반복")
        setSpinner(R.id.repeatModeSpinner, modes, modes[repeatMode.coerceIn(0, 2)]) { value ->
            repeatMode = modes.indexOf(value)
            resetRepeatCounters()
            prefs.edit().putInt("repeat_mode", repeatMode).apply()
        }
        val counts = listOf("1회", "3회", "5회", "10회", "∞")
        val selectedCount = if (repeatLimit < 0) "∞" else repeatLimit.toString() + "회"
        setSpinner(R.id.repeatCountSpinner, counts, selectedCount) { value ->
            repeatLimit = if (value == "∞") -1 else value.removeSuffix("회").toInt()
            resetRepeatCounters()
            prefs.edit().putInt("repeat_limit", repeatLimit).apply()
        }
    }

    private fun setSpinner(id: Int, values: List<String>, selected: String, changed: (String) -> Unit) {
        val spinner = findViewById<Spinner>(id)
        spinner.adapter = ArrayAdapter(this, R.layout.item_spinner, R.id.spinnerText, values).also {
            it.setDropDownViewResource(R.layout.item_spinner)
        }
        spinner.setSelection(values.indexOf(selected).let { if (it >= 0) it else 0 })
        spinner.onItemSelectedListener = SimpleItemSelectedListener { changed(values[it]) }
    }

    private fun configureRepeatRangeControls() {
        findViewById<Button>(R.id.aButton).setOnClickListener {
            repeatStartMs = player.currentPosition.coerceAtLeast(0)
            if (repeatEndMs <= repeatStartMs) repeatEndMs = -1L
            saveRepeatRange()
        }
        findViewById<Button>(R.id.bButton).setOnClickListener {
            val now = player.currentPosition.coerceAtLeast(0)
            when {
                repeatStartMs < 0 -> toast("먼저 A 시작점을 지정해 주세요.")
                now <= repeatStartMs -> toast("B 종료점은 A보다 뒤에 있어야 합니다.")
                else -> { repeatEndMs = now; saveRepeatRange(); player.seekTo(repeatStartMs) }
            }
        }
        findViewById<Button>(R.id.clearRepeatButton).setOnClickListener {
            repeatStartMs = -1L; repeatEndMs = -1L; saveRepeatRange()
        }
    }

    private fun configureBookmarks() {
        val buttons = listOf<Button>(findViewById(R.id.bookmark1Button), findViewById(R.id.bookmark2Button), findViewById(R.id.bookmark3Button))
        buttons.forEachIndexed { slot, button ->
            button.setOnClickListener {
                if (bookmarks[slot] >= 0) player.seekTo(bookmarks[slot])
                else toast((slot + 1).toString() + "번이 비어 있습니다. 길게 눌러 저장하세요.")
            }
            button.setOnLongClickListener {
                bookmarks[slot] = player.currentPosition.coerceAtLeast(0)
                prefs.edit().putLong("bookmark_" + slot, bookmarks[slot]).apply()
                updateLabels(); toast((slot + 1).toString() + "번 위치에 저장했습니다."); true
            }
        }
    }

    private fun handlePlaybackEnded() {
        when (repeatMode) {
            1 -> if (repeatLimit < 0 || fileRepeatsDone < repeatLimit) {
                fileRepeatsDone++; player.seekTo(0); player.play()
            } else { fileRepeatsDone = 0; moveFile(1, true) }
            2 -> if (currentIndex < playlist.lastIndex) moveFile(1, true)
            else if (repeatLimit < 0 || playlistCyclesDone < repeatLimit) {
                playlistCyclesDone++; currentIndex = 0; loadCurrent(0L, true)
            }
            else -> if (currentIndex < playlist.lastIndex) moveFile(1, true)
        }
    }

    private fun moveFile(direction: Int, autoPlay: Boolean = player.isPlaying) {
        val next = currentIndex + direction
        if (next !in playlist.indices) { toast(if (direction < 0) "첫 파일입니다." else "마지막 파일입니다."); return }
        currentIndex = next
        resetRepeatCounters()
        clearFileLearningState()
        loadCurrent(0L, autoPlay)
    }

    private fun loadCurrent(position: Long, autoPlay: Boolean = false) {
        if (playlist.isEmpty()) return
        val uri = playlist[currentIndex]
        player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.seekTo(position)
        if (autoPlay) player.play()
        fileName.text = getDisplayName(uri)
        prefs.edit().putInt("playlist_index", currentIndex).putString("last_uri", uri.toString()).apply()
        updateLabels()
    }

    private fun savePlaylist() {
        val array = JSONArray(); playlist.forEach { array.put(it.toString()) }
        prefs.edit().putString("playlist", array.toString()).putInt("playlist_index", currentIndex).apply()
    }

    private fun clearFileLearningState() {
        repeatStartMs = -1L; repeatEndMs = -1L; bookmarks.fill(-1L)
        val edit = prefs.edit().remove("repeat_start").remove("repeat_end")
        repeat(3) { edit.remove("bookmark_" + it) }; edit.apply(); updateLabels()
    }

    private fun saveRepeatRange() {
        prefs.edit().putLong("repeat_start", repeatStartMs).putLong("repeat_end", repeatEndMs).apply()
        updateLabels()
    }

    private fun resetRepeatCounters() { fileRepeatsDone = 0; playlistCyclesDone = 0 }

    private fun updateSeekButtonLabels() {
        val seconds = (seekStepMs / 1000).toString()
        backButton.text = "−" + seconds + "초"; forwardButton.text = "+" + seconds + "초"
    }

    private fun updateLabels() {
        playlistStatus.text = if (playlist.isEmpty()) "목록 없음" else (currentIndex + 1).toString() + " / " + playlist.size
        repeatStatus.text = when {
            repeatStartMs >= 0 && repeatEndMs >= 0 -> "A " + formatTime(repeatStartMs) + "  ↔  B " + formatTime(repeatEndMs)
            repeatStartMs >= 0 -> "A " + formatTime(repeatStartMs) + "  ·  B 미지정"
            else -> "A–B 꺼짐"
        }
        bookmarkStatus.text = bookmarks.mapIndexed { index, value ->
            (index + 1).toString() + " " + if (value >= 0) formatTime(value) else "--:--"
        }.joinToString("   ")
    }

    private fun fetchLatestBbcEpisode() {
        toast("BBC 최신 회차를 확인하고 있습니다.")
        Thread {
            try {
                val connection = URL(BBC_FEED).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val parser = Xml.newPullParser()
                val episode = connection.inputStream.use { parser.setInput(it, "UTF-8"); parseFirstEpisode(parser) }
                connection.disconnect()
                runOnUiThread { showLatestEpisode(episode) }
            } catch (_: Exception) { runOnUiThread { toast("BBC 정보를 불러오지 못했습니다.") } }
        }.start()
    }

    private fun parseFirstEpisode(parser: XmlPullParser): BbcEpisode {
        var inItem = false; var title = ""; var audioUrl = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                "item" -> inItem = true
                "title" -> if (inItem) title = parser.nextText()
                "enclosure" -> if (inItem) audioUrl = parser.getAttributeValue(null, "url") ?: ""
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item" && inItem) break
            parser.next()
        }
        if (title.isBlank() || audioUrl.isBlank()) error("No episode")
        return BbcEpisode(title, audioUrl.replaceFirst("http://", "https://"))
    }

    private fun showLatestEpisode(episode: BbcEpisode) {
        AlertDialog.Builder(this).setTitle("BBC 6 Minute English")
            .setMessage("최신 회차\n\n" + episode.title).setNegativeButton("취소", null)
            .setPositiveButton("MP3 다운로드") { _, _ -> downloadEpisode(episode) }.show()
    }

    private fun downloadEpisode(episode: BbcEpisode) {
        val fileName = episode.title.replace(Regex("[^A-Za-z0-9가-힣 _-]"), "").take(80).ifBlank { "BBC_6_Minute_English" } + ".mp3"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) downloadToMediaStore(episode, fileName)
        else {
            val request = DownloadManager.Request(Uri.parse(episode.audioUrl)).setTitle(episode.title)
                .setMimeType("audio/mpeg").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast("다운로드를 시작했습니다.")
        }
    }

    private fun downloadToMediaStore(episode: BbcEpisode, fileName: String) {
        toast("MP3를 다운로드하고 있습니다.")
        Thread {
            var outputUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HD MP3_Player")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                outputUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("저장 위치 오류")
                val connection = URL(episode.audioUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
                connection.inputStream.use { input -> contentResolver.openOutputStream(outputUri!!)?.use { input.copyTo(it) } ?: error("파일 쓰기 오류") }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); contentResolver.update(outputUri!!, values, null, null)
                connection.disconnect()
                runOnUiThread { toast("다운로드 완료: Download/HD MP3_Player") }
            } catch (error: Exception) {
                outputUri?.let { contentResolver.delete(it, null, null) }
                runOnUiThread { toast("다운로드 실패: " + (error.message ?: "네트워크 오류")) }
            }
        }.start()
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
                player.seekTo(repeatStartMs); position = repeatStartMs
            }
            seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            seekBar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            seekBar.setRepeatRange(repeatStartMs, repeatEndMs, duration)
            timeText.text = formatTime(position) + " / " + formatTime(duration)
            handler.postDelayed(this, 250)
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000; val hours = total / 3600
        val minutes = (total % 3600) / 60; val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) prefs.edit().putLong("last_position", player.currentPosition).apply()
    }

    override fun onDestroy() { handler.removeCallbacks(progressUpdater); player.release(); super.onDestroy() }

    companion object {
        private const val BBC_FEED = "https://podcasts.files.bbci.co.uk/p02pc9tn.rss"
        private const val USER_AGENT = "HD-MP3-Player/1.0"
    }
}

private data class BbcEpisode(val title: String, val audioUrl: String)

private class SimpleItemSelectedListener(private val selected: (Int) -> Unit) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = selected(position)
    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}

