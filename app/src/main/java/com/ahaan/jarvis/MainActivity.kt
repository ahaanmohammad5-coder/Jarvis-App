package com.ahaan.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvStatus: TextView
    private lateinit var tvCaption: TextView
    private lateinit var ivReactor: ImageView

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var tts: TextToSpeech? = null

    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---- State machine ----
    private enum class State { IDLE, AWAKE, THINKING, SPEAKING }
    private var state = State.IDLE
    private var listening = false
    private var awakeTimeout: Runnable? = null
    /** State to enter when the current TTS utterance finishes. */
    private var afterSpeech: State = State.IDLE

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                startPassiveListening()
            } else {
                tvStatus.text = getString(R.string.perm_denied)
            }
        }

    // ================= Lifecycle =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvCaption = findViewById(R.id.tvCaption)
        ivReactor = findViewById(R.id.ivReactor)

        // TTS first (needed before speaking)
        tts = TextToSpeech(this, this)

        // Tap reactor toggles passive listening on/off
        ivReactor.setOnClickListener {
            if (listening) stopListening() else requestPermissionsAndStart()
        }

        buildRecognizerIntent()
        requestPermissionsAndStart()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val urdu = Locale("ur", "PK")
            val res = tts?.setLanguage(urdu)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback: try generic Urdu, then default
                val r2 = tts?.setLanguage(Locale("ur"))
                if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
            }
            tts?.setSpeechRate(0.95f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { setState(State.SPEAKING) }
                }
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        // Enter the state that was requested for after this utterance
                        val next = afterSpeech
                        afterSpeech = State.IDLE
                        setState(next)
                        if (next == State.AWAKE) armAwakeTimeout()
                        restartListeningDelayed(400)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        val next = afterSpeech
                        afterSpeech = State.IDLE
                        setState(next)
                        if (next == State.AWAKE) armAwakeTimeout()
                        restartListeningDelayed(400)
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        stopListening()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ================= Permissions =================

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (!hasPerm(Manifest.permission.CALL_PHONE)) needed.add(Manifest.permission.CALL_PHONE)
        if (!hasPerm(Manifest.permission.SEND_SMS)) needed.add(Manifest.permission.SEND_SMS)
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) needed.add(Manifest.permission.READ_CONTACTS)

        if (needed.isEmpty()) {
            startPassiveListening()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPerm(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // ================= Speech recognition =================

    private fun buildRecognizerIntent() {
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Prefer Urdu recognition; falls back gracefully
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            // Restart listening unless we're speaking/thinking (errors 7=no match, 6=no speech are normal)
            if (state == State.IDLE || state == State.AWAKE) {
                restartListeningDelayed(600)
            }
        }

        override fun onPartialResults(partial: Bundle?) {
            val text = bestTranscript(partial) ?: return
            if (state == State.IDLE && isWakeWord(text)) {
                onWakeWord()
            }
        }

        override fun onResults(results: Bundle?) {
            val text = bestTranscript(results) ?: run {
                if (state == State.IDLE || state == State.AWAKE) restartListeningDelayed(300)
                return
            }
            when (state) {
                State.IDLE -> if (isWakeWord(text)) onWakeWord() else restartListeningDelayed(300)
                State.AWAKE -> onCommand(text)
                else -> { /* ignore while thinking/speaking */ }
            }
        }
    }

    private fun bestTranscript(bundle: Bundle?): String? {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isWakeWord(text: String): Boolean {
        val t = " $text ".lowercase(Locale.ROOT)
        return t.contains("hey jarvis") || t.contains("jarvis") || t.contains("جاروس")
    }

    private fun startPassiveListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        listening = true
        setState(State.IDLE)
        startListeningNow()
    }

    private fun startListeningNow() {
        if (!listening) return
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.startListening(recognizerIntent)
        } catch (_: Exception) {
            restartListeningDelayed(1000)
        }
    }

    private fun restartListeningDelayed(ms: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, ms)
    }

    private val restartRunnable = Runnable { startListeningNow() }

    private fun stopListening() {
        listening = false
        handler.removeCallbacks(restartRunnable)
        awakeTimeout?.let { handler.removeCallbacks(it) }
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        tvStatus.text = getString(R.string.status_idle)
    }

    // ================= Wake word & commands =================

    private fun onWakeWord() {
        setState(State.AWAKE)
        tvCaption.text = getString(R.string.status_awake)
        // Greet, then stay AWAKE listening for the actual command
        speak(getString(R.string.status_awake), next = State.AWAKE)
    }

    /** Gives the user 10s to say a command after wake-up. */
    private fun armAwakeTimeout() {
        awakeTimeout?.let { handler.removeCallbacks(it) }
        awakeTimeout = Runnable {
            if (state == State.AWAKE) {
                setState(State.IDLE)
                tvCaption.text = getString(R.string.hint_wake)
                restartListeningDelayed(300)
            }
        }
        handler.postDelayed(awakeTimeout!!, 10_000)
    }

    private fun onCommand(raw: String) {
        awakeTimeout?.let { handler.removeCallbacks(it) }
        tvCaption.text = "🗣 $raw"
        setState(State.THINKING)
        tvStatus.text = getString(R.string.status_thinking)

        val handled = handleLocalCommand(raw.lowercase(Locale.ROOT).trim(), raw)
        if (!handled) {
            askAI(raw)
        }
        // If handled locally: handleLocalCommand already called speak(),
        // whose UtteranceProgressListener returns us to IDLE listening when done.
    }

    /** Returns true if a local command handled the input. */
    private fun handleLocalCommand(lower: String, raw: String): Boolean {
        // ---- WhatsApp: "whatsapp <message> to <number>" ----
        Regex("""whatsapp\s+(.+?)\s+to\s+([\d+\s-]+)""").find(lower)?.let { m ->
            val message = m.groupValues[1].trim()
            val number = normalizePkNumber(m.groupValues[2])
            speak("Theek hai, WhatsApp khol raha hoon.")
            openWhatsApp(number, message)
            return true
        }
        // ---- SMS: "sms <message> to <number>" / "message <message> to <number>" ----
        Regex("""(?:sms|message)\s+(.+?)\s+to\s+([\d+\s-]+)""").find(lower)?.let { m ->
            val message = m.groupValues[1].trim()
            val number = normalizePkNumber(m.groupValues[2])
            speak("Theek hai, SMS tayyar kar raha hoon.")
            openSms(number, message)
            return true
        }
        // ---- Call: "call <number>" ----
        Regex("""\bcall\s+([\d+\s-]+)""").find(lower)?.let { m ->
            val number = normalizePkNumber(m.groupValues[1])
            speak("Call laga raha hoon.")
            openDialer(number)
            return true
        }
        // ---- YouTube: "youtube <query>" ----
        Regex("""youtube\s+(.+)""").find(lower)?.let { m ->
            val q = m.groupValues[1].trim()
            speak("YouTube par $q search kar raha hoon.")
            openUrl("https://www.youtube.com/results?search_query=${Uri.encode(q)}")
            return true
        }
        // ---- Google: "google <query>" / "search <query>" ----
        Regex("""(?:google|search)\s+(.+)""").find(lower)?.let { m ->
            val q = m.groupValues[1].trim()
            speak("Google par $q dhoond raha hoon.")
            openUrl("https://www.google.com/search?q=${Uri.encode(q)}")
            return true
        }
        // ---- Time ----
        if (lower.contains("time") || lower.contains("baj")) {
            speak("Abhi ${urduTime()} ho rahay hain.")
            return true
        }
        // ---- Date ----
        if (lower.contains("date") || lower.contains("tareekh") || lower.contains("taareekh")) {
            speak("Aaj ${urduDate()} hai.")
            return true
        }
        // ---- Calculate: "calculate <expr>" ----
        Regex("""(?:calculate|hisab|hisaab)\s+(.+)""").find(lower)?.let { m ->
            val expr = m.groupValues[1]
                .replace("plus", "+").replace("minus", "-")
                .replace("times", "*").replace("multiply", "*")
                .replace("divided by", "/").replace("divide", "/")
                .replace("x", "*")
            val result = evalExpr(expr)
            if (result != null) {
                val pretty = if (result % 1.0 == 0.0) result.toLong().toString() else result.toString()
                speak("Jawab hai $pretty.")
            } else {
                speak("Maaf kijiye, ye hisaab samajh nahi aaya.")
            }
            return true
        }
        return false
    }

    // ================= Intents =================

    private fun openWhatsApp(number: String, message: String) {
        // wa.me opens WhatsApp with pre-filled text; user taps send (Android security)
        val url = "https://wa.me/$number?text=${Uri.encode(message)}"
        openUrl(url)
    }

    private fun openSms(number: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", message)
        }
        safeStart(intent, "Koi SMS app nahi mili.")
    }

    private fun openDialer(number: String) {
        // ACTION_DIAL (not CALL) so the user confirms — safer & needs no extra permission
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        safeStart(intent, "Dialer nahi khul saka.")
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        safeStart(intent, "Browser nahi khul saka.")
    }

    private fun safeStart(intent: Intent, errMsg: String) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            speak(errMsg)
        }
    }

    private fun normalizePkNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") -> digits
            digits.startsWith("92") -> "+$digits"
            digits.startsWith("0") -> "+92" + digits.drop(1)
            else -> digits
        }
    }

    // ================= Urdu helpers =================

    private fun urduTime(): String {
        val h24 = SimpleDateFormat("H", Locale.US).format(Date()).toInt()
        val m = SimpleDateFormat("m", Locale.US).format(Date()).toInt()
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return if (m == 0) "$h12 baj rahay hain"
        else "$h12 baj kar $m minute ho rahay hain"
    }

    private fun urduDate(): String {
        val day = SimpleDateFormat("d", Locale.US).format(Date())
        val monthEn = SimpleDateFormat("MMMM", Locale.US).format(Date())
        val monthUr = mapOf(
            "January" to "Janvari", "February" to "Farvari", "March" to "March",
            "April" to "April", "May" to "Mayi", "June" to "June",
            "July" to "Julayi", "August" to "Agast", "September" to "Sitambar",
            "October" to "Aktobar", "November" to "Navambar", "December" to "Disambar"
        )[monthEn] ?: monthEn
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        return "$day $monthUr $year"
    }

    // ================= Simple expression evaluator =================
    // Supports +, -, *, /, parentheses, decimals.

    private fun evalExpr(expr: String): Double? {
        return try {
            val tokens = tokenize(expr)
            val parser = ExprParser(tokens)
            val v = parser.parseExpr()
            if (parser.pos == tokens.size) v else null
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    out.add(s.substring(i, j)); i = j
                }
                c in "+-*/()" -> { out.add(c.toString()); i++ }
                c.isWhitespace() -> i++
                else -> i++ // ignore words
            }
        }
        return out
    }

    private class ExprParser(val tokens: List<String>) {
        var pos = 0
        fun parseExpr(): Double {
            var v = parseTerm()
            while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                val op = tokens[pos++]
                val r = parseTerm()
                v = if (op == "+") v + r else v - r
            }
            return v
        }
        fun parseTerm(): Double {
            var v = parseFactor()
            while (pos < tokens.size && (tokens[pos] == "*" || tokens[pos] == "/")) {
                val op = tokens[pos++]
                val r = parseFactor()
                v = if (op == "*") v * r else v / r
            }
            return v
        }
        fun parseFactor(): Double {
            val t = tokens[pos++]
            return when {
                t == "(" -> { val v = parseExpr(); pos++; v } // skip ')'
                t == "-" -> -parseFactor()
                else -> t.toDouble()
            }
        }
    }

    // ================= Pollinations AI =================

    private fun askAI(question: String) {
        Thread {
            try {
                val systemPrompt = "You are Jarvis, a friendly AI assistant. " +
                    "Talk like a Karachi friend in fluent, natural Roman Urdu. " +
                    "NEVER mix English. Keep answers to 2-3 sentences. Be witty and helpful."

                val body = JSONObject().apply {
                    put("model", "openai")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system"); put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user"); put("content", question)
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://text.pollinations.ai/")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val text = resp.body?.string()?.trim().orEmpty()
                    val answer = if (text.length >= 3 && text != "{}") text
                    else "Maaf kijiye, jawab nahi mil saka. Dobara poochiye."
                    runOnUiThread {
                        tvCaption.text = "🤖 $answer"
                        speak(answer)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    speak("Internet mein masla lag raha hai. Dobara koshish kijiye.")
                    setState(State.IDLE)
                    restartListeningDelayed(800)
                }
            }
        }.start()
    }

    // ================= Speech output =================

    private fun speak(
        text: String,
        utteranceId: String = "jarvis_${System.currentTimeMillis()}",
        next: State = State.IDLE
    ) {
        // Pause listening while speaking so Jarvis doesn't hear itself
        afterSpeech = next
        handler.removeCallbacks(restartRunnable)
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        tvCaption.text = "🤖 $text"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun setState(s: State) {
        state = s
        tvStatus.text = when (s) {
            State.IDLE -> getString(R.string.status_idle)
            State.AWAKE -> getString(R.string.status_listening)
            State.THINKING -> getString(R.string.status_thinking)
            State.SPEAKING -> getString(R.string.status_speaking)
        }
        // Reactor glow feedback
        ivReactor.alpha = when (s) {
            State.IDLE -> 0.75f
            State.AWAKE -> 1.0f
            State.THINKING -> 0.9f
            State.SPEAKING -> 1.0f
        }
    }
}
