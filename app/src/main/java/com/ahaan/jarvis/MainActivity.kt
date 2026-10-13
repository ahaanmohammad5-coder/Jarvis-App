package com.ahaan.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tvStatus: TextView
    private lateinit var tvCaption: TextView
    private lateinit var btnMic: Button
    private lateinit var reactorOuter: View
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvCaption = findViewById(R.id.tvCaption)
        btnMic = findViewById(R.id.btnMic)
        reactorOuter = findViewById(R.id.reactorOuter)

        tts = TextToSpeech(this, this)

        val tapAction = {
            if (checkAudioPermission()) toggleListening()
            else requestAudioPermission()
        }
        btnMic.setOnClickListener { tapAction() }
        reactorOuter.setOnClickListener { tapAction() }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        // Pick the best available voice: prefer Urdu, then Hindi, then default.
        // Hindi sounds far more natural for Urdu text than English does.
        val candidates = listOf(Locale("ur", "PK"), Locale("ur", "IN"), Locale("hi", "IN"))
        var picked: Locale? = null
        for (loc in candidates) {
            val avail = tts?.isLanguageAvailable(loc) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (avail == TextToSpeech.LANG_AVAILABLE ||
                avail == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                picked = loc
                break
            }
        }
        if (picked != null) tts?.language = picked
        else tts?.language = Locale.getDefault()

        tts?.setSpeechRate(1.0f)
        tts?.setPitch(1.0f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {}
            override fun onError(id: String?) {}
        })
        ttsReady = true
        speak("Assalam o Alaikum Ahaan! Main Jarvis hoon, aapka personal assistant. Kuch poochna ho to mic ka button dabayein.")
    }

    private fun checkAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 100 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            speak("Shukriya! Ab mic ka button daba kar bolein.")
        } else {
            Toast.makeText(this, "Mic permission chahiye", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleListening() {
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition available nahi", Toast.LENGTH_SHORT).show()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                runOnUiThread {
                    tvStatus.text = "Sun raha hoon..."
                    tvCaption.text = "Bolein, main sun raha hoon"
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                runOnUiThread {
                    tvStatus.text = getString(R.string.status_idle)
                    tvCaption.text = getString(R.string.hint_wake)
                    listening = false
                }
            }
            override fun onResults(r: Bundle?) {
                val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                listening = false
                if (text.isNotBlank()) {
                    runOnUiThread { tvCaption.text = "Aap ne kaha: \"$text\"" }
                    handleCommand(text)
                } else {
                    runOnUiThread { tvStatus.text = getString(R.string.status_idle) }
                }
            }
            override fun onPartialResults(r: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        recognizer?.startListening(intent)
        listening = true
        tvStatus.text = "Sun raha hoon..."
    }

    private fun stopListening() {
        recognizer?.stopListening()
        listening = false
        tvStatus.text = getString(R.string.status_idle)
    }

    private fun handleCommand(text: String) {
        val lower = text.lowercase(Locale.ROOT).trim()
        // Remove wake words for cleaner matching
        val cmd = lower.replace("hey jarvis", "").replace("jarvis", "").trim()

        when {
            // Greetings
            cmd.contains("assalam") || cmd.contains("salam") ->
                reply("Walaikum Assalam Ahaan! Kaise madad kar sakta hoon?")
            cmd.contains("hello") || cmd.contains("hi ") || cmd == "hi" ->
                reply("Hello Ahaan! Kya hukm hai?")
            cmd.contains("how are you") || cmd.contains("kya haal") || cmd.contains("kaise ho") ->
                reply("Main bilkul theek hoon, shukriya! Aap sunayein?")
            cmd.contains("good morning") ->
                reply("Good morning Ahaan! Aapka din acha guzre!")
            cmd.contains("good night") || cmd.contains("shab bakhair") ->
                reply("Shab bakhair Ahaan! Meethay khwab dekhein!")

            // Time & Date
            cmd.contains("time") || cmd.contains("waqt") || cmd.contains("baj") || cmd.contains("kitne baj") -> {
                val t = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                reply("Abhi $t baj rahe hain.")
            }
            cmd.contains("date") || cmd.contains("tareekh") || cmd.contains("tarikh") || cmd.contains("aaj kya") -> {
                val d = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
                reply("Aaj $d hai.")
            }
            cmd.contains("day") || cmd.contains("din") && cmd.contains("konsa") -> {
                val d = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
                reply("Aaj $d hai.")
            }

            // Identity
            cmd.contains("your name") || cmd.contains("tumhara naam") || cmd.contains("aapka naam") || cmd.contains("who are you") ->
                reply("Main Jarvis hoon, Ahaan ka personal assistant!")
            cmd.contains("my name") || cmd.contains("mera naam") ->
                reply("Aapka naam Ahaan hai!")

            // Actions: YouTube
            cmd.contains("youtube") -> {
                val query = cmd.replace("youtube", "").replace("play", "").replace("search", "")
                    .replace("par", "").replace("mein", "").replace("chalao", "").trim()
                reply(if (query.isNotBlank()) "YouTube par $query chala raha hoon."
                else "YouTube khol raha hoon.")
                openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(if (query.isNotBlank()) query else "music"))
            }
            // Actions: Google search
            cmd.contains("google") || cmd.contains("search") -> {
                val query = cmd.replace("google", "").replace("search", "")
                    .replace("karo", "").replace("kar", "").trim()
                if (query.isNotBlank()) {
                    reply("$query Google par search kar raha hoon.")
                    openUrl("https://www.google.com/search?q=" + Uri.encode(query))
                } else reply("Kya search karoon?")
            }
            // Actions: WhatsApp
            cmd.contains("whatsapp") -> {
                reply("WhatsApp khol raha hoon.")
                openUrl("https://wa.me/")
            }
            // Actions: Call
            cmd.contains("call") || cmd.contains("phone") || cmd.contains("cal") -> {
                reply("Dialer khol raha hoon.")
                startActivity(Intent(Intent.ACTION_DIAL))
            }
            // Actions: Calculator
            cmd.contains("calcul") || cmd.contains("hisaab") -> {
                reply("Calculator khol raha hoon.")
                try {
                    val i = Intent().apply {
                        setClassName("com.android.calculator2", "com.android.calculator2.Calculator")
                    }
                    startActivity(i)
                } catch (e: Exception) {
                    openUrl("https://www.google.com/search?q=calculator")
                }
            }
            // Jokes
            cmd.contains("joke") || cmd.contains("mazak") || cmd.contains("latifa") ->
                reply("Ek aadmi doctor ke paas gaya aur bola: Doctor sahab, mujhe bhoolne ki bimari hai! Doctor bola: Kab se? Aadmi bola: Kab se kya?")
            cmd.contains("shayari") || cmd.contains("poetry") ->
                reply("Arz kiya hai: Chandni raaton mein taare chamakte hain, Ahaan ke phone mein Jarvis damakte hain!")

            // Thanks / Bye
            cmd.contains("thank") || cmd.contains("shukriya") ->
                reply("Khush aamdeed Ahaan! Koi baat nahi!")
            cmd.contains("bye") || cmd.contains("allah hafiz") || cmd.contains("khuda hafiz") ->
                reply("Allah Hafiz Ahaan! Phir milenge!")

            // Fallback: intelligent, never just echo
            else -> {
                val responses = listOf(
                    "Hmm, '$text' — is par main abhi kaam kar raha hoon. Kuch aur poochein?",
                    "Samajh gaya! '$text' ke baare mein jald seekh jaunga. Filhaal time, date, YouTube ya Google search pooch sakte hain!",
                    "Dilchasp sawal! Main abhi seekh raha hoon. Aap 'YouTube par gana chalao' ya 'time kya hai' keh kar dekhein!"
                )
                reply(responses.random())
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Khol nahi saka", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reply(text: String) {
        tvStatus.text = "Jawab de raha hoon..."
        tvCaption.text = text
        speak(text)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        recognizer?.cancel()
        listening = false
        tvStatus.text = "Bol raha hoon..."
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
        // Reset status after speaking (approximate)
        tvStatus.postDelayed({
            if (!listening) tvStatus.text = getString(R.string.status_idle)
        }, (text.length * 90L).coerceAtLeast(2000L))
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
