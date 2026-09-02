package com.example.bbcplayer

import android.app.DownloadManager
import android.content.ContentValues
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import android.util.Xml
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playButton: Button
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var seekBar: RepeatSeekBar
    private lateinit var timeText: TextView
    private lateinit var fileName: TextView
    private lateinit var repeatStatus: TextView
    private lateinit var bookmarkStatus: TextView
    private lateinit var bookmarkButtons: List<Button>
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bbc_player", MODE_PRIVATE) }
    private var currentUri: Uri? = null
    private val folderTracks = mutableListOf<Pair<Uri, String>>()
    private var currentTrackIndex = 0
    private val bookmarks = LongArray(3) { -1L }
    private var seekStepMs = 10_000L
    private var repeatStartMs = -1L
    private var repeatEndMs = -1L
    private var repeatMode = 0
    private var repeatLimit = 1
    private var fileRepeatsDone = 0

    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showFolderPicker() else toast("오디오 파일을 보려면 음악 접근 권한이 필요합니다.")
    }

    private fun openAudio(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: Exception) {}
        currentUri = uri
        clearFileLearningState()
        loadCurrent(0L)
    }

    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun openAudioLibrary() {
        val permission = audioPermission()
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) showFolderPicker()
        else requestAudioPermission.launch(permission)
    }

    private fun readAudioLibrary(): Map<String, List<Pair<Uri, String>>> {
        val projection = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) projection += MediaStore.Audio.Media.RELATIVE_PATH
        else projection += MediaStore.Audio.Media.DATA
        val groups = linkedMapOf<String, MutableList<Pair<Uri, String>>>()
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection.toTypedArray(), null, null, MediaStore.Audio.Media.DISPLAY_NAME + " COLLATE NOCASE")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "오디오"
                val rawPath = cursor.getString(2) ?: "기타"
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rawPath.trimEnd('/').ifBlank { "기타" }
                else java.io.File(rawPath).parentFile?.name ?: "기타"
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                groups.getOrPut(folder) { mutableListOf() }.add(uri to name)
            }
        }
        return groups
    }

    private fun showFolderPicker() {
        val library = readAudioLibrary()
        if (library.isEmpty()) { toast("기기에서 오디오 파일을 찾지 못했습니다."); return }
        val folders = library.keys.sorted()
        AlertDialog.Builder(this)
            .setTitle("🎧  오디오 폴더")
            .setMessage("재생할 파일이 들어 있는 폴더를 선택하세요.")
            .setItems(folders.toTypedArray()) { _, index -> showTrackPicker(folders[index], library.getValue(folders[index])) }
            .setNegativeButton("취소", null).show()
    }

    private fun showTrackPicker(folder: String, tracks: List<Pair<Uri, String>>) {
        var selected = 0
        val labels = tracks.mapIndexed { index, track -> String.format("%02d   %s", index + 1, track.second) }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("🎵  재생할 오디오")
            .setMessage(folder + "  ·  " + tracks.size + "곡")
            .setSingleChoiceItems(labels, selected) { _, index -> selected = index }
            .setNegativeButton("취소", null)
            .setPositiveButton("재생", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                folderTracks.clear(); folderTracks.addAll(tracks)
                currentTrackIndex = selected
                prefs.edit().putString("folder_path", folder).apply()
                openAudio(folderTracks[selected].first)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun restoreFolder(folder: String) {
        val tracks = readAudioLibrary()[folder] ?: return
        folderTracks.clear(); folderTracks.addAll(tracks)
        val saved = prefs.getString("last_uri", null)
        currentTrackIndex = folderTracks.indexOfFirst { it.first.toString() == saved }.coerceAtLeast(0)
        currentUri = folderTracks[currentTrackIndex].first
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
        if (currentUri != null) loadCurrent(prefs.getLong("last_position", 0L))
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
        repeatStatus = findViewById(R.id.repeatStatus)
        bookmarkStatus = findViewById(R.id.bookmarkStatus)
        bookmarkButtons = listOf(findViewById(R.id.bookmark1Button), findViewById(R.id.bookmark2Button), findViewById(R.id.bookmark3Button))
    }

    private fun restoreState() {
        seekStepMs = prefs.getLong("seek_step", 10_000L)
        repeatStartMs = prefs.getLong("repeat_start", -1L)
        repeatEndMs = prefs.getLong("repeat_end", -1L)
        repeatMode = prefs.getInt("repeat_mode", 0)
        repeatLimit = prefs.getInt("repeat_limit", 1)
        repeat(3) { bookmarks[it] = prefs.getLong("bookmark_" + it, -1L) }
        if (ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED) {
            prefs.getString("folder_path", null)?.let { runCatching { restoreFolder(it) } }
        }
        if (currentUri == null) prefs.getString("last_uri", null)?.let { currentUri = Uri.parse(it) }
        updateLabels()
        updateSeekButtonLabels()
    }

    private fun configurePlaybackControls() {
        findViewById<Button>(R.id.openButton).setOnClickListener { openAudioLibrary() }
        findViewById<Button>(R.id.previousTrackButton).setOnClickListener { moveTrack(-1) }
        findViewById<Button>(R.id.nextTrackButton).setOnClickListener { moveTrack(1) }
        playButton.setOnClickListener {
            if (currentUri == null) toast("먼저 오디오 파일을 열어 주세요.")
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
        val modes = listOf("반복 끔", "파일 반복")
        repeatMode = repeatMode.coerceIn(0, 1)
        setSpinner(R.id.repeatModeSpinner, modes, modes[repeatMode]) { value ->
            repeatMode = modes.indexOf(value)
            resetRepeatCounters()
            prefs.edit().putInt("repeat_mode", repeatMode).apply()
        }
        val counts = listOf("1회", "3회", "5회", "10회", "무제한")
        val selectedCount = if (repeatLimit < 0) "무제한" else repeatLimit.toString() + "회"
        setSpinner(R.id.repeatCountSpinner, counts, selectedCount) { value ->
            repeatLimit = if (value == "무제한") -1 else value.removeSuffix("회").toInt()
            resetRepeatCounters()
            prefs.edit().putInt("repeat_limit", repeatLimit).apply()
        }
    }

    private fun setSpinner(id: Int, values: List<String>, selected: String, changed: (String) -> Unit) {
        val button = findViewById<Button>(id)
        var selectedIndex = values.indexOf(selected).let { if (it >= 0) it else 0 }
        fun update() { button.text = values[selectedIndex] + "  ▾" }
        update()
        button.setOnClickListener {
            AlertDialog.Builder(this).setSingleChoiceItems(values.toTypedArray(), selectedIndex) { dialog, index ->
                selectedIndex = index
                changed(values[index])
                update()
                dialog.dismiss()
            }.show()
        }
    }

    private fun moveTrack(direction: Int) {
        if (folderTracks.isEmpty()) { toast("먼저 오디오 폴더를 열어 주세요."); return }
        val next = currentTrackIndex + direction
        if (next !in folderTracks.indices) { toast(if (direction < 0) "폴더의 첫 곡입니다." else "폴더의 마지막 곡입니다."); return }
        currentTrackIndex = next
        currentUri = folderTracks[next].first
        resetRepeatCounters(); clearFileLearningState(); loadCurrent(0L, true)
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
        bookmarkButtons.forEachIndexed { slot, button ->
            button.setOnClickListener {
                if (bookmarks[slot] >= 0) player.seekTo(bookmarks[slot])
                else toast((slot + 1).toString() + "번이 비어 있습니다. 길게 눌러 저장하세요.")
            }
            button.setOnLongClickListener {
                val removing = bookmarks[slot] >= 0
                bookmarks[slot] = if (removing) -1L else player.currentPosition.coerceAtLeast(0)
                prefs.edit().putLong("bookmark_" + slot, bookmarks[slot]).apply()
                updateLabels()
                toast((slot + 1).toString() + if (removing) "번 즐겨찾기를 해제했습니다." else "번 위치에 저장했습니다.")
                true
            }
        }
    }

    private fun handlePlaybackEnded() {
        when (repeatMode) {
            1 -> if (repeatLimit < 0 || fileRepeatsDone < repeatLimit) {
                fileRepeatsDone++; player.seekTo(0); player.play()
            } else fileRepeatsDone = 0
        }
    }

    private fun loadCurrent(position: Long, autoPlay: Boolean = false) {
        val uri = currentUri ?: return
        player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.seekTo(position)
        if (autoPlay) player.play()
        fileName.text = getDisplayName(uri)
        prefs.edit().putString("last_uri", uri.toString()).apply()
        updateLabels()
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

    private fun resetRepeatCounters() { fileRepeatsDone = 0 }

    private fun updateSeekButtonLabels() {
        val seconds = (seekStepMs / 1000).toString()
        backButton.text = "−" + seconds + "초"; forwardButton.text = "+" + seconds + "초"
    }

    private fun updateLabels() {
        repeatStatus.text = when {
            repeatStartMs >= 0 && repeatEndMs >= 0 -> "A " + formatTime(repeatStartMs) + "  ↔  B " + formatTime(repeatEndMs)
            repeatStartMs >= 0 -> "A " + formatTime(repeatStartMs) + "  ·  B 미지정"
            else -> "꺼짐"
        }
        bookmarkStatus.text = bookmarks.mapIndexed { index, value ->
            (index + 1).toString() + " " + if (value >= 0) formatTime(value) else "--:--"
        }.joinToString("   ")
        bookmarkButtons.forEachIndexed { index, button ->
            val active = bookmarks[index] >= 0
            button.text = (index + 1).toString() + "  " + if (active) formatTime(bookmarks[index]) else "--:--"
            button.backgroundTintList = ColorStateList.valueOf(getColor(if (active) R.color.favorite_active else R.color.surface_border))
            button.setTextColor(getColor(if (active) android.R.color.white else R.color.text_primary))
        }
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
        var inItem = false; var title = ""; var audioUrl = ""; var published = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                "item" -> inItem = true
                "title" -> if (inItem) title = parser.nextText()
                "pubDate" -> if (inItem) published = parser.nextText()
                "enclosure" -> if (inItem) audioUrl = parser.getAttributeValue(null, "url") ?: ""
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item" && inItem) break
            parser.next()
        }
        if (title.isBlank() || audioUrl.isBlank()) error("No episode")
        val inputDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(published)
            ?: error("게시일 없음")
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(inputDate)
        return BbcEpisode(title, audioUrl.replaceFirst("http://", "https://"), date)
    }

    private fun showLatestEpisode(episode: BbcEpisode) {
        AlertDialog.Builder(this).setTitle("BBC 6 Minute English")
            .setMessage("최신 회차\n\n" + episode.title).setNegativeButton("취소", null)
            .setPositiveButton("MP3 다운로드") { _, _ -> downloadEpisode(episode) }.show()
    }

    private fun downloadEpisode(episode: BbcEpisode) {
        val title = episode.title.replace(Regex("[^A-Za-z0-9가-힣 _-]"), "").trim().take(80).ifBlank { "BBC_6_Minute_English" }
        val fileName = episode.publishedDate + "_" + title + ".mp3"
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
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                outputUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("저장 위치 오류")
                val connection = openBbcAudioConnection(episode.audioUrl)
                if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
                connection.inputStream.use { input -> contentResolver.openOutputStream(outputUri!!, "w")?.use { input.copyTo(it) } ?: error("파일 쓰기 오류") }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); contentResolver.update(outputUri!!, values, null, null)
                connection.disconnect()
                runOnUiThread { toast("다운로드 완료: Download/" + fileName) }
            } catch (error: Exception) {
                outputUri?.let { contentResolver.delete(it, null, null) }
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("BBC 다운로드 실패")
                        .setMessage((error.message ?: "네트워크 오류") + "\n\n인터넷 연결 후 다시 시도해 주세요.")
                        .setPositiveButton("확인", null).show()
                }
            }
        }.start()
    }

    private fun openBbcAudioConnection(source: String): HttpURLConnection {
        var current = source
        repeat(8) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location") ?: error("BBC 리디렉션 주소 없음")
            connection.disconnect()
            current = URL(URL(current), location).toString().replaceFirst("http://", "https://")
        }
        error("BBC 리디렉션 횟수 초과")
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
            seekBar.setBookmarks(bookmarks, duration)
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

private data class BbcEpisode(val title: String, val audioUrl: String, val publishedDate: String)

