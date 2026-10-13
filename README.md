# 🤖 Jarvis — Voice Assistant (Android)

A native Android voice assistant app like Iron Man's Jarvis. Wake it with **"Hey Jarvis"** and control WhatsApp, SMS, calls, YouTube, Google search — plus an AI brain that chats in fluent Roman Urdu.

**Package:** `com.ahaan.jarvis`

---

## ✨ Features

- 🎤 **Wake word** — say "Hey Jarvis" / "Jarvis", no button needed
- 🗣️ **Urdu voice** — speaks in natural Roman Urdu (ur-PK, rate 0.95)
- 💬 **WhatsApp** — *"whatsapp salaam to 03121234567"* → opens chat with pre-filled text
- 📩 **SMS** — *"sms meeting at 5 to 03121234567"* → opens SMS app ready to send
- 📞 **Calls** — *"call 03121234567"* → opens dialer (you confirm — safer)
- 📺 **YouTube** — *"youtube lo-fi music"* → opens search results
- 🔍 **Google** — *"google best biryani karachi"* → opens Google search
- 🕙 **Time/Date** in Urdu — *"time kya hai"*
- 🧮 **Calculator** — *"calculate 25 * 4 + 10"*
- 🧠 **AI chat** — anything else goes to Pollinations AI (free, no key)

> **Note:** Android security requires the user to tap Send/Call for WhatsApp, SMS and calls.
> The app prepares everything — one tap confirms. Full auto-send is blocked by Android on purpose.

---

## 🛠️ Open in Android Studio

1. Install **Android Studio** (Hedgehog or newer) with **JDK 17**
2. `File → Open` → select the `jarvis-android` folder
3. Let Gradle sync finish (downloads dependencies automatically)
4. Connect your Android phone (USB debugging ON) or start an emulator
5. Press ▶ **Run**

### Requirements
- Android Studio with Android SDK **API 34**
- Kotlin plugin **2.0.20** (Gradle handles it)
- Min supported phone: **Android 7.0 (API 24)**

---

## 📦 Build the APK

**Debug APK** (for testing on your phone):
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

**Release APK / AAB** (for Play Store — AAB is required):
```bash
./gradlew bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

> Before release build: create a signing key in Android Studio
> (`Build → Generate Signed Bundle/APK → Create new keystore`) and add it to `app/build.gradle`
> under `signingConfigs`.

---

## 🚀 Publish to Play Store

### 1. Get a Play Developer account — **$25 one-time**
- Go to [play.google.com/console](https://play.google.com/console)
- Sign up with a Google account, pay **$25** (one-time, lifetime)
- Complete identity verification

### 2. Create the app listing
- Console → **Create app** → name: **Jarvis**, language: English (US)
- Fill in: short description, full description, screenshots (phone), feature graphic (1024×500), app icon (512×512)

### 3. Content & policy
- **App category:** Tools / Productivity
- **Data safety form:** declare microphone, contacts, phone, SMS usage — explain *why* (voice commands, calling/messaging on user request)
- **Permissions declaration:** `CALL_PHONE` and `SEND_SMS` are *restricted* permissions — Google will ask for a video demo showing core functionality needs them. Since the app only opens the dialer/SMS composer (doesn't auto-send), consider whether you even need to declare them, or keep `ACTION_DIAL`/`ACTION_SENDTO` (no declaration needed).
- Target audience, news app declaration, etc. as prompted

### 4. Upload the AAB
- **Testing → Internal testing** → upload `app-release.aab` first
- Test on your own phone via the internal test link
- Then **Production → Create new release** → upload AAB → **Rollout**

### 5. Review
- Google reviews (usually 1–7 days). Fix any policy feedback, resubmit.

### ⚠️ Play Store tips for this app
- The **privacy policy URL is mandatory** — host a simple page explaining what data the app uses (voice audio is processed on-device by Google Speech; AI questions go to pollinations.ai)
- Don't claim the app can "auto-send" messages — describe it as *voice-prepared* messaging
- Record the **permissions demo video** early if you keep CALL_PHONE/SEND_SMS

---

## 📁 Project structure

```
jarvis-android/
├── settings.gradle
├── build.gradle
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/ahaan/jarvis/
        │   └── MainActivity.kt      # Everything: recognition, TTS, commands, AI
        └── res/
            ├── layout/activity_main.xml
            ├── values/{strings,colors,themes}.xml
            ├── drawable/{arc_reactor,ic_launcher_foreground}.xml
            └── mipmap-anydpi-v26/ic_launcher{,_round}.xml
```

## 🔑 Key implementation notes

- **Wake word:** `SpeechRecognizer` runs continuously; partial + final results are scanned for "jarvis"/"hey jarvis". After wake, a 10-second window listens for the command.
- **No self-hearing:** recognition is cancelled while TTS speaks, and restarted via `UtteranceProgressListener.onDone`.
- **AI:** `https://text.pollinations.ai/` POST with OpenAI-style `messages` JSON via OkHttp. System prompt forces fluent Roman Urdu, 2–3 sentences.
- **Calculator:** tiny recursive-descent parser (`+ - * / ( )`, decimals) — no `eval`, no scripting engine.
- **Urdu TTS:** `Locale("ur","PK")`, falls back to `Locale("ur")`, then device default. Speech rate `0.95`.
