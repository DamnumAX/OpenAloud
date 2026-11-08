package app.openaloud

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.util.Locale
import java.util.zip.ZipInputStream

data class Doc(
    val id: String,
    val title: String,
    val sentences: List<String>,
    val chapters: List<Int>,
    val locale: Locale,
)

object Loader {

    fun fromUri(ctx: Context, uri: Uri): Doc {
        val name = displayName(ctx, uri)
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val isEpub = name.endsWith(".epub", true) || mime == "application/epub+zip"
        val isPdf = name.endsWith(".pdf", true) || mime == "application/pdf"
        val isDocx = name.endsWith(".docx", true) ||
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val parts = when {
            isEpub -> epub(ctx, uri)
            isPdf -> listOf(pdf(ctx, uri))
            isDocx -> listOf(docx(ctx, uri))
            else -> {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                listOf(decode(bytes))
            }
        }
        return build(uri.toString(), name.substringBeforeLast('.'), parts)
    }

    fun fromText(title: String, text: String) =
        build("text:${text.hashCode()}", title, listOf(text))

    fun fromUrl(url: String): Doc {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val html = decode(c.inputStream.use { it.readBytes() })
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.trim()?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() }
            ?: url
        return build(url, title, listOf(htmlToText(html)))
    }

    // ---------- сборка документа ----------

    private fun build(id: String, title: String, parts: List<String>): Doc {
        val sentences = ArrayList<String>()
        val chapters = ArrayList<Int>()
        for (p in parts) {
            val before = sentences.size
            split(p, sentences)
            if (sentences.size > before) chapters.add(before)
        }
        if (chapters.isEmpty()) chapters.add(0)
        val sample = sentences.take(60).joinToString(" ")
        return Doc(id, title, sentences, chapters, detect(sample))
    }

    private fun split(text: String, out: MutableList<String>) {
        val bi = BreakIterator.getSentenceInstance(Locale.getDefault())
        for (para in text.split('\n')) {
            val p = para.trim()
            if (p.isEmpty()) continue
            bi.setText(p)
            var s = bi.first()
            var e = bi.next()
            while (e != BreakIterator.DONE) {
                addChunk(out, p.substring(s, e))
                s = e
                e = bi.next()
            }
        }
    }

    private fun addChunk(out: MutableList<String>, raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        // движок TTS не принимает слишком длинные фрагменты
        if (t.length <= 3000) out.add(t) else t.chunked(3000).forEach { out.add(it) }
    }

    private fun detect(s: String): Locale {
        var cyr = 0
        var lat = 0
        for (c in s) {
            if (c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё') cyr++
            else if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        val d = Locale.getDefault()
        return if (cyr > lat) Locale("ru", "RU") else if (d.language == "ru") Locale.US else d
    }

    // ---------- чтение форматов ----------

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) return it.getString(0) ?: "Документ" }
        return uri.lastPathSegment ?: "Документ"
    }

    private fun decode(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString().replace("\uFEFF", "")
    } catch (e: Exception) {
        String(b, Charset.forName("windows-1251"))
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

    private fun resolve(dir: String, href: String): String {
        val parts = ArrayList<String>()
        val full = if (dir.isEmpty()) href else "$dir/$href"
        for (s in full.split('/')) {
            when {
                s == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                s != "." && s.isNotEmpty() -> parts.add(s)
            }
        }
        return parts.joinToString("/")
    }

    private fun pdf(ctx: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(ctx)
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть PDF" }
            PDDocument.load(input).use { document ->
                require(!document.isEncrypted) { "PDF защищён паролем и пока не поддерживается" }
                return PDFTextStripper().getText(document)
            }
        }
    }

    private fun docx(ctx: Context, uri: Uri): String {
        var documentXml: String? = null
        ZipInputStream(ctx.contentResolver.openInputStream(uri)?.buffered()
            ?: error("Не удалось открыть DOCX")).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentXml = decode(zip.readBytes())
                    break
                }
                entry = zip.nextEntry
            }
        }
        val xml = documentXml ?: error("В DOCX не найден основной текст")
        val text = xml.replace(Regex("(?i)</w:p\\s*>"), "\\n")
            .replace(Regex("(?i)<w:tab\\b[^>]*/>"), "\\t")
            .replace(Regex("(?is)<[^>]+>"), "")
        return HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString().replace("\\u00A0", " ").trim()
    }

    private fun epub(ctx: Context, uri: Uri): List<String> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ctx.contentResolver.openInputStream(uri)!!.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val container = files["META-INF/container.xml"]?.let { decode(it) } ?: return emptyList()
        val rootTag = Regex("<rootfile\\b[^>]*>").find(container)?.value ?: return emptyList()
        val opfPath = attr(rootTag, "full-path") ?: return emptyList()
        val opf = decode(files[opfPath] ?: return emptyList())
        val dir = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, String>()
        Regex("<item\\b[^>]*>").findAll(opf).forEach { m ->
            val id = attr(m.value, "id")
            val href = attr(m.value, "href")
            if (id != null && href != null) manifest[id] = href
        }
        val out = ArrayList<String>()
        Regex("<itemref\\b[^>]*>").findAll(opf).forEach { m ->
            val href = manifest[attr(m.value, "idref") ?: return@forEach] ?: return@forEach
            val path = resolve(dir, URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            val html = files[path]?.let { decode(it) } ?: return@forEach
            out.add(htmlToText(html))
        }
        return out
    }

    private fun htmlToText(html: String): String {
        var cleaned = html
            .replace(Regex("(?s)<!--.*?-->"), "")
            .replace(Regex("(?s)<\\?.*?\\?>|<!DOCTYPE[^>]*>"), "")
            .replace(Regex("(?is)<head\\b.*?</head>"), "")
            .replace(Regex("(?is)<(script|style|nav|footer|aside|noscript|form|button|svg)\\b.*?</\\1>"), "")
        // Убираем типичные рекламные, навигационные и комментарийные контейнеры.
        val noisy = Regex("(?is)<(div|section|aside|ul|ol)\\b(?=[^>]*(?:class|id)\\s*=\\s*[\"'][^\"']*(?:ad[-_ ]?|advert|banner|promo|cookie|popup|modal|comment|disqus|social|share|sidebar|navigation|\\bmenu\\b)[^\"']*[\"'])[^>]*>.*?</\\1>")
        repeat(3) { cleaned = cleaned.replace(noisy, "") }
        cleaned = cleaned
            .replace(Regex("(?is)<(header|nav|footer)\\b.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>|</tr>"), "\\n")
        return HtmlCompat.fromHtml(cleaned, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString().replace("\uFFFC", "").replace(Regex("[\\t ]+"), " ")
            .replace(Regex(" *\\n *"), "\n").trim()
    }
}
