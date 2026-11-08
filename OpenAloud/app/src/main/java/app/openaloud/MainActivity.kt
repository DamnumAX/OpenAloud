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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
        Reader.onChange = null
        super.onStop()
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
        menu.add(0, 1, 0, "Открыть файл (TXT, EPUB, PDF, DOCX)")
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
            10 -> historyDialog()
            11 -> fontSizeDialog()
            12 -> themeDialog()
        }
        return true
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
            h.tv.textSize = Prefs.fontSize(this).toFloat()
            h.tv.setBackgroundColor(if (pos == Reader.index) 0x66FFC107 else Color.TRANSPARENT)
            h.tv.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) cmd(TtsService.SEEK_PLAY, p)
            }
        }
    }
}
