package app.openaloud

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.graphics.drawable.BitmapDrawable
import android.graphics.BitmapFactory
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
import com.google.android.material.appbar.MaterialToolbar
import kotlin.math.max
import kotlin.math.roundToInt

class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: Button
    private val adapter = SentenceAdapter()
    private var shownDoc: String? = null
    private var shownIndex = 0
    private var seeking = false
    private var voicePickerTts: TextToSpeech? = null
    private var lastTapAt = 0L
    private var lastTapPosition = RecyclerView.NO_POSITION


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
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
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
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                val now = android.os.SystemClock.uptimeMillis()
                val child = rv.findChildViewUnder(event.x, event.y)
                val position = if (child == null) RecyclerView.NO_POSITION else rv.getChildAdapterPosition(child)
                if (position != RecyclerView.NO_POSITION && position == lastTapPosition && now - lastTapAt in 1..350) {
                    cmd(TtsService.SEEK_PLAY, position)
                    lastTapAt = 0L
                    true
                } else {
                    lastTapAt = now
                    lastTapPosition = position
                    if (position == RecyclerView.NO_POSITION) {
                        if (event.x < rv.width * 0.3f) pageBy(-1)
                        else if (event.x > rv.width * 0.7f) pageBy(1)
                    }
                    false
                }
            } else false
        }
        applyReaderBackground()
        seek = findViewById(R.id.seek)
        playBtn = findViewById(R.id.play)

        playBtn.setOnClickListener {
            if (Reader.sentences.isEmpty()) toast("Сначала откройте файл (меню ⋮)")
            else cmd(if (Reader.playing) TtsService.PAUSE else TtsService.PLAY)
        }
        findViewById<Button>(R.id.prev).setOnClickListener { cmd(TtsService.PREV) }
        findViewById<Button>(R.id.next).setOnClickListener { cmd(TtsService.NEXT) }
        findViewById<Button>(R.id.prevCh).setOnClickListener { cmd(TtsService.PREV_CH) }
        findViewById<Button>(R.id.nextCh).setOnClickListener { cmd(TtsService.NEXT_CH) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                seeking = false
                cmd(TtsService.SEEK, seek.progress)
            }
        })

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
        if (lastUri != null) {
            Prefs.setLastUri(this, lastUri)
            Prefs.addHistory(this, lastUri, d.title)
        }
        Reader.changed()
    }

    // ---------- интерфейс ----------

    private fun pageBy(direction: Int) {
        val lm = list.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
        val page = (last - first + 1).coerceAtLeast(1)
        lm.scrollToPosition((first + direction * page).coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0)))
    }

    private fun applyReaderBackground() {
        val raw = getSharedPreferences("p", MODE_PRIVATE).getString("reader_bg_uri", null)
        if (raw.isNullOrEmpty()) { list.background = null; return }
        try { contentResolver.openInputStream(Uri.parse(raw))?.use { stream -> BitmapFactory.decodeStream(stream)?.let { list.background = BitmapDrawable(resources, it) } } } catch (_: Exception) { toast("Не удалось загрузить фон") }
    }

    private fun refresh() {
        val lm = list.layoutManager as LinearLayoutManager
        if (shownDoc != Reader.docId) {
            shownDoc = Reader.docId
            adapter.notifyDataSetChanged()
            lm.scrollToPositionWithOffset(Reader.index, 200)
        } else if (shownIndex != Reader.index) {
            adapter.notifyItemChanged(shownIndex)
            adapter.notifyItemChanged(Reader.index)
            if (Reader.playing) lm.scrollToPositionWithOffset(Reader.index, 200)
        }
        shownIndex = Reader.index
        toolbar.title = Reader.title.ifEmpty { getString(R.string.app_name) }
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
        }
        return true
    }


    private fun libraryDialog() {
        val folders = Prefs.folders(this)
        val names = ArrayList<String>()
        names.add("Все сохранённые книги")
        names.add("Недавно открытые")
        for (i in 0 until folders.length()) names.add("Папка: " + folders.optString(i))
        AlertDialog.Builder(this).setTitle("Моя библиотека")
            .setItems(names.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showLibraryBooks("")
                    1 -> historyDialog()
                    else -> showLibraryBooks(folders.optString(which - 2))
                }
            }
            .setPositiveButton("Добавить файл") { _, _ -> importBook.launch(arrayOf("*/*")) }
            .setNeutralButton("Управление папками") { _, _ -> foldersDialog() }
            .setNegativeButton("Закрыть", null).show()
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
                val actions = arrayOf("Переименовать", "Переместить в папку", "Удалить из библиотеки")
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
        override fun getItemCount() = Reader.sentences.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(layoutInflater.inflate(R.layout.item_sentence, p, false) as TextView)

        override fun onBindViewHolder(h: VH, pos: Int) {
            h.tv.text = Reader.sentences[pos]
            h.tv.textSize = Prefs.fontSize(this@MainActivity).toFloat()
            val pad = (Prefs.sidePadding(this@MainActivity) * resources.displayMetrics.density).roundToInt()
            h.tv.setPadding(pad, (6 * resources.displayMetrics.density).roundToInt(), pad, (6 * resources.displayMetrics.density).roundToInt())
            h.tv.setTextIsSelectable(false)
            h.tv.setBackgroundColor(if (pos == Reader.index) 0x66FFC107 else Color.TRANSPARENT)
            h.tv.setOnClickListener { /* позицию чтения меняет отдельное действие */ }
        }
    }
}
