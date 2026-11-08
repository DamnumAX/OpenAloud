package app.openaloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.util.Locale
import kotlin.math.max

class TtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val PLAY = "app.openaloud.PLAY"
        const val PAUSE = "app.openaloud.PAUSE"
        const val TOGGLE = "app.openaloud.TOGGLE"
        const val NEXT = "app.openaloud.NEXT"
        const val PREV = "app.openaloud.PREV"
        const val NEXT_CH = "app.openaloud.NEXT_CH"
        const val PREV_CH = "app.openaloud.PREV_CH"
        const val SEEK = "app.openaloud.SEEK"
        const val SEEK_PLAY = "app.openaloud.SEEK_PLAY"
        const val SLEEP = "app.openaloud.SLEEP"
        const val STOP = "app.openaloud.STOP"
        private const val CH = "reader"
        private const val NID = 1
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var wantPlay = false
    private var notified = false
    private var spokenLocale: Locale? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private var focus: AudioFocusRequest? = null
    private val sleepTask = Runnable { pause() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Озвучка", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSessionCompat(this, "OpenAloud").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = move(1)
                override fun onSkipToPrevious() = move(-1)
            })
            isActive = true
        }
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "Движок синтеза речи недоступен", Toast.LENGTH_LONG).show()
            return
        }
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                main.post {
                    if (wantPlay && id == "s${Reader.index}") {
                        Reader.index++
                        save()
                        speakCurrent()
                        refreshUi(true)
                    }
                }
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String?) { main.post { pause() } }
        })
        ready = true
        if (wantPlay) speakCurrent()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) return START_NOT_STICKY
        when (i.action) {
            PLAY -> play()
            PAUSE -> pause()
            TOGGLE -> if (wantPlay) pause() else play()
            NEXT -> move(1)
            PREV -> move(-1)
            NEXT_CH -> chapter(1)
            PREV_CH -> chapter(-1)
            SEEK -> seek(i.getIntExtra("index", 0))
            SEEK_PLAY -> { seek(i.getIntExtra("index", 0)); play() }
            SLEEP -> {
                main.removeCallbacks(sleepTask)
                val m = i.getIntExtra("min", 0)
                if (m > 0) main.postDelayed(sleepTask, m * 60_000L)
            }
            STOP -> { pause(); stopForeground(STOP_FOREGROUND_REMOVE); notified = false; stopSelf() }
        }
        return START_NOT_STICKY
    }

    // ---------- управление ----------

    private fun play() {
        wantPlay = Reader.sentences.isNotEmpty()
        startForeground(NID, notif())      // обязательно сразу после startForegroundService
        notified = true
        if (!wantPlay) { stopForeground(STOP_FOREGROUND_REMOVE); notified = false; return }
        Reader.playing = true
        requestFocus()
        if (Reader.index >= Reader.sentences.size) Reader.index = 0
        speakCurrent()
        refreshUi(true)
    }

    private fun pause() {
        wantPlay = false
        Reader.playing = false
        tts?.stop()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        save()
        if (notified) stopForeground(STOP_FOREGROUND_DETACH)
        refreshUi(notified)
    }

    private fun seek(i: Int) {
        Reader.index = i.coerceIn(0, max(0, Reader.sentences.size - 1))
        save()
        if (wantPlay) speakCurrent()
        refreshUi(wantPlay)
    }

    private fun move(d: Int) = seek(Reader.index + d)

    private fun chapter(d: Int) {
        val ch = Reader.chapters
        val c = ch.indexOfLast { it <= Reader.index }.coerceAtLeast(0)
        val target = if (d < 0 && Reader.index > ch[c]) c else c + d
        if (target in ch.indices) seek(ch[target])
    }

    private fun save() {
        if (Reader.docId.isNotEmpty()) Prefs.setPos(this, Reader.docId, Reader.index)
    }

    // ---------- озвучка ----------

    private fun speakCurrent() {
        val t = tts ?: return
        if (!ready || Reader.sentences.isEmpty()) return
        if (Reader.index >= Reader.sentences.size) {   // конец текста
            pause()
            return
        }
        if (spokenLocale != Reader.locale) {
            t.language = Reader.locale
            spokenLocale = Reader.locale
        }
        t.setSpeechRate(Prefs.rate(this))
        t.setPitch(Prefs.pitch(this))
        val text = applyDict(Reader.sentences[Reader.index])
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "s${Reader.index}")
    }

    /** Словарь замен: строки вида «слово=как произносить». */
    private fun applyDict(s: String): String {
        var r = s
        for (line in Prefs.dict(this).lineSequence()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            r = r.replace(line.substring(0, i).trim(), line.substring(i + 1).trim(), ignoreCase = true)
        }
        return r
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener { c ->
                if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    main.post { pause() }
            }.build()
        focus = r
        am.requestAudioFocus(r)
    }

    // ---------- уведомление и медиасессия ----------

    private fun refreshUi(updateNotif: Boolean) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, Reader.title).build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (wantPlay) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                ).build()
        )
        if (updateNotif && notified)
            getSystemService(NotificationManager::class.java).notify(NID, notif())
        Reader.changed()
    }

    private fun pi(action: String) = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, TtsService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notif(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(Reader.title.ifEmpty { "OpenAloud" })
            .setContentText(Reader.sentences.getOrNull(Reader.index)?.take(100) ?: "")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(wantPlay)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Назад", pi(PREV))
            .addAction(
                if (wantPlay) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                "Пауза/Play", pi(TOGGLE)
            )
            .addAction(android.R.drawable.ic_media_next, "Вперёд", pi(NEXT))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacks(sleepTask)
        tts?.shutdown()
        session.release()
        super.onDestroy()
    }
}
