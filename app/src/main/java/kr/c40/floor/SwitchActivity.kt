package kr.c40.floor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.c40.floor.core.DryRunCommands
import kr.c40.floor.core.FloorSwitchSequencer
import kr.c40.floor.core.FloorTarget
import kr.c40.floor.core.GatewayRobotReader
import kr.c40.floor.core.Landmark
import kr.c40.floor.core.LiveCommands
import kr.c40.floor.core.RealGateway
import kr.c40.floor.core.RobotEndpoints
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 층 전환 화면. 기본은 dry-run(요청을 보내지 않고 내용만 표시)이며,
 * "실제 전송 허용"을 직접 켠 경우에만 로봇에 위치 지정 요청을 보낸다. 청소는 시작하지 않는다.
 */
class SwitchActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var allowWrites: CheckBox
    private lateinit var floorsBox: LinearLayout
    private lateinit var log: TextView
    private lateinit var openClean: Button

    private val endpoints: RobotEndpoints by lazy {
        val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true)
        if (emulator) RobotEndpoints.EMULATOR else RobotEndpoints.ROBOT
    }
    private val gateway by lazy { RealGateway(endpoints) }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        allowWrites = CheckBox(this).apply { text = "실제 전송 허용 (끄면 dry-run: 아무것도 보내지 않음)" }
        floorsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        log = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        openClean = Button(this).apply {
            text = "청소 앱 열기"
            isEnabled = false
            setOnClickListener {
                packageManager.getLaunchIntentForPackage("com.keenon.peanut.clean")?.let { startActivity(it) }
                    ?: append("청소 앱(com.keenon.peanut.clean)을 찾지 못했습니다")
            }
        }
        val refresh = Button(this).apply { text = "층/지표점 새로고침"; setOnClickListener { loadFloors() } }

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@SwitchActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad, pad, pad)
                        addView(TextView(this@SwitchActivity).apply { text = "대상: ${endpoints.httpBase} / coap://${endpoints.coapHost}:${endpoints.coapPort}" })
                        addView(allowWrites)
                        addView(refresh)
                        addView(floorsBox)
                        addView(openClean)
                        addView(log)
                    },
                )
            },
        )
        loadFloors()
    }

    private fun loadFloors() {
        floorsBox.removeAllViews()
        append("층/지표점 조회 중...")
        io.execute {
            val reader = GatewayRobotReader(gateway)
            val floors = reader.floors()
            val landmarks = reader.landmarks()
            runOnUiThread {
                floorsBox.removeAllViews()
                if (floors == null || landmarks == null) {
                    append("로봇에서 층/지표점을 읽지 못했습니다")
                    return@runOnUiThread
                }
                for (f in floors.map { it.floor }.sorted()) {
                    val lm: Landmark? = landmarks.firstOrNull { it.floor == f }
                    floorsBox.addView(
                        Button(this).apply {
                            text = if (lm != null) "${f}층으로 전환  (지표점: ${lm.name})" else "${f}층  (지표점 없음)"
                            isEnabled = lm != null
                            setOnClickListener { lm?.let { switchTo(FloorTarget(f, it.id)) } }
                        },
                    )
                }
                append("층 ${floors.size}개, 지표점 ${landmarks.size}개")
            }
        }
    }

    private fun switchTo(target: FloorTarget) {
        if (busy) return
        busy = true
        val live = allowWrites.isChecked
        openClean.isEnabled = false
        append("\n=== ${target.floor}층 전환 시작 (${if (live) "실제 전송" else "dry-run"}) ===")
        io.execute {
            val dry = DryRunCommands()
            val commands = if (live) LiveCommands(gateway) else dry
            val sequencer = FloorSwitchSequencer(GatewayRobotReader(gateway), commands) { step ->
                runOnUiThread { append("[${step.step.label}] ${if (step.ok) "OK" else "실패"}: ${step.message}") }
            }
            val result = sequencer.run(target) { text ->
                askUi((if (live) "실제로 로봇에 위치 지정을 보냅니다.\n\n" else "") + text)
            }
            runOnUiThread {
                if (result.dryRun) dry.recorded.forEach { append("(보냈다면) $it") }
                append(if (result.success) "=== 완료: ${if (result.dryRun) "dry-run" else "준비 완료. 청소는 시작하지 않았습니다"} ===" else "=== 중단: ${result.failedAt?.label} ===")
                openClean.isEnabled = result.success && !result.dryRun
                busy = false
            }
        }
    }

    /** 백그라운드 스레드에서 호출해 UI 확인 창의 답을 기다린다. */
    private fun askUi(message: String): Boolean {
        val latch = CountDownLatch(1)
        var answer = false
        runOnUiThread {
            AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("전환") { _, _ -> answer = true; latch.countDown() }
                .setNegativeButton("취소") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        return answer
    }

    private fun append(text: String) {
        log.append(text + "\n")
    }
}
