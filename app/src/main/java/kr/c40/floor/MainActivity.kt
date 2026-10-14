package kr.c40.floor

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kr.c40.floor.core.RealGateway
import kr.c40.floor.core.RobotEndpoints
import kr.c40.floor.core.RobotGateway
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 1단계: 읽기 전용 상태 조회 화면. GET 만 보내고 결과를 그대로 보여 준다. */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var out: TextView
    private lateinit var button: Button

    private val endpoints: RobotEndpoints by lazy {
        val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true)
        if (emulator) RobotEndpoints.EMULATOR else RobotEndpoints.ROBOT
    }

    private data class Probe(val kind: String, val path: String, val maxChars: Int = 1500)

    private val probes = listOf(
        Probe("HTTP", "/mapFloorList"),
        Probe("HTTP", "/getCurrentLoadMapInfo", 300),
        Probe("COAP", "/position/status"),
        Probe("COAP", "/position/robotPosInfo"),
        Probe("COAP", "/clean/status"),
        Probe("COAP", "/charge/status"),
        Probe("COAP", "/navigation/dest_posesV2", 4000),
        Probe("COAP", "/task/status"),
        Probe("COAP", "/clean/positionStatus"),
        Probe("HTTP", "/listCurrentMapNavigationPoints", 1500),
    )

    private fun header() = "대상: ${endpoints.httpBase} / coap://${endpoints.coapHost}:${endpoints.coapPort}\n"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        button = Button(this).apply {
            text = "상태 조회 (읽기 전용)"
            setOnClickListener { runProbes() }
        }
        out = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            text = header()
        }
        val switchButton = Button(this).apply {
            text = "층 전환 화면 (기본 dry-run)"
            setOnClickListener { startActivity(Intent(this@MainActivity, SwitchActivity::class.java)) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(switchButton)
            addView(button)
            addView(ScrollView(this@MainActivity).apply { addView(out) })
        }
        setContentView(root)
    }

    private fun runProbes() {
        button.isEnabled = false
        out.text = header()
        io.execute {
            val gw: RobotGateway = RealGateway(endpoints)
            val log = StringBuilder()
            for (p in probes) {
                val r = if (p.kind == "HTTP") gw.httpGet(p.path) else gw.coapGet(p.path)
                val body = if (r.body.length > p.maxChars) r.body.take(p.maxChars) + "...(총 ${r.body.length}자)" else r.body
                val block = "\n[${p.kind} GET ${p.path}] ${r.code} ${r.millis}ms\n$body\n"
                log.append("\n[${p.kind} GET ${p.path}] ${r.code} ${r.millis}ms\n${r.body}\n")
                runOnUiThread { out.append(block) }
            }
            saveLog(log.toString())
            runOnUiThread { button.isEnabled = true }
        }
    }

    /** `adb pull /sdcard/Android/data/kr.c40.floor/files/` 로 회수할 수 있게 저장한다. */
    private fun saveLog(text: String) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "probe_$name.txt").writeText("endpoints=$endpoints\n$text")
        } catch (_: Exception) {
        }
    }
}
