package app.openaloud

import android.content.Context
import java.util.Locale

/** Общее состояние: текущий документ и позиция. Меняется только в главном потоке. */
object Reader {
    var docId = ""
    var title = ""
    var sentences: List<String> = emptyList()
    var chapters: List<Int> = listOf(0)   // индексы предложений, с которых начинаются главы
    var index = 0
    var playing = false
    var locale: Locale = Locale.US
    var onChange: (() -> Unit)? = null
    fun changed() { onChange?.invoke() }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)
    fun rate(c: Context) = sp(c).getFloat("rate", 1f)
    fun pitch(c: Context) = sp(c).getFloat("pitch", 1f)
    fun voice(c: Context) = sp(c).getString("tts_voice", "") ?: ""
    fun setVoice(c: Context, v: String) = sp(c).edit().putString("tts_voice", v).apply()
    fun setRate(c: Context, v: Float) = sp(c).edit().putFloat("rate", v).apply()
    fun setPitch(c: Context, v: Float) = sp(c).edit().putFloat("pitch", v).apply()
    fun sentencePause(c: Context) = sp(c).getInt("sentence_pause_ms", 250)
    fun setSentencePause(c: Context, v: Int) = sp(c).edit().putInt("sentence_pause_ms", v.coerceIn(0, 3000)).apply()
    fun dict(c: Context): String = sp(c).getString("dict", "") ?: ""
    fun setDict(c: Context, v: String) = sp(c).edit().putString("dict", v).apply()
    fun pos(c: Context, id: String) = sp(c).getInt("pos_$id", 0)
    fun setPos(c: Context, id: String, i: Int) = sp(c).edit().putInt("pos_$id", i).apply()
    fun lastUri(c: Context): String? = sp(c).getString("last", null)
    fun setLastUri(c: Context, u: String?) = sp(c).edit().putString("last", u).apply()
    fun addBookmark(c: Context, id: String, title: String, index: Int) {
        val a = org.json.JSONArray(sp(c).getString("bookmarks", "[]") ?: "[]")
        val item = org.json.JSONObject().put("id", id).put("title", title).put("index", index)
        var replaced = false
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i) ?: continue
            if (x.optString("id") == id && x.optInt("index") == index) {
                a.put(i, item); replaced = true; break
            }
        }
        if (!replaced) a.put(item)
        sp(c).edit().putString("bookmarks", a.toString()).apply()
    }
    fun bookmarks(c: Context): org.json.JSONArray =
        org.json.JSONArray(sp(c).getString("bookmarks", "[]") ?: "[]")
    fun addHistory(c: Context, id: String, title: String) {
        if (!id.startsWith("content:")) {
            val old = sp(c).getString("history", "[]") ?: "[]"
            val source = org.json.JSONArray(old)
            val out = org.json.JSONArray()
            for (i in 0 until source.length()) {
                val item = source.optJSONObject(i) ?: continue
                if (item.optString("id") != id) out.put(item)
            }
            out.put(org.json.JSONObject().put("id", id).put("title", title).put("opened", System.currentTimeMillis()))
            val trimmed = org.json.JSONArray()
            for (i in (out.length() - 1).coerceAtLeast(0) downTo 0) {
                if (trimmed.length() >= 40) break
                trimmed.put(out.getJSONObject(i))
            }
            sp(c).edit().putString("history", trimmed.toString()).apply()
        }
    }
    fun history(c: Context): org.json.JSONArray =
        org.json.JSONArray(sp(c).getString("history", "[]") ?: "[]")
    fun fontSize(c: Context) = sp(c).getInt("font_size", 18).coerceIn(14, 32)
    fun setFontSize(c: Context, v: Int) = sp(c).edit().putInt("font_size", v.coerceIn(14, 32)).apply()
    fun theme(c: Context) = sp(c).getInt("theme", 0)
    fun setTheme(c: Context, v: Int) = sp(c).edit().putInt("theme", v.coerceIn(0, 4)).apply()
    fun removeBookmark(c: Context, index: Int) {
        val a = bookmarks(c)
        if (index in 0 until a.length()) {
            a.remove(index)
            sp(c).edit().putString("bookmarks", a.toString()).apply()
        }
    }
}
