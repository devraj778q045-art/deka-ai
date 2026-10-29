package com.deka.ai

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DekaAdminReceiver : DeviceAdminReceiver()

class MainActivity : Activity() {

    private val MODEL = "claude-sonnet-5-5"

    private val SYSTEM_PROMPT = """
तुम "डेका AI" हो, मेरे मोबाइल में रहने वाला पर्सनल AI असिस्टेंट।

भाषा और लहजा:
- मैं जिस भाषा में बोलूँ (हिंदी, अंग्रेज़ी या हिंग्लिश), उसी में जवाब दो।
- दोस्ताना, विनम्र और सरल रहो। जवाब छोटे रखो, पहले सीधा जवाब दो।

आम मदद:
सवालों के जवाब, लिखना, पढ़ाई, प्लानिंग, कोडिंग, रिसर्च और रोज़मर्रा के काम। जटिल बात स्टेप-बाय-स्टेप और उदाहरण के साथ समझाओ।

फ़ोन कंट्रोल:
जब मैं फ़ोन से जुड़ा कोई काम कहूँ, तो जवाब के आखिर में एक अलग लाइन में सिर्फ़ यह JSON दो:
{"action": "OPEN_APP", "target": "ऐप का नाम"}
{"action": "LOCK_SCREEN"}
{"action": "CALL", "target": "फ़ोन नंबर"}
{"action": "SET_ALARM", "target": "HH:MM (24 घंटे वाला समय)"}

नियम:
- सिर्फ़ ऊपर लिखे एक्शन इस्तेमाल करो। फ़ोन पूरा बंद करना तुम्हारे बस में नहीं है। कहा जाए तो साफ़ बताओ और स्क्रीन लॉक का विकल्प दो।
- कॉल के लिए नंबर चाहिए। अगर मैं सिर्फ़ नाम बोलूँ, तो नंबर पूछो।
- पैसे भेजना, डेटा डिलीट करना या मैसेज भेजना जैसे काम करने से पहले मुझसे "हाँ" की पुष्टि लो।
- अगर काम साफ़ नहीं है, तो एक छोटा सवाल पूछो।

ईमानदारी और सुरक्षा:
- जो पता नहीं, साफ़ कहो। अंदाज़े को तथ्य मत बताओ।
- मेरी योजना में गलती दिखे तो विनम्रता से बताओ।
- गैरकानूनी या नुकसानदेह काम में मदद मत करो।
- मेरी निजी जानकारी गोपनीय रखो।
""".trimIndent()

    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private val history = JSONArray()

    private fun prefs() = getSharedPreferences("deka", MODE_PRIVATE)
    private fun apiKey(): String = prefs().getString("key", "") ?: ""

    private fun askKey() {
        val et = EditText(this)
        et.hint = "sk-ant-..."
        AlertDialog.Builder(this)
            .setTitle("Anthropic API key")
            .setView(et)
            .setPositiveButton("सेव") { _, _ ->
                prefs().edit().putString("key", et.text.toString().trim()).apply()
            }
            .setNegativeButton("रद्द", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 24)

        scroll = ScrollView(this)
        chat = TextView(this)
        chat.textSize = 16f
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        input = EditText(this)
        input.hint = "डेका AI से कुछ भी कहिए..."

        val keyBtn = Button(this)
        keyBtn.text = "🔑"
        keyBtn.setOnClickListener { askKey() }
        val micBtn = Button(this)
        micBtn.text = "🎤"
        micBtn.setOnClickListener { startVoice() }
        val sendBtn = Button(this)
        sendBtn.text = "भेजें"
        sendBtn.setOnClickListener { send() }

        val row = LinearLayout(this)
        row.addView(keyBtn)
        row.addView(micBtn)
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sendBtn)
        root.addView(row)
        setContentView(root)

        append("डेका AI: नमस्ते! मैं आपकी क्या मदद करूँ?")
        if (apiKey().isEmpty()) askKey()
    }

    private fun append(text: String) {
        chat.append(text + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send() {
        if (apiKey().isEmpty()) { askKey(); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        append("आप: $text")
        history.put(JSONObject().put("role", "user").put("content", text))
        Thread {
            try {
                val reply = callApi()
                runOnUiThread { handleReply(reply) }
            } catch (e: Exception) {
                runOnUiThread {
                    history.remove(history.length() - 1)
                    append("त्रुटि: ${e.message}")
                }
            }
        }.start()
    }

    private fun callApi(): String {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1000)
            .put("system", SYSTEM_PROMPT)
            .put("messages", history)
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", apiKey())
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val resp = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) throw Exception(resp)
        return JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun handleReply(reply: String) {
        history.put(JSONObject().put("role", "assistant").put("content", reply))
        val lines = reply.lines()
        val actionLine = lines.lastOrNull { it.trim().startsWith("{\"action\"") }
        val shown = lines.filter { it != actionLine }.joinToString("\n").trim()
        append("डेका AI: $shown")
        if (actionLine != null) {
            try {
                runAction(JSONObject(actionLine.trim()))
            } catch (e: Exception) {
                append("एक्शन नहीं चल पाया: ${e.message}")
            }
        }
    }

    private fun runAction(a: JSONObject) {
        val target = a.optString("target")
        when (a.optString("action")) {
            "OPEN_APP" -> openApp(target)
            "LOCK_SCREEN" -> lockScreen()
            "CALL" -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(target))))
            "SET_ALARM" -> {
                val p = target.split(":")
                startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, p[0].trim().toInt())
                    .putExtra(AlarmClock.EXTRA_MINUTES, p[1].trim().toInt()))
            }
        }
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(q, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
        val launch = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launch != null) startActivity(launch) else append("ऐप नहीं मिला: $name")
    }

    private fun lockScreen() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn = ComponentName(this, DekaAdminReceiver::class.java)
        if (dpm.isAdminActive(cn)) {
            dpm.lockNow()
        } else {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn))
        }
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        startActivityForResult(i, 1)
    }

    @Deprecated("simple approach")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                input.setText(text)
                send()
            }
        }
    }
}
