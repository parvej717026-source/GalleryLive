package com.newsnest.gallerylive

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpFromFile
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity(), ConnectCheckerRtmp, VideoDecoderInterface, AudioDecoderInterface {

    private lateinit var userToken: EditText
    private lateinit var title: EditText
    private lateinit var status: TextView
    private lateinit var selected: TextView
    private lateinit var pagesBox: LinearLayout
    private lateinit var btn: Button
    private var videoUri: Uri? = null
    private var logoBmp: Bitmap? = null
    private var page: Page? = null
    private var stream: RtmpFromFile? = null
    private var liveId: String? = null
    private var liveToken = ""
    @Volatile private var running = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        userToken = EditText(this).apply {
            hint = "Access Token"
            setText(prefs.getString("tok", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val load = Button(this).apply { text = "পেজ আনুন"; setOnClickListener { loadPages(prefs) } }
        pagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        selected = TextView(this).apply { text = "কোনো পেজ বাছা হয়নি"; textSize = 16f; setPadding(0, 16, 0, 8) }
        title = EditText(this).apply { hint = "লাইভের টাইটেল"; setText(prefs.getString("title", "")) }
        val pickVideo = Button(this).apply { text = "গ্যালারি থেকে ভিডিও বাছুন"; setOnClickListener { pick(1, "video/*") } }
        val pickLogo = Button(this).apply { text = "অন্য লোগো দিন (ঐচ্ছিক)"; setOnClickListener { pick(2, "image/*") } }
        btn = Button(this).apply { text = "লাইভ শুরু"; setOnClickListener { if (running) stopLive() else startLive(prefs) } }
        status = TextView(this).apply { text = "প্রস্তুত"; setTextColor(Color.DKGRAY); setPadding(0, 24, 0, 0) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            listOf(userToken, load, pagesBox, selected, title, pickVideo, pickLogo, btn, status).forEach { addView(it) }
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun loadPages(prefs: android.content.SharedPreferences) {
        val tk = userToken.text.toString().trim()
        if (tk.isEmpty()) return say("আগে Access Token দিন")
        prefs.edit().putString("tok", tk).apply()
        say("পেজ আনা হচ্ছে...")
        thread {
            try {
                val list = FacebookLive.pages(tk)
                runOnUiThread {
                    pagesBox.removeAllViews()
                    list.forEach { addRow(it) }
                    status.text = "${list.size}টি পেজ পাওয়া গেছে। একটি বাছুন"
                }
            } catch (e: Exception) { say("ভুল: ${e.message}") }
        }
    }

    private fun addRow(p: Page) {
        val img = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 120) }
        val name = TextView(this).apply { text = p.name; textSize = 17f; setPadding(24, 0, 0, 0) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
            addView(img); addView(name)
            setOnClickListener { choose(p) }
        }
        pagesBox.addView(row)
        if (p.logoUrl.isNotEmpty()) thread {
            val bmp = FacebookLive.bitmap(p.logoUrl)
            runOnUiThread { img.setImageBitmap(bmp) }
        }
    }

    private fun choose(p: Page) {
        page = p
        selected.text = "বাছাই: ${p.name}"
        if (title.text.isNullOrBlank()) title.setText(p.name)
        say("লোগো আনা হচ্ছে...")
        thread {
            logoBmp = FacebookLive.bitmap(p.logoUrl)
            say(if (logoBmp != null) "পেজের লোগো তৈরি" else "লোগো পাওয়া যায়নি")
        }
    }

    private fun pick(code: Int, type: String) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); this.type = type }
        startActivityForResult(i, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val u = data?.data
        if (res != RESULT_OK || u == null) return
        if (req == 1) { videoUri = u; say("ভিডিও বাছাই হয়েছে") }
        if (req == 2) {
            logoBmp = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
            say("নতুন লোগো বাছাই হয়েছে")
        }
    }

    private fun say(s: String) = runOnUiThread { status.text = s }

    private fun startLive(prefs: android.content.SharedPreferences) {
        val vUri = videoUri ?: return say("আগে ভিডিও বাছুন")
        val p = page ?: return say("আগে পেজ বাছুন")
        val t = title.text.toString().trim()
        prefs.edit().putString("title", t).apply()
        liveToken = p.token
        running = true
        btn.text = "লাইভ বন্ধ"
        say("লাইভ তৈরি হচ্ছে...")
        thread {
            try {
                val file = File(cacheDir, "live.mp4")
                contentResolver.openInputStream(vUri)!!.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                val (id, url) = FacebookLive.create(p.id, p.token, t.ifEmpty { p.name })
                liveId = id
                runOnUiThread {
                    val s = RtmpFromFile(this, this, this)
                    stream = s
                    val ok = s.prepareVideo(file.absolutePath, 4_000_000) &&
                            s.prepareAudio(file.absolutePath, 128 * 1024)
                    if (!ok) { say("ভিডিও প্রস্তুত করা যায়নি"); stopLive(); return@runOnUiThread }
                    addOverlays(s, t)
                    s.startStream(url)
                }
            } catch (e: Exception) {
                say("ভুল: ${e.message}")
                runOnUiThread { stopLive() }
            }
        }
    }

    private fun addOverlays(s: RtmpFromFile, t: String) {
        val gl = s.glInterface
        logoBmp?.let { bmp ->
            val f = ImageObjectFilterRender()
            gl.addFilter(f)
            f.setImage(bmp)
            f.setScale(15f, 15f)
            f.setPosition(TranslateTo.TOP_RIGHT)
        }
        if (t.isNotEmpty()) {
            val f = TextObjectFilterRender()
            gl.addFilter(f)
            f.setText(t, 28f, Color.WHITE)
            f.setScale(50f, 8f)
            f.setPosition(TranslateTo.BOTTOM)
        }
    }

    private fun stopLive() {
        if (!running) return
        running = false
        try { stream?.stopStream() } catch (_: Exception) { }
        stream = null
        val id = liveId; val tk = liveToken
        liveId = null
        if (id != null) thread { FacebookLive.end(id, tk) }
        btn.text = "লাইভ শুরু"
        say("লাইভ বন্ধ হয়েছে")
    }

    override fun onVideoDecoderFinished() { runOnUiThread { stopLive() } }
    override fun onAudioDecoderFinished() { }

    override fun onConnectionStartedRtmp(rtmpUrl: String) { say("সংযোগ হচ্ছে...") }
    override fun onConnectionSuccessRtmp() { say("🔴 লাইভ চলছে") }
    override fun onConnectionFailedRtmp(reason: String) { say("সংযোগ ব্যর্থ: $reason"); runOnUiThread { stopLive() } }
    override fun onNewBitrateRtmp(bitrate: Long) { }
    override fun onDisconnectRtmp() { }
    override fun onAuthErrorRtmp() { say("Auth ভুল") }
    override fun onAuthSuccessRtmp() { }
}
