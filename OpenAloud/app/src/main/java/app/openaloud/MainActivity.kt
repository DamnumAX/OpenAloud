package app.openaloud

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.GestureDetector
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.graphics.drawable.BitmapDrawable
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import org.json.JSONObject
import java.io.File
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.speech.tts.TextToSpeech
import android.view.ScaleGestureDetector
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.PagerSnapHelper
import android.app.Dialog
import android.widget.ProgressBar
import android.widget.ScrollView
import com.google.android.material.appbar.MaterialToolbar
import kotlin.math.max
import kotlin.math.roundToInt

class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: Button
    private lateinit var controlBar: ViewGroup
    private lateinit var expandControls: Button
    private val adapter = SentenceAdapter()
    private var shownDoc: String? = null
    private var shownIndex = 0
    private var seeking = false
    private var voicePickerTts: TextToSpeech? = null
    private var lastTapAt = 0L
    private var lastTapPosition = RecyclerView.NO_POSITION
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var controlsExpanded = true
    private var pendingCenterTap: Runnable? = null
    private var bookPages: List<List<Int>> = emptyList()
    private var lastPageTapAt = 0L
    private var lastPageTap = RecyclerView.NO_POSITION
    private var pagerSnapHelper: PagerSnapHelper? = null


    private val importBook = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Thread {
            var imported = 0
            try {
                uris.forEach { uri ->
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Книга"
                val dir = File(filesDir, "library").apply { mkdirs() }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                var target = File(dir, safe)
                var n = 1
                while (target.exists()) { target = File(dir, n++.toString() + "_" + safe) }
                contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                val a = Prefs.library(this)
                a.put(JSONObject().put("path", target.absolutePath).put("title", name).put("folder", ""))
                Prefs.saveLibrary(this, a)
                imported++
                }
                runOnUiThread { toast("Добавлено книг: " + imported); libraryDialog() }
            } catch (e: Exception) { runOnUiThread { toast("Ошибка импорта: " + e.message) } }
        }.start()
    }

    private val chooseBackground = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("reader_bg_uri", uri.toString()).apply()
            applyReaderBackground()
        } catch (e: Exception) { toast("Не удалось установить фон: " + e.message) }
    }

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            openUri(uri)
        }
    }

    override fun onCreate(b: Bundle?) {
        applySavedTheme()
        when (Prefs.theme(this)) {
            3 -> setTheme(R.style.Theme_OpenAloud_Gold)
            4 -> setTheme(R.style.Theme_OpenAloud_Brown)
        }
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        if (getSharedPreferences("p", MODE_PRIVATE).getBoolean("reader_landscape", false)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        toolbar = findViewById(R.id.toolbar)
        toolbar.setTitleTextAppearance(this, R.style.ReaderToolbarTitle)
        toolbar.setSubtitleTextAppearance(this, R.style.ReaderToolbarSubtitle)
        setSupportActionBar(toolbar)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        configureReaderMode()
        // Disable platform scrollbar drawing: some MIUI builds crash in View.onDrawScrollBars.
        list.isVerticalScrollBarEnabled = false
        list.isHorizontalScrollBarEnabled = false
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (Prefs.fontSize(this@MainActivity) * detector.scaleFactor).roundToInt().coerceIn(14, 32)
                if (next != Prefs.fontSize(this@MainActivity)) {
                    Prefs.setFontSize(this@MainActivity, next)
                    adapter.notifyDataSetChanged()
                }
                return true
            }
        })
        list.setOnTouchListener { rv, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                touchStartX = event.x
                touchStartY = event.y
            } else if (event.action == MotionEvent.ACTION_UP) {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val bookMode = getSharedPreferences("p", MODE_PRIVATE).getBoolean("reading_mode_book", false)
                val horizontalTurn = bookMode && kotlin.math.abs(dx) > 90 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f
                val verticalTurn = !bookMode && kotlin.math.abs(dy) > 90 && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.25f
                if (horizontalTurn || verticalTurn) {
                    pageBy(if ((if (bookMode) dx else dy) < 0) 1 else -1)
                    return@setOnTouchListener true
                }
            }
            false
        }
        applyReaderBackground()
        seek = findViewById(R.id.seek)
        playBtn = findViewById(R.id.play)
        controlBar = findViewById(R.id.controlBar)
        expandControls = findViewById(R.id.expandControls)
        expandControls.setOnClickListener { setControlsExpanded(!controlsExpanded) }

        playBtn.setOnClickListener {
            if (Reader.sentences.isEmpty()) toast("Сначала откройте файл (меню ⋮)")
            else cmd(if (Reader.playing) TtsService.PAUSE else TtsService.PLAY)
        }
        findViewById<Button>(R.id.prev).setOnClickListener { cmd(TtsService.PREV) }
        findViewById<Button>(R.id.next).setOnClickListener { cmd(TtsService.NEXT) }
        findViewById<Button>(R.id.prevCh).setOnClickListener { cmd(TtsService.PREV_CH) }
        findViewById<Button>(R.id.nextCh).setOnClickListener { cmd(TtsService.NEXT_CH) }
        // Индикатор прогресса только показывает позицию озвучки.
        // Ручная прокрутка текста не меняет место чтения.
        seek.isEnabled = false

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        if (!handleIntent(intent) && Reader.docId.isEmpty()) {
            Prefs.lastUri(this)?.let { openUri(Uri.parse(it)) }   // продолжить прошлую книгу
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Reader.onChange = { refresh() }
        refresh()
    }

    override fun onStop() {
        if (Reader.docId.isNotEmpty()) Prefs.setPos(this, Reader.docId, Reader.index)
        Reader.onChange = null
        super.onStop()
    }

    override fun onDestroy() {
        voicePickerTts?.shutdown()
        voicePickerTts = null
        super.onDestroy()
    }

    // ---------- открытие текста ----------

    private fun handleIntent(i: Intent?): Boolean {
        when (i?.action) {
            Intent.ACTION_SEND -> {
                val t = i.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return false
                openText(t)
                return true
            }
            Intent.ACTION_VIEW -> {
                val u = i.data ?: return false
                openUri(u)
                return true
            }
        }
        return false
    }

    private fun openUri(uri: Uri) = load({ Loader.fromUri(this, uri) }, uri.toString())

    private fun openText(t: String) {
        if (Regex("^https?://\\S+$").matches(t)) load({ Loader.fromUrl(t) })
        else load({ Loader.fromText("Текст", t) })
    }

    private fun load(task: () -> Doc, lastUri: String? = null) {
        Thread {
            try {
                val d = task()
                runOnUiThread { applyDoc(d, lastUri) }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось открыть: ${e.message}") }
            }
        }.start()
    }

    private fun applyDoc(d: Doc, lastUri: String?) {
        if (d.sentences.isEmpty()) { toast("Текст не найден"); return }
        if (Reader.docId.isNotEmpty()) Prefs.setPos(this, Reader.docId, Reader.index)
        if (Reader.playing) cmd(TtsService.PAUSE)
        Reader.docId = d.id
        Reader.title = d.title
        Reader.sentences = d.sentences
        Reader.chapters = d.chapters
        Reader.locale = d.locale
        Reader.index = Prefs.pos(this, d.id).coerceIn(0, d.sentences.size - 1)
        Reader.playing = false
        val library = Prefs.library(this)
        for (i in 0 until library.length()) {
            val item = library.optJSONObject(i) ?: continue
            val fileId = Uri.fromFile(File(item.optString("path"))).toString()
            if (fileId == d.id || item.optString("path") == lastUri) {
                item.put("total", d.sentences.size)
                item.put("title", item.optString("title").ifBlank { d.title })
            }
        }
        Prefs.saveLibrary(this, library)
        if (lastUri != null) {
            Prefs.setLastUri(this, lastUri)
            Prefs.addHistory(this, lastUri, d.title)
        }
        Reader.changed()
    }

    // ---------- интерфейс ----------

    private fun setControlsExpanded(expanded: Boolean) {
        controlsExpanded = expanded
        controlBar.visibility = if (expanded) ViewGroup.VISIBLE else ViewGroup.GONE
        seek.visibility = if (expanded) ViewGroup.VISIBLE else ViewGroup.GONE
        expandControls.text = if (expanded) "⌄" else "⌃"
    }

    private fun readingModeDialog() {
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val isBook = prefs.getBoolean("reading_mode_book", false)
        AlertDialog.Builder(this).setTitle("Режим чтения")
            .setSingleChoiceItems(arrayOf("Свиток — вертикальная прокрутка", "Книга — перелистывание влево и вправо"), if (isBook) 1 else 0) { dialog, which ->
                prefs.edit().putBoolean("reading_mode_book", which == 1).apply()
                configureReaderMode()
                toast(if (which == 1) "Книжный режим включён" else "Режим свитка включён")
                dialog.dismiss()
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun configureReaderMode() {
        val bookMode = getSharedPreferences("p", MODE_PRIVATE).getBoolean("reading_mode_book", false)
        if (bookMode) {
            buildBookPages()
            list.setBackgroundColor(readerSurfaceColor())
            list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            if (pagerSnapHelper == null) {
                pagerSnapHelper = PagerSnapHelper().also { it.attachToRecyclerView(list) }
            }
        } else {
            list.background = null
            pagerSnapHelper?.attachToRecyclerView(null)
            pagerSnapHelper = null
            list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        }
        adapter.notifyDataSetChanged()
        if (bookMode) {
            val page = bookPages.indexOfFirst { Reader.index in it }.coerceAtLeast(0)
            list.scrollToPosition(page)
        } else if (Reader.sentences.isNotEmpty()) {
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(Reader.index, 0)
        }
    }

    // Разбиваем документ на экранные страницы по реальной ширине и высоте области чтения.
    private fun buildBookPages() {
        if (Reader.sentences.isEmpty()) { bookPages = emptyList(); return }
        val width = (list.width - (32 * resources.displayMetrics.density).roundToInt()).coerceAtLeast(240)
        val height = (list.height - (32 * resources.displayMetrics.density).roundToInt()).coerceAtLeast(300)
        val font = Prefs.fontSize(this) * resources.displayMetrics.scaledDensity
        val charsPerLine = (width / (font * 0.53f)).toInt().coerceAtLeast(18)
        val linesPerPage = (height / (font * 1.45f)).toInt().coerceAtLeast(5)
        val capacity = (charsPerLine * linesPerPage * 0.82f).toInt().coerceAtLeast(350)
        val pages = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var count = 0
        Reader.sentences.forEachIndexed { index, sentence ->
            if (current.isNotEmpty() && count + sentence.length > capacity) {
                pages.add(current)
                current = ArrayList()
                count = 0
            }
            current.add(index)
            count += sentence.length + 1
        }
        if (current.isNotEmpty()) pages.add(current)
        bookPages = pages
    }

    private fun pageBy(direction: Int) {
        val lm = list.layoutManager as LinearLayoutManager
        val current = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val target = (current + direction).coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
        list.smoothScrollToPosition(target)
    }

    private fun readerSurfaceColor(): Int {
        val value = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)
        return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun applyReaderBackground() {
        val raw = getSharedPreferences("p", MODE_PRIVATE).getString("reader_bg_uri", null)
        if (raw.isNullOrEmpty()) {
            list.background = if (getSharedPreferences("p", MODE_PRIVATE).getBoolean("reading_mode_book", false))
                android.graphics.drawable.ColorDrawable(readerSurfaceColor()) else null
            return
        }
        try { contentResolver.openInputStream(Uri.parse(raw))?.use { stream -> BitmapFactory.decodeStream(stream)?.let { list.background = BitmapDrawable(resources, it) } } } catch (_: Exception) { toast("Не удалось загрузить фон") }
    }

    private fun refresh() {
        val bookMode = getSharedPreferences("p", MODE_PRIVATE).getBoolean("reading_mode_book", false)
        if (bookMode && (shownDoc != Reader.docId || bookPages.isEmpty())) buildBookPages()
        val lm = list.layoutManager as LinearLayoutManager
        if (shownDoc != Reader.docId) {
            shownDoc = Reader.docId
            adapter.notifyDataSetChanged()
            if (bookMode) list.scrollToPosition(bookPages.indexOfFirst { Reader.index in it }.coerceAtLeast(0))
            else lm.scrollToPositionWithOffset(Reader.index, 200)
        } else if (shownIndex != Reader.index) {
            if (bookMode) {
                val page = bookPages.indexOfFirst { Reader.index in it }.coerceAtLeast(0)
                if (Reader.playing) list.scrollToPosition(page)
            } else {
                adapter.notifyItemChanged(shownIndex)
                adapter.notifyItemChanged(Reader.index)
                if (Reader.playing) lm.scrollToPositionWithOffset(Reader.index, 200)
            }
        }
        shownIndex = Reader.index
        toolbar.title = Reader.title.ifEmpty { getString(R.string.app_name) }
        val remainingChars = Reader.sentences.drop(Reader.index).sumOf { it.length }
        val charsPerSecond = (14.0 * Prefs.rate(this).coerceAtLeast(0.1f)).coerceAtLeast(1.0)
        val minutesLeft = (remainingChars / charsPerSecond / 60.0).roundToInt()
        val progressPercent = ((Reader.index.toDouble() / Reader.sentences.size.coerceAtLeast(1)) * 100).roundToInt()
        toolbar.subtitle = if (Reader.sentences.isEmpty()) "" else "$progressPercent% прочитано • осталось примерно $minutesLeft мин."
        playBtn.text = if (Reader.playing) "⏸" else "▶"
        if (!seeking) {
            seek.max = max(1, Reader.sentences.size - 1)
            seek.progress = Reader.index
        }
    }

    private fun cmd(action: String, index: Int = -1) {
        val i = Intent(this, TtsService::class.java).setAction(action)
        if (index >= 0) i.putExtra("index", index)
        if (action == TtsService.PLAY || action == TtsService.SEEK_PLAY)
            ContextCompat.startForegroundService(this, i)
        else startService(i)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Открыть файл (TXT, EPUB, FB2, PDF, DOCX)")
        menu.add(0, 2, 1, "Вставить из буфера / ссылку")
        menu.add(0, 3, 2, "Скорость и тон")
        menu.add(0, 4, 3, "Таймер сна")
        menu.add(0, 5, 4, "Словарь замен")
        menu.add(0, 6, 5, "Голос и движок (системные)")
        menu.add(0, 7, 6, "Пауза между предложениями")
        menu.add(0, 8, 7, "Добавить закладку")
        menu.add(0, 9, 8, "Мои закладки")
        menu.add(0, 10, 9, "Библиотека и история")
        menu.add(0, 11, 10, "Размер текста")
        menu.add(0, 12, 11, "Тема оформления")
        menu.add(0, 13, 12, "Автопереход к следующей книге")
        menu.add(0, 14, 13, "Выбрать голос")
        menu.add(0, 15, 14, "Поиск по тексту")
        menu.add(0, 16, 15, "Отступы текста")
        menu.add(0, 17, 16, "Установить свой фон")
        menu.add(0, 18, 17, "Убрать свой фон")
        menu.add(0, 19, 18, "Ориентация экрана")
        menu.add(0, 20, 19, "Режим чтения: книга / свиток")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> openDoc.launch(arrayOf("*/*"))
            2 -> paste()
            3 -> speedDialog()
            4 -> sleepDialog()
            5 -> dictDialog()
            6 -> try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            7 -> sentencePauseDialog()
            8 -> saveBookmark()
            9 -> bookmarksDialog()
            10 -> libraryDialog()
            11 -> fontSizeDialog()
            12 -> themeDialog()
            13 -> autoNextDialog()
            14 -> voiceDialog()
            15 -> searchDialog()
            16 -> textLayoutDialog()
            17 -> chooseBackground.launch(arrayOf("image/*"))
            18 -> { getSharedPreferences("p", MODE_PRIVATE).edit().remove("reader_bg_uri").apply(); applyReaderBackground() }
            19 -> { val land = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE; requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("reader_landscape", land).apply() }
            20 -> readingModeDialog()
        }
        return true
    }


    private fun libraryDialog() {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 12)
        }
        val title = TextView(this).apply {
            text = "Моя библиотека"
            textSize = 22f
            setPadding(4, 8, 4, 16)
        }
        root.addView(title)
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        val books = Prefs.library(this)
        if (books.length() == 0) {
            rows.addView(TextView(this).apply {
                text = "Здесь пока нет книг. Нажми «Добавить книги», чтобы импортировать файлы."
                textSize = 16f
                setPadding(12, 24, 12, 24)
            })
        }
        for (i in 0 until books.length()) {
            val book = books.optJSONObject(i) ?: continue
            val id = Uri.fromFile(File(book.optString("path"))).toString()
            val progress = Prefs.pos(this, id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }
            val name = TextView(this).apply {
                text = book.optString("title", "Книга")
                textSize = 17f
                maxLines = 2
            }
            val total = if (id == Reader.docId) Reader.sentences.size else book.optInt("total", 0)
            val percent = if (total > 0) ((progress.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
            val detail = TextView(this).apply {
                text = if (total > 0) "$percent% прочитано" else "Ещё не открывалась"
                textSize = 12f
                setPadding(0, 4, 0, 4)
            }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = percent
            }
            row.addView(name)
            row.addView(detail)
            row.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8))
            row.setOnClickListener {
                val file = File(book.optString("path"))
                if (file.exists()) { dialog.dismiss(); openUri(Uri.fromFile(file)) }
                else toast("Файл не найден")
            }
            row.setOnLongClickListener {
                val one = listOf(book)
                dialog.dismiss()
                booksDialog(one)
                true
            }
            rows.addView(row)
            val divider = View(this).apply { setBackgroundColor(0x33222222) }
            rows.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val add = Button(this).apply {
            text = "Добавить книги"
            setOnClickListener { dialog.dismiss(); importBook.launch(arrayOf("*/*")) }
        }
        val foldersButton = Button(this).apply {
            text = "Папки"
            setOnClickListener { dialog.dismiss(); foldersDialog() }
        }
        val close = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { dialog.dismiss() }
        }
        buttons.addView(add); buttons.addView(foldersButton); buttons.addView(close)
        root.addView(buttons)
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun showLibraryBooks(folder: String) {
        val a = Prefs.library(this)
        val books = ArrayList<JSONObject>()
        val labels = ArrayList<String>()
        for (i in 0 until a.length()) {
            val b = a.optJSONObject(i) ?: continue
            if (folder.isNotEmpty() && b.optString("folder") != folder) continue
            books.add(b); labels.add(b.optString("title"))
        }
        AlertDialog.Builder(this).setTitle(if (folder.isEmpty()) "Все книги" else folder)
            .setItems(labels.toTypedArray()) { _, which ->
                val file = File(books[which].optString("path"))
                if (file.exists()) openUri(Uri.fromFile(file)) else toast("Файл не найден")
            }
            .setPositiveButton("Добавить") { _, _ -> importBook.launch(arrayOf("*/*")) }
            .setNeutralButton("Управление") { _, _ -> booksDialog(books) }
            .setNegativeButton("Назад") { _, _ -> libraryDialog() }.show()
    }

    private fun booksDialog(books: List<JSONObject>) {
        AlertDialog.Builder(this).setTitle("Управление книгами")
            .setItems(books.map { it.optString("title") }.toTypedArray()) { _, which ->
                val b = books[which]
                val actions = arrayOf("Переименовать", "Переместить в папку", "Удалить из библиотеки", "Переместить выше", "Переместить ниже")
                AlertDialog.Builder(this).setTitle(b.optString("title")).setItems(actions) { _, action ->
                    when (action) {
                        0 -> {
                            val edit = EditText(this).apply { setText(b.optString("title")) }
                            AlertDialog.Builder(this).setTitle("Название книги").setView(edit)
                                .setPositiveButton("Сохранить") { _, _ ->
                                    b.put("title", edit.text.toString().trim().ifBlank { "Книга" })
                                    saveBook(b); libraryDialog()
                                }.setNegativeButton("Отмена", null).show()
                        }
                        1 -> {
                            val folders = Prefs.folders(this)
                            val choices = ArrayList<String>().apply {
                                add("Без папки")
                                for (i in 0 until folders.length()) add(folders.optString(i))
                            }
                            AlertDialog.Builder(this).setTitle("Переместить в папку")
                                .setItems(choices.toTypedArray()) { _, index ->
                                    b.put("folder", if (index == 0) "" else choices[index])
                                    saveBook(b); libraryDialog()
                                }.show()
                        }
                        3, 4 -> {
                            val all = Prefs.library(this)
                            var index = -1
                            for (i in 0 until all.length()) if (all.optJSONObject(i)?.optString("path") == b.optString("path")) { index = i; break }
                            val target = if (action == 3) index - 1 else index + 1
                            if (index >= 0 && target in 0 until all.length()) {
                                val other = all.get(target)
                                all.put(target, all.get(index))
                                all.put(index, other)
                                Prefs.saveLibrary(this, all)
                                libraryDialog()
                            } else toast("Книга уже на краю списка")
                        }
                        2 -> AlertDialog.Builder(this).setTitle("Удалить сохранённый файл?")
                            .setMessage("Копия будет удалена из памяти приложения.")
                            .setPositiveButton("Удалить") { _, _ ->
                                File(b.optString("path")).delete()
                                val all = Prefs.library(this); val out = org.json.JSONArray()
                                for (i in 0 until all.length()) {
                                    val item = all.optJSONObject(i)
                                    if (item?.optString("path") != b.optString("path")) out.put(all.get(i))
                                }
                                Prefs.saveLibrary(this, out); libraryDialog()
                            }.setNegativeButton("Отмена", null).show()
                    }
                }.show()
            }.setNegativeButton("Назад", null).show()
    }

    private fun saveBook(book: JSONObject) {
        val a = Prefs.library(this)
        for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("path") == book.optString("path")) { a.put(i, book); break }
        Prefs.saveLibrary(this, a)
    }

    private fun foldersDialog() {
        val folders = Prefs.folders(this)
        val actions = arrayOf("Создать папку", "Переименовать", "Удалить")
        AlertDialog.Builder(this).setTitle("Папки библиотеки").setItems(actions) { _, action ->
            if (action == 0) {
                val edit = EditText(this).apply { hint = "Название папки" }
                AlertDialog.Builder(this).setTitle("Новая папка").setView(edit)
                    .setPositiveButton("Создать") { _, _ ->
                        val n = edit.text.toString().trim()
                        if (n.isNotEmpty()) { folders.put(n); Prefs.saveFolders(this, folders) }
                        libraryDialog()
                    }.setNegativeButton("Отмена", null).show()
            } else if (folders.length() > 0) {
                val names = Array(folders.length()) { folders.optString(it) }
                AlertDialog.Builder(this).setItems(names) { _, index ->
                    val old = names[index]
                    if (action == 1) {
                        val edit = EditText(this).apply { setText(old) }
                        AlertDialog.Builder(this).setTitle("Новое название").setView(edit)
                            .setPositiveButton("Сохранить") { _, _ ->
                                val n = edit.text.toString().trim()
                                if (n.isNotEmpty()) {
                                    folders.put(index, n)
                                    val a = Prefs.library(this)
                                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("folder") == old) it.put("folder", n) }
                                    Prefs.saveLibrary(this, a); Prefs.saveFolders(this, folders)
                                }
                                libraryDialog()
                            }.setNegativeButton("Отмена", null).show()
                    } else {
                        folders.remove(index)
                        val a = Prefs.library(this)
                        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("folder") == old) it.put("folder", "") }
                        Prefs.saveLibrary(this, a); Prefs.saveFolders(this, folders); libraryDialog()
                    }
                }.show()
            } else toast("Папок пока нет")
        }.setNegativeButton("Готово") { _, _ -> libraryDialog() }.show()
    }

    private fun autoNextDialog() {
        val check = CheckBox(this).apply {
            text = "Открывать следующую книгу автоматически"
            isChecked = Prefs.autoNext(this@MainActivity)
        }
        AlertDialog.Builder(this).setTitle("Переход между книгами").setView(check)
            .setPositiveButton("Сохранить") { _, _ -> Prefs.setAutoNext(this, check.isChecked) }
            .setNegativeButton("Отмена", null).show()
    }


    private fun voiceDialog() {
        toast("Загружаю список голосов…")
        val engine = TextToSpeech(this) { status ->
            val activeEngine = voicePickerTts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                runOnUiThread { toast("Не удалось загрузить голоса") }
                return@TextToSpeech
            }
            val voices = activeEngine.voices?.sortedBy { it.name } ?: emptyList()
            runOnUiThread {
                if (voices.isEmpty()) {
                    toast("Системные голоса не найдены")
                    activeEngine.shutdown()
                    voicePickerTts = null
                    return@runOnUiThread
                }
                val labels = voices.map { it.name + " · " + it.locale.displayName }.toTypedArray()
                val selected = voices.indexOfFirst { it.name == Prefs.voice(this) }.coerceAtLeast(0)
                AlertDialog.Builder(this).setTitle("Голос чтения")
                    .setSingleChoiceItems(labels, selected) { dialog, which ->
                        Prefs.setVoice(this, voices[which].name)
                        activeEngine.shutdown()
                        voicePickerTts = null
                        dialog.dismiss()
                        toast("Голос выбран. Изменение применится при следующей фразе.")
                    }
                    .setNeutralButton("Автоматический") { _, _ ->
                        Prefs.setVoice(this, "")
                        activeEngine.shutdown()
                        voicePickerTts = null
                    }
                    .setNegativeButton("Закрыть") { _, _ ->
                        activeEngine.shutdown()
                        voicePickerTts = null
                    }
                    .setOnCancelListener {
                        activeEngine.shutdown()
                        voicePickerTts = null
                    }.show()
            }
        }
        voicePickerTts = engine
    }

    private fun searchDialog() {
        if (Reader.sentences.isEmpty()) { toast("Сначала откройте книгу"); return }
        val input = EditText(this).apply { hint = "Слово или фраза" }
        val dialog = AlertDialog.Builder(this).setTitle("Поиск по тексту")
            .setView(input).setPositiveButton("Найти", null)
            .setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isEmpty()) { input.error = "Введите запрос"; return@setOnClickListener }
                val matches = Reader.sentences.indices.filter { Reader.sentences[it].contains(query, true) }
                if (matches.isEmpty()) { toast("Совпадений не найдено"); return@setOnClickListener }
                val labels = matches.map { (it + 1).toString() + ". " + Reader.sentences[it].take(120) }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Найдено: " + matches.size)
                    .setItems(labels) { _, which -> jumpTo(matches[which]) }
                    .setNegativeButton("Закрыть", null).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun jumpTo(index: Int) {
        Reader.index = index.coerceIn(0, (Reader.sentences.size - 1).coerceAtLeast(0))
        if (Reader.docId.isNotEmpty()) Prefs.setPos(this, Reader.docId, Reader.index)
        Reader.changed()
    }

    private fun textLayoutDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        sliderRow(box, "Боковые отступы", 48, Prefs.sidePadding(this) / 2,
            { (it * 2).toString() + " dp" }) {
            Prefs.setSidePadding(this, it * 2)
            adapter.notifyDataSetChanged()
        }
        AlertDialog.Builder(this).setTitle("Ширина текстовой колонки")
            .setMessage("Сведите или разведите два пальца на тексте, чтобы изменить размер шрифта. Полоса прокрутки находится справа.")
            .setView(box).setPositiveButton("Готово", null).show()
    }

    private fun historyDialog() {
        val a = Prefs.history(this)
        if (a.length() == 0) {
            toast("История пока пуста")
            return
        }
        val labels = ArrayList<String>()
        for (i in 0 until a.length()) labels.add(a.optJSONObject(i)?.optString("title", "Документ") ?: "Документ")
        AlertDialog.Builder(this).setTitle("Библиотека и история")
            .setItems(labels.toTypedArray()) { _, which ->
                val item = a.optJSONObject(which) ?: return@setItems
                val id = item.optString("id")
                if (id.isNotEmpty()) openUri(Uri.parse(id))
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun fontSizeDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        sliderRow(box, "Размер текста", 18, Prefs.fontSize(this) - 14,
            { "${it + 14} sp" }) {
            Prefs.setFontSize(this, it + 14)
            adapter.notifyDataSetChanged()
        }
        AlertDialog.Builder(this).setTitle("Размер текста").setView(box)
            .setPositiveButton("Готово", null).show()
    }

    private fun themeDialog() {
        val labels = arrayOf("Как в системе", "Светлая", "Тёмная", "Золотая", "Коричневая")
        AlertDialog.Builder(this).setTitle("Тема оформления")
            .setSingleChoiceItems(labels, Prefs.theme(this)) { dialog, which ->
                Prefs.setTheme(this, which)
                applySavedTheme()
                dialog.dismiss()
                recreate()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun applySavedTheme() {
        val mode = when (Prefs.theme(this)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2, 4 -> AppCompatDelegate.MODE_NIGHT_YES
            3 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun saveBookmark() {
        if (Reader.sentences.isEmpty()) {
            toast("Сначала откройте документ")
            return
        }
        Prefs.addBookmark(this, Reader.docId, Reader.title, Reader.index)
        toast("Закладка сохранена")
    }

    private fun bookmarksDialog() {
        val a = Prefs.bookmarks(this)
        if (a.length() == 0) {
            toast("Закладок пока нет")
            return
        }
        val labels = ArrayList<String>()
        for (i in 0 until a.length()) {
            val item = a.optJSONObject(i) ?: continue
            labels.add("${item.optString("title", "Документ")} — позиция ${item.optInt("index") + 1}")
        }
        AlertDialog.Builder(this).setTitle("Закладки")
            .setItems(labels.toTypedArray()) { _, which ->
                val item = a.optJSONObject(which) ?: return@setItems
                val id = item.optString("id")
                val index = item.optInt("index").coerceAtLeast(0)
                if (id == Reader.docId) {
                    Reader.index = index.coerceIn(0, (Reader.sentences.size - 1).coerceAtLeast(0))
                    Reader.changed()
                } else if (id.startsWith("content:") || id.startsWith("text:")) {
                    toast("Эта закладка относится к тексту, который нужно открыть заново")
                } else {
                    Prefs.setPos(this, id, index)
                    openUri(Uri.parse(id))
                }
            }
            .setNeutralButton("Управление") { _, _ -> manageBookmarksDialog() }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun manageBookmarksDialog() {
        val a = Prefs.bookmarks(this)
        if (a.length() == 0) return
        val labels = ArrayList<String>()
        for (i in 0 until a.length()) {
            val item = a.optJSONObject(i) ?: continue
            labels.add("${item.optString("title", "Документ")} — ${item.optInt("index") + 1}")
        }
        AlertDialog.Builder(this).setTitle("Удалить закладку")
            .setItems(labels.toTypedArray()) { _, which ->
                Prefs.removeBookmark(this, which)
                toast("Закладка удалена")
            }.setNegativeButton("Отмена", null).show()
    }

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (t.isNullOrEmpty()) toast("Буфер обмена пуст") else openText(t)
    }

    private fun sliderRow(
        parent: LinearLayout, label: String, steps: Int, progress: Int,
        fmt: (Int) -> String, onSet: (Int) -> Unit,
    ) {
        val tv = TextView(this)
        tv.text = "$label: ${fmt(progress)}"
        val sb = SeekBar(this)
        sb.max = steps
        sb.progress = progress
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                tv.text = "$label: ${fmt(p)}"
                if (u) onSet(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(tv)
        parent.addView(sb)
    }

    private fun speedDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(48, 24, 48, 0)
        sliderRow(box, "Скорость", 25, ((Prefs.rate(this) - 0.5f) * 10).roundToInt(),
            { "%.1f×".format(0.5f + it / 10f) }) { Prefs.setRate(this, 0.5f + it / 10f) }
        sliderRow(box, "Тон", 15, ((Prefs.pitch(this) - 0.5f) * 10).roundToInt(),
            { "%.1f".format(0.5f + it / 10f) }) { Prefs.setPitch(this, 0.5f + it / 10f) }
        AlertDialog.Builder(this).setTitle("Скорость и тон").setView(box)
            .setPositiveButton("OK", null).show()
    }

    private fun sentencePauseDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val current = Prefs.sentencePause(this)
        sliderRow(box, "Пауза", 30, current / 100,
            { if (it == 0) "Без паузы" else "${it * 100} мс" }
        ) { Prefs.setSentencePause(this, it * 100) }
        AlertDialog.Builder(this).setTitle("Интервал между предложениями")
            .setMessage("Небольшая пауза делает озвучку естественнее.")
            .setView(box).setPositiveButton("Готово", null).show()
    }

    private fun sleepDialog() {
        val names = arrayOf("Выключить", "15 минут", "30 минут", "60 минут")
        val mins = intArrayOf(0, 15, 30, 60)
        AlertDialog.Builder(this).setTitle("Таймер сна").setItems(names) { _, w ->
            startService(
                Intent(this, TtsService::class.java)
                    .setAction(TtsService.SLEEP).putExtra("min", mins[w])
            )
            toast(if (mins[w] == 0) "Таймер выключен" else "Остановлю через ${mins[w]} мин")
        }.show()
    }

    private fun dictDialog() {
        val et = EditText(this)
        et.setText(Prefs.dict(this))
        et.hint = "слово=как произносить\n(по одной замене в строке)"
        et.minLines = 6
        et.gravity = android.view.Gravity.TOP
        AlertDialog.Builder(this).setTitle("Словарь замен").setView(et)
            .setPositiveButton("Сохранить") { _, _ -> Prefs.setDict(this, et.text.toString()) }
            .setNegativeButton("Отмена", null).show()
    }

    inner class SentenceAdapter : RecyclerView.Adapter<VH>() {
        private fun isBookMode() = getSharedPreferences("p", MODE_PRIVATE).getBoolean("reading_mode_book", false)
        override fun getItemCount() = if (isBookMode()) bookPages.size else Reader.sentences.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val tv = layoutInflater.inflate(R.layout.item_sentence, p, false) as TextView
            if (isBookMode()) tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return VH(tv)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val book = isBookMode()
            val indices = if (book) bookPages.getOrNull(pos).orEmpty() else listOf(pos)
            val parts = indices.map { Reader.sentences.getOrElse(it) { "" } }
            val plainText = parts.joinToString("\n\n")
            val starts = ArrayList<Int>()
            var cursor = 0
            parts.forEachIndexed { i, part ->
                starts.add(cursor)
                cursor += part.length + if (i < parts.lastIndex) 2 else 0
            }
            val styled = SpannableString(plainText)
            val activePart = indices.indexOf(Reader.index)
            if (activePart >= 0) {
                val start = starts[activePart]
                val end = start + parts[activePart].length
                styled.setSpan(BackgroundColorSpan(0x66FFC107), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            h.tv.text = styled
            h.tv.textSize = Prefs.fontSize(this@MainActivity).toFloat()
            val density = resources.displayMetrics.density
            val pad = (Prefs.sidePadding(this@MainActivity) * density).roundToInt()
            val verticalPad = ((if (book) 24 else 6) * density).roundToInt()
            h.tv.layoutParams = h.tv.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = if (book) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            h.tv.setPadding(pad, verticalPad, pad, verticalPad)
            h.tv.setTextIsSelectable(false)
            h.tv.gravity = if (book) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.TOP
            h.tv.setBackgroundColor(if (book) readerSurfaceColor() else Color.TRANSPARENT)
            val detector = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val layout = h.tv.layout ?: return true
                    val line = layout.getLineForVertical((e.y - h.tv.totalPaddingTop).toInt().coerceAtLeast(0))
                    val offset = layout.getOffsetForHorizontal(line, (e.x - h.tv.totalPaddingLeft).coerceAtLeast(0f))
                        .coerceIn(0, plainText.length)
                    val part = starts.indexOfLast { it <= offset }.coerceAtLeast(0)
                    val target = indices.getOrElse(part) { indices.firstOrNull() ?: 0 }
                    cmd(TtsService.SEEK_PLAY, target)
                    return true
                }
            })
            h.tv.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        }
    }
}
