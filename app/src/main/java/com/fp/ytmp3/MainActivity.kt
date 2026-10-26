package com.fp.ytmp3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.fp.ytmp3.plugin.ExtractorApi
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

enum class ArtMode(val labelRes: Int) {
    COVER(R.string.main_art_covers_short),
    LYRICS(R.string.main_art_lyrics_short),
    BOTH(R.string.main_art_both_short)
}

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var urlInput: MaterialAutoCompleteTextView
    private lateinit var downloadButton: Button
    private lateinit var destLabel: TextView
    private lateinit var statsTitle: TextView
    private lateinit var statCount: TextView
    private lateinit var statCurrent: TextView
    private lateinit var runProgress: LinearProgressIndicator
    private lateinit var statPresent: TextView
    private lateinit var statDownloaded: TextView
    private lateinit var statErrors: TextView
    private lateinit var trackList: RecyclerView
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var downloadSelectedButton: Button
    private lateinit var stopButton: Button
    private val items = ArrayList<TrackItem>()
    private var listEpoch = 0
    private var refreshJob: Job? = null
    private var pendingRefresh = false
    private var stopRequested = false
    private var downloading = false
    private var artJob: Job? = null
    private var downloadJob: Job? = null
    private var artMoveJob: Job? = null
    private lateinit var folderList: RecyclerView
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var searchTracks: EditText
    private lateinit var searchFolder: EditText
    private val folderItems = ArrayList<FolderItem>()
    private lateinit var btnDeleteFiles: Button
    private lateinit var btnArtCovers: Button
    private lateinit var btnArtLyrics: Button
    private lateinit var btnArtBoth: Button
    private lateinit var statPresentLabel: TextView
    private lateinit var statDownloadedLabel: TextView
    private lateinit var statErrorsLabel: TextView
    private var destUri: Uri? = null
    private var availableUpdate: GitHubRelease? = null
    private var updateCheckStarted = false

    private val prefs by lazy { getSharedPreferences("conf", MODE_PRIVATE) }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val changed = destUri?.toString() != uri.toString()
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit().putString("destUri", uri.toString()).apply()
            destUri = uri
            updateDestLabel()
            if (changed) {
                if (items.isEmpty() || downloading) pendingRefresh = true
                else refreshPresence()
            }
            log("Dossier de destination enregistre")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.init(this)
        setContentView(R.layout.activity_main)
        setupMenu()
        urlInput = findViewById(R.id.urlInput)
        refreshUrlHistory()
        urlInput.threshold = 0
        // Ne pas ouvrir le dropdown depuis onFocusChange : lors d'une restauration
        // d'activite le token de fenetre n'est pas encore valide -> BadTokenException.
        urlInput.setOnClickListener {
            if (urlInput.isAttachedToWindow && urlInput.adapter != null) {
                urlInput.showDropDown()
            }
        }
        downloadButton = findViewById(R.id.btnDownload)
        destLabel = findViewById(R.id.destLabel)
        statsTitle = findViewById(R.id.statsTitle)
        statCount = findViewById(R.id.statCount)
        statCurrent = findViewById(R.id.statCurrent)
        runProgress = findViewById(R.id.runProgress)
        statPresent = findViewById(R.id.statPresent)
        statDownloaded = findViewById(R.id.statDownloaded)
        statErrors = findViewById(R.id.statErrors)

        destUri = prefs.getString("destUri", null)?.let { Uri.parse(it) }
        updateDestLabel()
        findViewById<Button>(R.id.btnChooseFolder).setOnClickListener {
            pickFolder.launch(null)
        }

        trackList = findViewById(R.id.trackList)
        trackList.layoutManager = LinearLayoutManager(this)
        trackAdapter = TrackAdapter(items) { updateDownloadButton() }
        trackList.adapter = trackAdapter
        downloadSelectedButton = findViewById(R.id.btnDownloadSelected)
        downloadSelectedButton.setOnClickListener { downloadSelected() }
        stopButton = findViewById(R.id.btnStop)
        stopButton.setOnClickListener { requestStop() }
        findViewById<Button>(R.id.btnCheckAll).setOnClickListener { setAllChecked(true) }
        findViewById<Button>(R.id.btnUncheckAll).setOnClickListener { setAllChecked(false) }

        folderList = findViewById(R.id.folderList)
        folderList.layoutManager = LinearLayoutManager(this)
        folderAdapter = FolderAdapter(folderItems) { updateFolderButtons() }
        folderList.adapter = folderAdapter
        searchTracks = findViewById(R.id.searchTracks)
        searchTracks.onTextChanged {
            trackAdapter.query = it
            trackList.scrollToPosition(0)
            updateDownloadButton()
        }
        searchFolder = findViewById(R.id.searchFolder)
        searchFolder.onTextChanged {
            folderAdapter.query = it
            folderList.scrollToPosition(0)
            updateFolderButtons()
        }
        btnDeleteFiles = findViewById(R.id.btnDeleteFiles)
        btnDeleteFiles.setOnClickListener { deleteSelectedFiles() }
        btnArtCovers = findViewById(R.id.btnArtCovers)
        btnArtLyrics = findViewById(R.id.btnArtLyrics)
        btnArtBoth = findViewById(R.id.btnArtBoth)
        btnArtCovers.setOnClickListener { fetchArt(ArtMode.COVER) }
        btnArtLyrics.setOnClickListener { fetchArt(ArtMode.LYRICS) }
        btnArtBoth.setOnClickListener { fetchArt(ArtMode.BOTH) }
        findViewById<Button>(R.id.btnAnalyzeFolder).setOnClickListener { analyzeFolder() }
        findViewById<Button>(R.id.btnFolderCheckAll).setOnClickListener { setAllFolderChecked(true) }
        findViewById<Button>(R.id.btnFolderUncheckAll).setOnClickListener { setAllFolderChecked(false) }

        statPresentLabel = findViewById(R.id.statPresentLabel)
        statDownloadedLabel = findViewById(R.id.statDownloadedLabel)
        statErrorsLabel = findViewById(R.id.statErrorsLabel)

        downloadButton.setOnClickListener { analyze() }
        restoreSession()
        findViewById<TextView>(R.id.appTagline).text =
            getString(R.string.app_tagline_version_fmt, Updater.currentVersionName(this))
        findViewById<View>(R.id.updateBanner).setOnClickListener { installAvailableUpdate() }
        checkForAppUpdate()
    }

    override fun onPause() {
        saveSession()
        super.onPause()
    }

    /** Verifie discretement la derniere release au lancement. */
    private fun checkForAppUpdate() {
        if (updateCheckStarted) return
        updateCheckStarted = true
        scope.launch {
            try {
                val rel = withContext(Dispatchers.IO) {
                    GitHubUpdater.latest(Config.githubRepo())
                }
                if (!GitHubUpdater.isNewer(rel.version, Updater.currentVersionName(this@MainActivity))) {
                    return@launch
                }
                availableUpdate = rel
                val banner = findViewById<TextView>(R.id.updateBanner)
                banner.text = getString(R.string.update_available_fmt, rel.version)
                banner.visibility = View.VISIBLE
            } catch (_: Exception) {
                // Pas de reseau / pas encore de release : on reste silencieux.
            }
        }
    }

    private fun installAvailableUpdate() {
        val rel = availableUpdate ?: return
        val banner = findViewById<TextView>(R.id.updateBanner)
        banner.isEnabled = false
        banner.text = getString(R.string.update_downloading)
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.downloadApk(this@MainActivity, rel.apkUrl)
                }
                if (apk.length() < 1000) throw IllegalStateException("APK vide")
                val info = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                if (info?.packageName != packageName) {
                    throw IllegalStateException("APK d'une autre application")
                }
                banner.text = getString(R.string.update_install)
                Updater.install(this@MainActivity, apk)
            } catch (e: Exception) {
                banner.text = getString(
                    R.string.update_error_fmt,
                    e.message ?: e.javaClass.simpleName
                )
                banner.isEnabled = true
            }
        }
    }

    private fun setupMenu() {
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_settings -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                    R.id.action_logs -> {
                        startActivity(Intent(this, JournalActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun urlHistory(): MutableList<String> =
        (prefs.getString("urlHistory", "") ?: "")
            .split("\n").filter { it.isNotBlank() }.toMutableList()

    /** Conserve la derniere liste pour la retrouver apres un kill MIUI. */
    private fun saveSession() {
        try {
            val o = JSONObject()
            o.put("url", urlInput.text.toString())
            val mode = if (findViewById<View>(R.id.folderSection).visibility == View.VISIBLE) {
                "folder"
            } else {
                "tracks"
            }
            o.put("mode", mode)
            o.put("recursive", findViewById<CheckBox>(R.id.chkRecursive).isChecked)

            val tracks = JSONArray()
            for (t in items) {
                tracks.put(
                    JSONObject()
                        .put("title", t.title)
                        .put("web", t.webUrl)
                        .put("present", t.present)
                        .put("checked", t.checked)
                        .put("uri", t.localUri?.toString() ?: "")
                )
            }
            o.put("tracks", tracks)

            val folders = JSONArray()
            for (f in folderItems) {
                folders.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("uri", f.uri.toString())
                        .put("checked", f.checked)
                )
            }
            o.put("folders", folders)

            if (tracks.length() == 0 && folders.length() == 0) {
                prefs.edit().remove("session").apply()
            } else {
                prefs.edit().putString("session", o.toString()).apply()
            }
        } catch (e: Exception) {
            Log.e("yt-mp3", "save session", e)
        }
    }

    private fun restoreSession() {
        val raw = prefs.getString("session", null) ?: return
        try {
            val o = JSONObject(raw)
            urlInput.setText(o.optString("url", ""))

            items.clear()
            val tracks = o.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val uri = t.optString("uri", "").takeIf { it.isNotBlank() }?.let(Uri::parse)
                    items.add(
                        TrackItem(
                            title = t.optString("title"),
                            webUrl = t.optString("web"),
                            present = t.optBoolean("present"),
                            checked = t.optBoolean("checked"),
                            localUri = uri
                        )
                    )
                }
            }

            folderItems.clear()
            val folders = o.optJSONArray("folders")
            if (folders != null) {
                for (i in 0 until folders.length()) {
                    val f = folders.optJSONObject(i) ?: continue
                    val uri = f.optString("uri", "")
                    if (uri.isBlank()) continue
                    folderItems.add(
                        FolderItem(
                            f.optString("name"),
                            Uri.parse(uri),
                            f.optBoolean("checked")
                        )
                    )
                }
            }

            trackAdapter.notifyDataSetChanged()
            folderAdapter.notifyDataSetChanged()
            if (items.isEmpty() && folderItems.isEmpty()) return

            findViewById<CheckBox>(R.id.chkRecursive).isChecked = o.optBoolean("recursive")
            val folderMode = o.optString("mode") == "folder" && folderItems.isNotEmpty()
            if (folderMode) {
                findViewById<View>(R.id.selectionSection).visibility = View.GONE
                findViewById<View>(R.id.folderSection).visibility = View.VISIBLE
                setStatsLabels(
                    getString(R.string.main_files),
                    getString(R.string.main_updated),
                    getString(R.string.main_errors)
                )
                statsTitle.text = getString(R.string.main_ready)
                setCurrent(getString(R.string.main_folder_hint))
                updateStats(folderItems.size, 0, 0, folderItems.size, folderItems.size)
                statCount.text = getString(R.string.main_folder_count_fmt, folderItems.size)
                updateFolderButtons()
                // Les URI SAF ont pu changer pendant le kill : relire le dossier.
                refreshFolderList()
            } else if (items.isNotEmpty()) {
                findViewById<View>(R.id.folderSection).visibility = View.GONE
                findViewById<View>(R.id.selectionSection).visibility = View.VISIBLE
                setStatsLabels(
                    getString(R.string.main_present),
                    getString(R.string.main_added),
                    getString(R.string.main_errors)
                )
                val present = items.count { it.present }
                val toDownload = items.count { it.checked }
                statsTitle.text = getString(R.string.main_ready)
                setCurrent(getString(R.string.main_analyze_summary_fmt, toDownload, present))
                updateStats(present, 0, 0, items.size, items.size)
                updateDownloadButton()
                // Le dossier a pu changer pendant le kill : revalider localement.
                refreshPresence()
            }
            log("Liste restauree : ${items.size} piste(s), ${folderItems.size} fichier(s)")
        } catch (e: Exception) {
            Log.e("yt-mp3", "restore session", e)
            prefs.edit().remove("session").apply()
        }
    }

    private fun saveUrlToHistory(url: String) {
        val list = urlHistory()
        list.remove(url)
        list.add(0, url)
        while (list.size > 10) list.removeAt(list.size - 1)
        prefs.edit().putString("urlHistory", list.joinToString("\n")).apply()
        refreshUrlHistory()
    }

    private fun refreshUrlHistory() {
        val list = urlHistory()
        urlInput.setSimpleItems(list.toTypedArray())
        urlInput.threshold = 0
    }

    private fun log(s: String) = LogStore.add(s)

    private fun setCurrent(s: String) {
        runOnUiThread { statCurrent.text = s }
    }

    /** Pendant le travail d'un fichier, la barre doit rester visible. */
    private fun startItemProgress() {
        runOnUiThread { runProgress.isIndeterminate = true }
    }

    /** Amene la carte de progression (et son bouton Arreter) a l'ecran. */
    private fun scrollToProgress() {
        val scroll = findViewById<ScrollView>(R.id.mainScroll)
        val card = findViewById<View>(R.id.progressCard)
        scroll.post {
            val margin = (16 * resources.displayMetrics.density).toInt()
            scroll.smoothScrollTo(0, (card.top - margin).coerceAtLeast(0))
        }
    }

    private fun updateStats(present: Int, downloaded: Int, errors: Int, processed: Int, total: Int) {
        runOnUiThread {
            statPresent.text = present.toString()
            statDownloaded.text = downloaded.toString()
            statErrors.text = errors.toString()
            statCount.text = if (total > 0) "$processed / $total" else "0 / 0"
            if (total > 0) {
                runProgress.isIndeterminate = false
                runProgress.max = total
                runProgress.setProgressCompat(processed, true)
            }
        }
    }

    private fun updateDestLabel() {
        val name = destUri?.let { DocumentFile.fromTreeUri(this, it)?.name }
        destLabel.text = if (destUri != null) {
            getString(R.string.main_dest_folder_fmt, name ?: destUri.toString())
        } else {
            getString(R.string.main_default_dest)
        }
    }

    private fun needStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return false
        val ok = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
        return !ok
    }

    /**
     * Recalcule "deja present" sur le nouveau dossier de destination, sans
     * re-interroger YouTube : on reutilise la liste de pistes deja analysee.
     */
    private fun refreshPresence() {
        if (items.isEmpty() || downloading) return
        // Le panneau dossier affiche d'autres stats : ne pas les ecraser.
        if (findViewById<View>(R.id.folderSection).visibility == View.VISIBLE) return
        val epoch = listEpoch
        val target = destUri
        val snapshot = items.toList()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val files: List<Pair<String, Uri>>? = withContext(Dispatchers.IO) {
                    if (target != null && DocumentFile.fromTreeUri(this@MainActivity, target) == null) {
                        null
                    } else {
                        MediaStoreUtil.listAudio(this@MainActivity, target, true)
                    }
                }
                if (files == null) {
                    setCurrent(getString(R.string.main_error_fmt, "dossier inaccessible"))
                    return@launch
                }
                val fingerprints = withContext(Dispatchers.Default) {
                    files.map { NameMatcher.prepare(listOf(it.first)).first() }
                }
                val uris = withContext(Dispatchers.Default) {
                    snapshot.map { item ->
                        val index = NameMatcher.indexOfMatch(item.title, fingerprints)
                        if (index >= 0) files[index].second else null
                    }
                }
                if (epoch != listEpoch) return@launch
                var present = 0
                for ((index, item) in snapshot.withIndex()) {
                    val uri = uris[index]
                    item.present = uri != null
                    item.localUri = uri
                    item.checked = uri == null
                    if (uri != null) present++
                }
                trackAdapter.notifyDataSetChanged()
                val toDownload = items.count { it.checked }
                statsTitle.text = getString(R.string.main_ready)
                setCurrent(getString(R.string.main_analyze_summary_fmt, toDownload, present))
                updateStats(present, 0, 0, items.size, items.size)
                updateDownloadButton()
                log(getString(R.string.log_analysis_fmt, items.size, present, toDownload))
                saveSession()
                if (pendingRefresh) {
                    pendingRefresh = false
                    refreshPresence()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("yt-mp3", "refresh presence", e)
            }
        }
    }

    private fun analyze() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            log(getString(R.string.main_url_empty))
            return
        }
        saveUrlToHistory(url)

        if (needStoragePermission()) {
            log(getString(R.string.main_storage))
            return
        }

        urlInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)

        downloadButton.isEnabled = false
        downloadSelectedButton.isEnabled = false
        stopButton.visibility = View.GONE
        statsTitle.text = getString(R.string.main_analyzing)
        setCurrent(getString(R.string.main_reading_playlist))
        runProgress.isIndeterminate = true
        updateStats(0, 0, 0, 0, 0)
        listEpoch++
        refreshJob?.cancel()
        searchTracks.setText("")
        items.clear()
        trackAdapter.notifyDataSetChanged()
        findViewById<View>(R.id.selectionSection).visibility = View.GONE
        findViewById<View>(R.id.folderSection).visibility = View.GONE
        setStatsLabels(getString(R.string.main_present), getString(R.string.main_added), getString(R.string.main_errors))
        scrollToProgress()

        scope.launch {
            try {
                log("--- $url")
                val entries = withContext(Dispatchers.IO) { JSONArray(ExtractorApi.list(url)) }
                val total = entries.length()
                val existing = withContext(Dispatchers.IO) {
                    MediaStoreUtil.listAudio(this@MainActivity, destUri, true)
                }
                val fingerprints = withContext(Dispatchers.Default) {
                    existing.map { NameMatcher.prepare(listOf(it.first)).first() }
                }

                var present = 0
                for (i in 0 until total) {
                    val o = entries.getJSONObject(i)
                    val title = o.optString("title")
                    val web = o.optString("url")
                    if (title.isBlank() || web.isBlank()) continue
                    val index = NameMatcher.indexOfMatch(title, fingerprints)
                    val isPresent = index >= 0
                    val localUri = if (isPresent) existing[index].second else null
                    if (isPresent) present++
                    items.add(TrackItem(title, web, isPresent, !isPresent, localUri))
                }

                trackAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.selectionSection).visibility = View.VISIBLE
                statsTitle.text = getString(R.string.main_ready)
                val toDownload = items.count { it.checked }
                setCurrent(getString(R.string.main_analyze_summary_fmt, toDownload, present))
                updateStats(present, 0, 0, items.size, items.size)
                updateDownloadButton()
                log(getString(R.string.log_analysis_fmt, items.size, present, toDownload))
                saveSession()
            } catch (e: Exception) {
                Log.e("yt-mp3", "analyze", e)
                statsTitle.text = getString(R.string.main_error)
                setCurrent(getString(R.string.main_error_fmt, "${e.javaClass.simpleName}: ${e.message}"))
                log(getString(R.string.main_error_fmt, "${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                downloadButton.isEnabled = true
                runProgress.isIndeterminate = false
            }
        }
    }

    private fun downloadSelected() {
        val selected = trackAdapter.visibleItems().filter { it.checked }
        if (selected.isEmpty()) return

        stopRequested = false
        downloading = true
        ensureNotifPermission()
        Notify.start(this, getString(R.string.notif_download), getString(R.string.notif_preparing))
        setStatsLabels(getString(R.string.main_present), getString(R.string.main_added), getString(R.string.main_errors))
        findViewById<View>(R.id.folderSection).visibility = View.GONE
        downloadSelectedButton.isEnabled = false
        downloadButton.isEnabled = false
        stopButton.isEnabled = true
        stopButton.visibility = View.VISIBLE
        statsTitle.text = getString(R.string.main_running)
        var errors = 0
        var done = 0
        updateStats(items.count { it.present }, 0, 0, 0, selected.size)
        scrollToProgress()

        downloadJob = scope.launch {
            try {
                for ((index, item) in selected.withIndex()) {
                    if (stopRequested) break
                    startItemProgress()
                    setCurrent(getString(R.string.main_downloading_fmt, item.title))
                    try {
                        val where = downloadOne(item.webUrl)
                        item.present = true
                        item.checked = false
                        item.localUri = runCatching { Uri.parse(where) }.getOrNull()
                        done++
                        trackAdapter.notifyDataSetChanged()
                    } catch (e: CancellationException) {
                        break
                    } catch (e: Exception) {
                        errors++
                        Log.e("yt-mp3", "download item", e)
                        log("  echec : ${e.javaClass.simpleName}: ${e.message}")
                    }
                    updateStats(items.count { it.present }, done, errors, index + 1, selected.size)
                    updateDownloadButton()
                }

                statsTitle.text = if (stopRequested) getString(R.string.main_stopped) else getString(R.string.main_done)
                val present = items.count { it.present }
                setCurrent(getString(R.string.main_done_fmt, done, present, errors))
                log(getString(R.string.log_done_fmt, done, present, errors))
            } catch (e: Exception) {
                Log.e("yt-mp3", "download", e)
                statsTitle.text = getString(R.string.main_error)
                setCurrent(getString(R.string.main_error_fmt, "${e.javaClass.simpleName}: ${e.message}"))
                log(getString(R.string.main_error_fmt, "${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                downloading = false
                stopRequested = false
                downloadJob = null
                downloadButton.isEnabled = true
                runProgress.isIndeterminate = false
                stopButton.visibility = View.GONE
                stopButton.isEnabled = true
                updateDownloadButton()
                updateFolderButtons()
                saveSession()
                File(cacheDir, "work").deleteRecursively()
                Notify.stop(this@MainActivity)
                if (pendingRefresh) {
                    pendingRefresh = false
                    refreshPresence()
                }
            }
        }
    }

    private fun requestStop() {
        stopRequested = true
        artJob?.cancel()
        artMoveJob?.cancel()
        downloadJob?.cancel()
        stopButton.isEnabled = false
        setCurrent(getString(R.string.main_stop_requested))
    }

    private fun updateDownloadButton() {
        val n = trackAdapter.visibleItems().count { it.checked }
        downloadSelectedButton.text = getString(R.string.main_download_fmt, n)
        downloadSelectedButton.isEnabled = n > 0 && !downloading
        if (downloading) stopButton.visibility = View.VISIBLE
    }

    private fun setAllChecked(checked: Boolean) {
        trackAdapter.visibleItems().forEach { it.checked = checked }
        trackAdapter.notifyDataSetChanged()
        updateDownloadButton()
    }

    private fun setAllFolderChecked(checked: Boolean) {
        folderAdapter.visibleItems().forEach { it.checked = checked }
        folderAdapter.notifyDataSetChanged()
        updateFolderButtons()
    }

    private fun updateFolderButtons() {
        val n = folderAdapter.visibleItems().count { it.checked }
        btnDeleteFiles.isEnabled = n > 0 && !downloading
        btnArtCovers.isEnabled = n > 0 && !downloading
        btnArtLyrics.isEnabled = n > 0 && !downloading
        btnArtBoth.isEnabled = n > 0 && !downloading
    }

    private fun setStatsLabels(a: String, b: String, c: String) {
        statPresentLabel.text = a
        statDownloadedLabel.text = b
        statErrorsLabel.text = c
    }

    private fun deleteSelectedFiles() {
        val selected = folderAdapter.visibleItems().filter { it.checked }
        if (selected.isEmpty()) {
            setCurrent(getString(R.string.delete_none))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message_fmt, selected.size))
            .setNegativeButton(R.string.delete_cancel, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ -> doDelete(selected) }
            .show()
    }

    private fun doDelete(selected: List<FolderItem>) {
        scope.launch {
            var deleted = 0
            var errors = 0
            for (item in selected) {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        if (DocumentsContract.isDocumentUri(this@MainActivity, item.uri)) {
                            DocumentsContract.deleteDocument(contentResolver, item.uri)
                        } else {
                            contentResolver.delete(item.uri, null, null) > 0
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
                if (ok) {
                    deleted++
                    folderItems.remove(item)
                } else {
                    errors++
                }
            }
            folderAdapter.notifyDataSetChanged()
            val remaining = folderItems.size
            updateStats(remaining, 0, errors, remaining, remaining)
            setCurrent(getString(R.string.delete_done_fmt, deleted, errors))
            log(getString(R.string.delete_done_fmt, deleted, errors))
            updateFolderButtons()
            saveSession()
        }
    }

    /** Recharge la liste apres un renommage/deplacement. */
    private fun refreshFolderList() {
        val recursive = findViewById<CheckBox>(R.id.chkRecursive).isChecked
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    MediaStoreUtil.listAudio(this@MainActivity, destUri, recursive)
                }
                folderItems.clear()
                files.forEach { folderItems.add(FolderItem(it.first, it.second, false)) }
                folderAdapter.notifyDataSetChanged()
                updateFolderButtons()
                saveSession()
            } catch (_: Exception) {
            }
        }
    }

    private fun analyzeFolder() {
        scope.launch {
            try {
                downloadButton.isEnabled = false
                findViewById<Button>(R.id.btnAnalyzeFolder).isEnabled = false
                searchFolder.setText("")
                statsTitle.text = getString(R.string.main_folder)
                setCurrent(getString(R.string.main_reading_folder))
                runProgress.isIndeterminate = true
                scrollToProgress()
                val files = withContext(Dispatchers.IO) {
                    MediaStoreUtil.listAudio(
                        this@MainActivity, destUri,
                        findViewById<CheckBox>(R.id.chkRecursive).isChecked
                    )
                }
                folderItems.clear()
                files.forEach { folderItems.add(FolderItem(it.first, it.second, false)) }
                folderAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.folderSection).visibility = View.VISIBLE
                findViewById<View>(R.id.selectionSection).visibility = View.GONE
                setStatsLabels(getString(R.string.main_files), getString(R.string.main_updated), getString(R.string.main_errors))
                statCount.text = getString(R.string.main_folder_count_fmt, files.size)
                updateStats(files.size, 0, 0, 0, files.size)
                updateFolderButtons()
                setCurrent(getString(R.string.main_folder_hint))
                log(getString(R.string.log_folder_fmt, files.size))
                saveSession()
            } catch (e: Exception) {
                Log.e("yt-mp3", "analyze folder", e)
                setCurrent(getString(R.string.main_error_fmt, "${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                downloadButton.isEnabled = true
                findViewById<Button>(R.id.btnAnalyzeFolder).isEnabled = true
                runProgress.isIndeterminate = false
            }
        }
    }

    private fun fetchArt(mode: ArtMode) {
        val selected = folderItems.filter { it.checked }
        if (selected.isEmpty()) return

        val covers = mode != ArtMode.LYRICS
        val lyrics = mode != ArtMode.COVER

        log("${getString(mode.labelRes)} : ${selected.size} fichier(s)")
        stopRequested = false
        downloading = true
        ensureNotifPermission()
        Notify.start(this, getString(R.string.notif_scan), getString(R.string.notif_preparing))
        updateFolderButtons()
        stopButton.visibility = View.VISIBLE
        stopButton.isEnabled = true
        statsTitle.text = getString(mode.labelRes)
        var updated = 0
        var errors = 0
        updateStats(selected.size, 0, 0, 0, selected.size)
        scrollToProgress()

        artJob = scope.launch {
            try {
                for ((index, item) in selected.withIndex()) {
                    if (stopRequested) break
                    startItemProgress()
                    Notify.update(
                        this@MainActivity, getString(R.string.notif_scan),
                        "${index + 1}/${selected.size} · ${item.name}",
                        index * 100 / selected.size, false
                    )
                    setCurrent(getString(R.string.main_art_working_fmt, getString(mode.labelRes), item.name))
                    try {
                        log("  ${item.name} : lecture")
                        val local = withContext(Dispatchers.IO) {
                            SafTagger.read(this@MainActivity, item.uri, item.name, cacheDir)
                        } ?: throw IllegalStateException("lecture du fichier impossible")

                        log("  ${item.name} : metadonnees")
                        // Le nom du fichier est un indice fort : un tag peut etre faux
                        // (ex. UNCLEFTY) alors que le fichier est bien nomme.
                        val fileParts = SafTagger.parseName(item.name)
                        val hintArtist = fileParts?.second?.takeIf {
                            it.isNotBlank() && !it.equals(local.artist, ignoreCase = true)
                        }
                        val hintTitle = fileParts?.third
                        val enriched = withContext(Dispatchers.IO) {
                            MusicBrainz.resolve(
                                this@MainActivity, local.title, local.artist,
                                local.year, local.duration, covers, lyrics,
                                nameArtist = hintArtist, nameTitle = hintTitle
                            ) { stopRequested }
                        }
                        val meta = enriched.meta
                        val cover = enriched.cover
                        val text = enriched.lyrics
                        if (covers) log("  ${item.name} : pochette")
                        if (lyrics) log("  ${item.name} : paroles")

                        val newSegments = if (meta.matched) {
                            RenamePattern.render(Config.renamePattern(prefs), meta)
                        } else null
                        log("  ${item.name} : ecriture")
                        val ok = withContext(Dispatchers.IO) {
                            SafTagger.write(
                                this@MainActivity, item.uri, cacheDir,
                                if (meta.matched) meta else null, cover, text
                            )
                        }
                        if (ok) {
                            CoverLoader.evict(item.uri)
                            if (newSegments != null) {
                                log("  ${item.name} : renommage")
                                val newUri = moveWithTimeout(item, newSegments)
                                if (newUri != null) {
                                    log("  -> " + newSegments.joinToString("/"))
                                    items.filter { it.localUri == item.uri }
                                        .forEach { it.localUri = newUri }
                                }
                            }
                            updated++
                            log("  ${item.name} : fait")
                        } else errors++
                    } catch (e: CancellationException) {
                        break
                    } catch (e: Exception) {
                        errors++
                        Log.e("yt-mp3", "art", e)
                        log("  " + getString(R.string.log_item_failed_fmt, item.name, e.javaClass.simpleName))
                    }
                    updateStats(selected.size, updated, errors, index + 1, selected.size)
                    Notify.update(
                        this@MainActivity, getString(R.string.notif_scan),
                        "${index + 1}/${selected.size} · ${item.name}",
                        (index + 1) * 100 / selected.size, false
                    )
                }
                statsTitle.text = if (stopRequested) getString(R.string.main_stopped) else getString(R.string.main_done)
                setCurrent(getString(R.string.main_folder_done_fmt, updated, errors))
                log(getString(R.string.log_art_fmt, getString(mode.labelRes), updated, errors))
                // Si un renommage SAF est encore en cours, attendre sa fin avant de
                // relire la liste : sinon elle garde l'ancien URI/nom.
                val pendingMove = artMoveJob?.isCompleted == false
                if (pendingMove) {
                    artMoveJob?.invokeOnCompletion {
                        scope.launch { refreshFolderList() }
                    }
                } else {
                    refreshFolderList()
                }
            } catch (e: Exception) {
                Log.e("yt-mp3", "fetch art", e)
                statsTitle.text = getString(R.string.main_error)
                setCurrent(getString(R.string.main_error_fmt, "${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                downloading = false
                stopRequested = false
                artJob = null
                runProgress.isIndeterminate = false
                stopButton.visibility = View.GONE
                stopButton.isEnabled = true
                updateFolderButtons()
                File(cacheDir, "work").deleteRecursively()
                Notify.stop(this@MainActivity)
            }
        }
    }

    /** Le renommage SAF ne doit jamais empecher le job de se terminer. */
    private suspend fun moveWithTimeout(item: FolderItem, segments: List<String>): Uri? {
        if (artMoveJob?.isCompleted == false) {
            log("  ${item.name} : renommage ignore (autre renommage en cours)")
            return null
        }
        val tree = destUri
        val move = scope.async(Dispatchers.IO) {
            FileMover.move(this@MainActivity, tree, item.uri, segments)
        }
        artMoveJob = move
        val result = withTimeoutOrNull(30_000L) { move.await() }
        if (result == null && move.isActive) {
            move.cancel()
            log("  ${item.name} : renommage abandonne (delai 30 s)")
        }
        if (move.isCompleted) artMoveJob = null
        return result
    }

    private suspend fun downloadOne(webUrl: String): String {
        val info = withContext(Dispatchers.IO) { JSONObject(ExtractorApi.info(webUrl)) }
        var audioUrl = info.getString("url")
        val title = info.optString("title")
        val uploader = info.optString("uploader")
        val duration = info.optLong("duration")
        val uploadYear = info.optInt("uploadYear", 0)

        val work = File(cacheDir, "work").apply { mkdirs() }
        val src = File(work, "source.bin")
        setCurrent(getString(R.string.main_downloading_fmt, title))
        Notify.update(this@MainActivity, getString(R.string.notif_download), title, 0, false)
        log("  " + getString(R.string.log_download_step))
        var attempt = 0
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    val lock = Any()
                    var lastShown = -2
                    Http.downloadParallel(
                        audioUrl, src, parts = 6,
                        shouldStop = { stopRequested }
                    ) { pct ->
                        if (stopRequested) throw CancellationException("arrêt")
                        val p = pct.toInt()
                        synchronized(lock) {
                            if (p < 0) {
                                if (lastShown != -2) {
                                    lastShown = -2
                                    setCurrent(getString(R.string.main_downloading_fmt, title))
                                    Notify.update(
                                        this@MainActivity, getString(R.string.notif_download),
                                        title, -1, true
                                    )
                                }
                            } else if (p > lastShown) {
                                lastShown = p
                                setCurrent(getString(R.string.main_download_pct_fmt, p, title))
                                Notify.update(
                                    this@MainActivity, getString(R.string.notif_download),
                                    title, p, false
                                )
                            }
                        }
                    }
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= 2) throw e
                attempt++
                log("  reprise téléchargement ($attempt/2) : ${e.javaClass.simpleName}")
                delay(1200)
                audioUrl = withContext(Dispatchers.IO) {
                    runCatching {
                        JSONObject(ExtractorApi.info(webUrl)).getString("url")
                    }.getOrElse { audioUrl }
                }
            }
        }

        val mp3 = File(work, "out.mp3")
        setCurrent(getString(R.string.main_converting_fmt, title))
        Notify.update(
            this@MainActivity, getString(R.string.notif_download),
            getString(R.string.notif_converting) + " · " + title, 0, false
        )
        log("  " + getString(R.string.log_convert_step) + " ...")
        withContext(Dispatchers.IO) {
            val lock = Any()
            var lastShown = -1
            AudioConverter.toMp3(src, mp3, shouldStop = { stopRequested }) { pct ->
                synchronized(lock) {
                    if (pct > lastShown) {
                        lastShown = pct
                        setCurrent(getString(R.string.main_convert_pct_fmt, pct, title))
                        Notify.update(this@MainActivity, getString(R.string.notif_download), title, pct, false)
                    }
                }
            }
        }

        if (stopRequested) throw CancellationException("arrêt")

        setCurrent(getString(R.string.main_tagging_fmt, title))
        Notify.update(
            this@MainActivity, getString(R.string.notif_download),
            getString(R.string.notif_tagging) + " · " + title, -1, true
        )
        log("  " + getString(R.string.log_tags_step))
        val enriched = withContext(Dispatchers.IO) {
            MusicBrainz.resolve(
                this@MainActivity, title, uploader, uploadYear, duration,
                withCover = true, withLyrics = true
            ) { stopRequested }
        }
        val meta = enriched.meta
        val where = withContext(Dispatchers.IO) {
            Tagger.tagAndSave(
                this@MainActivity, mp3, meta, enriched.cover, enriched.lyrics, destUri,
                Config.renamePattern(prefs)
            )
        }
        setCurrent(getString(R.string.main_saved_fmt, meta.title))
        Notify.update(this@MainActivity, getString(R.string.notif_download), title, 100, false)
        log("  -> $where")
        src.delete()
        mp3.delete()
        return where
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun EditText.onTextChanged(action: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                action(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }
}
