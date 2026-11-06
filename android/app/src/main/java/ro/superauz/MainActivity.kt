package ro.superauz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private var huToRo = true
    private var listening = false
    private var busy = false // translating or speaking
    private var restartPending = false
    private var speakToken = 0
    private var lastTranslation = ""

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var dirBtn: Button
    private lateinit var srcView: TextView
    private lateinit var dstView: TextView
    private lateinit var micBtn: Button
    private lateinit var replayBtn: Button
    private lateinit var ampSwitch: Switch
    private lateinit var gainBar: SeekBar
    private lateinit var gainLabel: TextView
    private lateinit var msgView: TextView
    private lateinit var updateBtn: Button

    @Volatile private var ampOn = false
    @Volatile private var gain = 4f
    @Volatile private var duck = 1f
    private var ampThread: Thread? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, sizeSp: Float, color: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(Color.parseColor(color))
        return t
    }

    private fun space(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0f1316"))
        root.setPadding(dp(18), dp(40), dp(18), dp(24))

        val title = label("Super-Auz", 26f, "#eef4f3")
        title.gravity = Gravity.CENTER
        root.addView(title)
        root.addView(space(10))

        dirBtn = Button(this)
        dirBtn.isAllCaps = false
        dirBtn.setOnClickListener { swapDirection() }
        root.addView(dirBtn)
        root.addView(space(12))

        root.addView(label("AI SPUS", 11f, "#8aa0a3"))
        srcView = label("Apasă pe microfon și vorbește.", 20f, "#eef4f3")
        srcView.setPadding(0, dp(4), 0, dp(14))
        root.addView(srcView)

        root.addView(label("TRADUCERE", 11f, "#8aa0a3"))
        dstView = label("…", 20f, "#5cc182")
        dstView.setPadding(0, dp(4), 0, dp(14))
        root.addView(dstView)

        micBtn = Button(this)
        micBtn.isAllCaps = false
        micBtn.textSize = 18f
        micBtn.setOnClickListener { if (listening) stopAll() else startAll() }
        root.addView(micBtn)

        replayBtn = Button(this)
        replayBtn.isAllCaps = false
        replayBtn.text = "Ascultă din nou"
        replayBtn.setOnClickListener {
            if (lastTranslation.isNotEmpty() && !busy) {
                busy = true
                recognizer?.cancel()
                speak(lastTranslation)
            }
        }
        root.addView(replayBtn)
        root.addView(space(16))

        ampSwitch = Switch(this)
        ampSwitch.text = "Auz amplificat (ce aud în jur)"
        ampSwitch.setTextColor(Color.parseColor("#eef4f3"))
        ampSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (checked) startAmp() else stopAmp()
        }
        root.addView(ampSwitch)

        gainLabel = label("Amplificare: 4×", 13f, "#8aa0a3")
        root.addView(gainLabel)
        gainBar = SeekBar(this)
        gainBar.max = 18 // 1x .. 10x in steps of 0.5
        gainBar.progress = 6
        gainBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                gain = 1f + p * 0.5f
                gainLabel.text = "Amplificare: " + gain.toString().replace(".0", "") + "×"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(gainBar)
        root.addView(label("Folosește căști. Fără căști apare microfonie.", 12f, "#8aa0a3"))
        root.addView(space(12))

        msgView = label("", 13f, "#ff9aa8")
        root.addView(msgView)

        updateBtn = Button(this)
        updateBtn.isAllCaps = false
        updateBtn.visibility = View.GONE
        root.addView(updateBtn)

        root.addView(space(8))
        root.addView(label("Build " + BuildConfig.VERSION_CODE, 11f, "#8aa0a3"))

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(Color.parseColor("#0f1316"))
        scroll.addView(root)
        setContentView(scroll)

        refreshLabels()

        tts = TextToSpeech(this) { status -> ttsReady = (status == TextToSpeech.SUCCESS) }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { ui.post { finishSpeaking() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { ui.post { finishSpeaking() } }
        })

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        checkUpdate()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) {
            msg("Fără permisiunea pentru microfon aplicația nu poate asculta. Dă-i acces din Setări.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listening = false
        stopAmp()
        try { recognizer?.destroy() } catch (e: Exception) {}
        tts?.stop()
        tts?.shutdown()
    }

    private fun msg(m: String) { msgView.text = m }

    private fun refreshLabels() {
        dirBtn.text = if (huToRo) "Maghiară → Română   (apasă ca să schimbi)" else "Română → Maghiară   (apasă ca să schimbi)"
        micBtn.text = if (listening) "■  Oprește ascultarea" else "🎤  Pornește ascultarea"
    }

    private fun swapDirection() {
        huToRo = !huToRo
        refreshLabels()
        srcView.text = "…"
        dstView.text = "…"
        if (listening && !busy) {
            recognizer?.cancel()
            restartSoon(200)
        }
    }

    // ---------------- listening ----------------

    private fun startAll() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        listening = true
        busy = false
        msg("")
        refreshLabels()
        startListening()
    }

    private fun stopAll() {
        listening = false
        busy = false
        ui.removeCallbacksAndMessages(null)
        restartPending = false
        try { recognizer?.cancel() } catch (e: Exception) {}
        tts?.stop()
        duck = 1f
        refreshLabels()
    }

    private fun restartSoon(ms: Long) {
        if (restartPending) return
        restartPending = true
        ui.postDelayed({
            restartPending = false
            startListening()
        }, ms)
    }

    private fun startListening() {
        if (!listening || busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            msg("Telefonul nu are serviciu de recunoaștere vocală (Google).")
            return
        }
        try { recognizer?.destroy() } catch (e: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) srcView.text = t
            }

            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) {
                    srcView.text = t
                    translate(t)
                } else {
                    restartSoon(250)
                }
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    listening = false
                    refreshLabels()
                    msg("Nu am permisiune la microfon.")
                    return
                }
                if (!listening || busy) return
                restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 300L)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (huToRo) "hu-HU" else "ro-RO")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            msg("Nu pot porni ascultarea: " + (e.message ?: ""))
            restartSoon(800)
        }
    }

    // ---------------- translate + speak ----------------

    private fun translate(text: String) {
        busy = true
        dstView.text = "Traduc…"
        val pair = if (huToRo) "hu%7Cro" else "ro%7Chu"
        Thread {
            try {
                val url = URL("https://api.mymemory.translated.net/get?q=" +
                        URLEncoder.encode(text, "UTF-8") + "&langpair=" + pair)
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val raw = JSONObject(body).getJSONObject("responseData").getString("translatedText")
                val out = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                ui.post { onTranslated(out) }
            } catch (e: Exception) {
                ui.post {
                    busy = false
                    dstView.text = "…"
                    msg("Traducere eșuată: " + (e.message ?: "fără internet?"))
                    restartSoon(500)
                }
            }
        }.start()
    }

    private fun onTranslated(out: String) {
        lastTranslation = out
        dstView.text = out
        speak(out)
    }

    private fun speak(t: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            msg("Vocea de citire nu e gata încă.")
            finishSpeaking()
            return
        }
        val loc = if (huToRo) Locale("ro", "RO") else Locale("hu", "HU")
        val res = engine.setLanguage(loc)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            msg("Lipsește vocea pentru " + (if (huToRo) "română" else "maghiară") +
                    ". Instalează-o în Setări → Text-to-speech.")
            finishSpeaking()
            return
        }
        duck = 0.12f
        val token = ++speakToken
        engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + token)
        // safety: if the engine never reports completion
        ui.postDelayed({ if (busy && speakToken == token) finishSpeaking() }, 3000L + t.length * 120L)
    }

    private fun finishSpeaking() {
        duck = 1f
        busy = false
        if (listening) restartSoon(200)
    }

    // ---------------- amplifier ----------------

    private fun startAmp() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            ampSwitch.isChecked = false
            return
        }
        if (ampOn) return
        ampOn = true
        val t = Thread { runAmp() }
        ampThread = t
        t.start()
    }

    private fun stopAmp() {
        ampOn = false
        try { ampThread?.join(800) } catch (e: Exception) {}
        ampThread = null
    }

    private fun runAmp() {
        val rate = 16000
        val enc = AudioFormat.ENCODING_PCM_16BIT
        var rec: AudioRecord? = null
        var trk: AudioTrack? = null
        try {
            val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, enc)
            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, enc)
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, enc,
                maxOf(minIn, 2048) * 2)
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("microfon indisponibil")
            trk = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(enc)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOut, 2048))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            rec.startRecording()
            trk.play()

            val buf = ShortArray(512)
            val hpA = 0.895f   // high-pass ~300 Hz
            val lpAlpha = 0.6f // low-pass ~3.8 kHz
            var hpY = 0f
            var hpX = 0f
            var lpY = 0f
            while (ampOn) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("eroare citire microfon ($n)")
                if (n == 0) continue
                val g = gain * duck
                for (k in 0 until n) {
                    val x = buf[k].toFloat()
                    hpY = hpA * (hpY + x - hpX)
                    hpX = x
                    lpY += lpAlpha * (hpY - lpY)
                    val v = lpY * g / 32768f
                    val o = Math.tanh(v.toDouble()).toFloat() * 32767f
                    buf[k] = o.toInt().toShort()
                }
                trk.write(buf, 0, n)
            }
        } catch (e: Exception) {
            ampOn = false
            ui.post {
                msg("Amplificarea s-a oprit: " + (e.message ?: ""))
                ampSwitch.isChecked = false
            }
        } finally {
            try { rec?.stop() } catch (e: Exception) {}
            try { rec?.release() } catch (e: Exception) {}
            try { trk?.stop() } catch (e: Exception) {}
            try { trk?.release() } catch (e: Exception) {}
        }
    }

    // ---------------- updates ----------------

    private fun checkUpdate() {
        Thread {
            try {
                val url = URL("https://api.github.com/repos/White131313/Super-auz/releases/tags/latest")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("User-Agent", "SuperAuzApp")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(body)
                val n = j.optString("name").filter { it.isDigit() }.toIntOrNull() ?: 0
                val assets = j.optJSONArray("assets")
                val apk = if (assets != null && assets.length() > 0)
                    assets.getJSONObject(0).optString("browser_download_url") else ""
                if (apk.isNotEmpty() && n > BuildConfig.VERSION_CODE) {
                    ui.post {
                        updateBtn.text = "⬇ Update disponibil (Build $n) — descarcă"
                        updateBtn.visibility = View.VISIBLE
                        updateBtn.setOnClickListener {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk)))
                        }
                    }
                }
            } catch (e: Exception) {
                // no update info; ignore
            }
        }.start()
    }
}
