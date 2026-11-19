package com.github.digitallyrefined.androidipcamera.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.github.digitallyrefined.androidipcamera.helpers.LgG6Main0Profile
import com.github.digitallyrefined.androidipcamera.helpers.LgMain0ImageBenchmark
import java.io.File
import java.util.concurrent.Executors

/**
 * Visible, opt-in Camera0 image laboratory. No LAN endpoint can start a test.
 * The MainActivity suspends streaming FIRST so only this Activity owns the sensor.
 */
class LgMain0BenchmarkActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var runner: LgMain0ImageBenchmark? = null
    private var archive: File? = null
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button
    private lateinit var preset: Spinner
    private lateinit var shape: Spinner
    @Volatile private var running = false

    private val saveArchive = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val source = archive ?: return@registerForActivityResult
        if (uri == null) { log("Export cancelled. Internal ZIP retained: ${source.absolutePath}"); return@registerForActivityResult }
        worker.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                } ?: error("Storage provider refused output stream")
                log("Saved ZIP to the selected folder: $uri")
            } catch (e: Exception) {
                log("Export failed: ${e.message}. Internal ZIP retained: ${source.absolutePath}")
            }
        }
    }

    private fun log(message: String) = runOnUiThread {
        if (!isFinishing && ::status.isInitialized) {
            val previous = status.text.toString()
            val lines = (previous + "\n" + message).lines().takeLast(35)
            status.text = lines.joinToString("\n").trim()
        }
    }

    private fun text(label: String, size: Float, color: Int = Color.BLACK): TextView =
        TextView(this).apply {
            this.text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setPadding(0, 8, 0, 8)
        }

    private fun spinner(values: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@LgMain0BenchmarkActivity,
            android.R.layout.simple_spinner_dropdown_item, values)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "LG G6 • Camera0 4.5× lab"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 16)
            setBackgroundColor(Color.rgb(247, 247, 247))
        }
        root.addView(text("Camera0 automatic 4.5× image benchmark", 20f))
        root.addView(text(
            "Camera stream is paused while testing. Uses maximum ACTUALLY accepted Camera2 " +
            "YUV resolution, then compares ISP sensor crop with software crop. " +
            "No root or uploads. Keep LG G6 fixed on a tripod with steady light.", 13f))
        root.addView(text("Test depth", 14f))
        preset = spinner(listOf("Quick • templates × ISP modes", "Deep • NR/EDGE, ISO, shutter, focus, AWB"))
        root.addView(preset)
        root.addView(text("Comparison images", 14f))
        shape = spinner(listOf("4:3 • full sensor proportions", "1:1 • centered square"))
        root.addView(shape)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        startButton = Button(this).apply { text = "Run test"; setOnClickListener { startBenchmark() } }
        stopButton = Button(this).apply { text = "Stop"; isEnabled = false; setOnClickListener {
            runner?.cancel(); log("Cancellation requested; finishing current frame.")
        } }
        saveButton = Button(this).apply { text = "Save ZIP"; isEnabled = false; setOnClickListener {
            archive?.let { saveArchive.launch(it.name) }
        } }
        for (button in listOf(startButton, stopButton, saveButton))
            actions.addView(button, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        root.addView(text("Note: this is a bounded sweep of advertised discrete modes and " +
            "representative continuous ISO/shutter/focus values. It cannot test literally " +
            "every real number. Heat limits and time limits stop testing safely.", 12f))
        status = text("Ready. Start after all other camera apps have closed.", 13f)
        val scroll = ScrollView(this).apply {
            addView(status)
            isFillViewport = false
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (!LgG6Main0Profile.isDevice()) {
            startButton.isEnabled = false
            log("This tool is intended only for LG-H872, rear Camera2 ID0.")
        }
    }

    private fun startBenchmark() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow camera permission first", Toast.LENGTH_LONG).show()
            return
        }
        val deep = preset.selectedItemPosition == 1
        val square = shape.selectedItemPosition == 1
        running = true
        archive = null
        startButton.isEnabled = false
        stopButton.isEnabled = true
        saveButton.isEnabled = false
        status.text = "Starting camera comparison..."
        worker.execute {
            val test = try { LgMain0ImageBenchmark(this) } catch (e: Exception) {
                log("Camera initialization error: ${e.message}")
                runOnUiThread { running = false; startButton.isEnabled = true; stopButton.isEnabled = false }
                return@execute
            }
            runner = test
            try {
                val result = test.run(deep, square, ::log)
                archive = result.archive
                log("FINISHED: ${result.completed}/${result.attempted} samples; stop=${result.stopReason}")
                log("Input YUV: ${result.imageSize.width}×${result.imageSize.height}; ZIP: ${result.archive.absolutePath}")
                runOnUiThread { saveButton.isEnabled = true }
            } catch (e: Exception) {
                log("Benchmark failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runner = null
                runOnUiThread {
                    running = false
                    stopButton.isEnabled = false
                    startButton.isEnabled = LgG6Main0Profile.isDevice()
                }
            }
        }
    }

    override fun onDestroy() {
        runner?.cancel()
        if (!running) worker.shutdown()
        super.onDestroy()
    }
}
