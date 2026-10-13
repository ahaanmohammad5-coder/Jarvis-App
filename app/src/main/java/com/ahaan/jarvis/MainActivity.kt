package com.ahaan.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvStatus: TextView
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)

        tts = TextToSpeech(this, this)

        tvStatus.setOnClickListener {
            if (checkAudioPermission()) toggleListening()
            else requestAudioPermission()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val urPk = Locale("ur", "PK")
            val result = tts?.setLanguage(urPk)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(0.95f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {}
                override fun onError(id: String?) {}
            })
            speak("Assalam o Alaikum Ahaan! Main Jarvis hoon. Bolne ke liye tap karein.")
        }
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
            speak("Shukriya! Ab bolne ke liye tap karein.")
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
            override fun onReadyForSpeech(p: Bundle?) { runOnUiThread { tvStatus.text = "Sun raha hoon..." } }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { runOnUiThread { tvStatus.text = "Tap to speak"; listening = false } }
            override fun onResults(r: Bundle?) {
                val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                runOnUiThread { tvStatus.text = "Ap ne kaha: $text"; listening = false }
                if (text.isNotBlank()) handleCommand(text)
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
        tvStatus.text = "Tap to speak"
    }

    private fun handleCommand(text: String) {
        val lower = text.lowercase()
        val reply = when {
            lower.contains("salam") || lower.contains("hello") || lower.contains("hey") ->
                "Walaikum Assalam Ahaan! Kya hukm hai?"
            lower.contains("time") || lower.contains("waqt") || lower.contains("baj") -> {
                val t = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date())
                "Abhi $t baj rahe hain."
            }
            lower.contains("name") || lower.contains("naam") ->
                "Main Jarvis hoon, Ahaan ka personal assistant!"
            else -> "Ap ne kaha: $text. Main abhi seekh raha hoon!"
        }
        speak(reply)
    }

    private fun speak(text: String) {
        recognizer?.cancel()
        listening = false
        tvStatus.text = "Bol raha hoon..."
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
