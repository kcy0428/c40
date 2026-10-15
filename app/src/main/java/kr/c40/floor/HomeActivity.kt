package kr.c40.floor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.c40.floor.Ui.bigButton
import kr.c40.floor.Ui.box
import kr.c40.floor.Ui.dp
import kr.c40.floor.Ui.setEnabledLook
import kr.c40.floor.Ui.text
import kr.c40.floor.core.DryRunCommands
import kr.c40.floor.core.FloorMap
import kr.c40.floor.core.FloorSwitchSequencer
import kr.c40.floor.core.SwitchResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 원터치 층 전환 첫 화면. 로봇을 다른 층으로 옮긴 뒤 그 층 카드를 한 번 누르면
 * 확인 창 하나를 거쳐 위치 지정 → 인식 확인 → 청소 앱 지도 확인까지 진행한다. 청소는 시작하지 않는다.
 */
class HomeActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var store: FloorConfigStore
    private lateinit var thumbs: MapThumbs

    private lateinit var statusChip: TextView
    private lateinit var modeBanner: TextView
    private lateinit var cardsRow: LinearLayout
    private lateinit var message: TextView

    private var snap: RobotSnapshot? = null
    private var tick = 0
    private var busy = false
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FloorConfigStore(this)
        thumbs = MapThumbs(this)

        statusChip = text("로봇 상태 확인 중…", 18f, Ui.MUTED, bold = true).apply {
            background = box(Ui.SURFACE, 20, 2, Ui.LINE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val settings = Button(this).apply {
            text = "설정"
            textSize = 18f
            isAllCaps = false
            setTextColor(Ui.INK)
            background = box(Ui.SURFACE, 20, 2, Ui.LINE)
            stateListAnimator = null
            setOnClickListener { if (!busy) startActivity(Intent(this@HomeActivity, SetupActivity::class.java)) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("층 전환", 30f, bold = true))
                addView(text("로봇을 옮긴 층을 한 번 누르세요", 17f, Ui.MUTED))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusChip)
            addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { leftMargin = dp(12) })
        }
        modeBanner = text("", 16f, Ui.WARN, bold = true).apply {
            background = box(Ui.WARN_BG, 12)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        cardsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        message = text("", 16f, Ui.MUTED)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(28), dp(22), dp(28), dp(18))
            addView(header)
            addView(modeBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(cardsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
            addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        })
    }

    override fun onResume() {
        super.onResume()
        renderMode()
        tick = 0
        polling = true
        poll()
    }

    override fun onPause() {
        super.onPause()
        polling = false
        ui.removeCallbacksAndMessages(null)
    }

    /** 3초마다 위치를, 15초마다 층/지표점 목록과 지도 그림을 다시 읽는다. 지도가 바뀌면 화면도 따라 바뀐다. */
    private fun poll() {
        if (!polling) return
        val withLists = tick % 5 == 0
        tick++
        io.execute {
            val s = RobotSnapshot.read(withLists, snap)
            if (withLists) cacheCurrentMapPic(s.floors)
            ui.post {
                snap = s
                renderStatus()
                if (withLists) renderCards()
                if (polling) ui.postDelayed({ poll() }, 3000)
            }
        }
    }

    /** 청소 앱에 올라간 지도 그림이 아직 없으면 저장해 둔다 (지도를 새로 만들어도 자동으로 따라감). */
    private fun cacheCurrentMapPic(floors: List<FloorMap>?) {
        if (floors == null) return
        val knownIds = floors.map { it.mapId }.toSet()
        if (knownIds.all { thumbs.has(it) }) return
        val (id, pic) = Robot.currentMapPic() ?: return
        if (id in knownIds && !thumbs.has(id)) runCatching { thumbs.storePic(id, pic) }
    }

    private fun renderMode() {
        modeBanner.text = if (store.liveSend) "실제 전송 모드 — 누르면 로봇의 위치가 바뀝니다"
        else "시험 모드 — 로봇에 아무것도 보내지 않습니다 (설정에서 실제 전송을 켜세요)"
        modeBanner.setTextColor(if (store.liveSend) Ui.ACCENT else Ui.WARN)
        modeBanner.background = box(if (store.liveSend) Ui.SOFT else Ui.WARN_BG, 12)
    }

    private fun renderStatus() {
        val s = snap ?: return
        val pos = s.pos
        val (txt, color) = when {
            pos == null -> "로봇 연결 안 됨" to Ui.BAD
            s.located -> "현재 ${pos.floor}층 · 위치 확정" to Ui.OK
            else -> "현재 ${pos.floor}층 · 위치 확인 중" to Ui.WARN
        }
        statusChip.text = txt
        statusChip.setTextColor(color)
    }

    private fun renderCards() {
        val s = snap ?: return
        val floors = s.floors
        cardsRow.removeAllViews()
        if (floors == null) {
            message.text = "로봇에서 층 목록을 읽지 못했습니다. 청소 앱이 켜져 있는지 확인하세요."
            return
        }
        message.text = ""
        floors.sortedBy { it.floor }.forEachIndexed { i, fm ->
            cardsRow.addView(card(fm, s), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = dp(20)
            })
        }
    }

    private fun card(fm: FloorMap, s: RobotSnapshot): View {
        val isCurrent = s.pos?.floor == fm.floor
        val spot = store.get(fm.floor)
        val spotValid = spot != null && spot.mapId == fm.mapId

        val mapView = MapThumbView(this)
        mapView.spot = if (spotValid) spot?.pose else null
        io.execute {
            val t = thumbs.load(fm.mapId)
            ui.post { mapView.thumb = t; if (t == null) mapView.emptyText = "이 층 지도를 청소 앱에서 한 번 열면 미리보기가 생깁니다" }
        }

        val spotText = when {
            spot == null -> "위치 지정 방법 미설정 — 설정에서 저장하세요"
            !spotValid -> "지도가 새로 만들어져 다시 설정이 필요합니다"
            else -> spot.describe()
        }
        val button = bigButton(if (isCurrent) "${fm.floor}층에서 위치 다시 잡기" else "${fm.floor}층으로 전환").apply {
            setEnabledLook(spotValid && !busy)
            setOnClickListener { spot?.let { start(it) } }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(Ui.SURFACE, 22, 2, if (isCurrent) Ui.INK else Ui.LINE)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text("${fm.floor}층", 34f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (isCurrent) addView(text("현재 위치", 15f, Ui.SURFACE, bold = true).apply {
                    background = box(Ui.INK, 14)
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                })
            })
            addView(mapView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(10) })
            addView(text(spotText, 16f, if (spotValid) Ui.MUTED else Ui.WARN))
            addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(12) })
        }
    }

    private fun start(spot: FloorSpot) {
        if (busy) return
        busy = true
        renderCards()
        val live = store.liveSend
        val progress = ProgressPanel(spot.floor, live)

        io.execute {
            val commands = Robot.commands(live)
            val sequencer = FloorSwitchSequencer(Robot.reader, commands) { step ->
                ui.post { progress.step(step.step.label, step.ok, step.message) }
            }
            val result = sequencer.run(spot.toTarget()) { text ->
                val ok = askUi(
                    (if (live) "" else "[시험 모드] 실제로는 보내지 않습니다.\n\n") +
                        text + "\n\n" + if (spot.pose != null) "로봇을 ${spot.floor}층 바닥 표시 자리에 표시된 방향으로 놓았는지 확인하세요."
                        else "로봇을 ${spot.floor}층 지표점 자리에 놓았는지 확인하세요.",
                )
                if (ok) ui.post { progress.show() }
                ok
            }
            val recorded = (commands as? DryRunCommands)?.recorded.orEmpty()
            ui.post {
                progress.finish(result, recorded)
                busy = false
                renderCards()
            }
        }
    }

    /** 백그라운드 스레드에서 호출해 확인 창의 답을 기다린다. */
    private fun askUi(msg: String): Boolean {
        val latch = CountDownLatch(1)
        var answer = false
        ui.post {
            AlertDialog.Builder(this)
                .setTitle("층 전환")
                .setMessage(msg)
                .setPositiveButton("전환") { _, _ -> answer = true; latch.countDown() }
                .setNegativeButton("취소") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        return answer
    }

    /** 진행 단계를 보여 주는 창. 끝나면 결과와 버튼을 붙인다. */
    private inner class ProgressPanel(private val floor: Int, private val live: Boolean) {
        private val lines = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
        private val title = text("${floor}층으로 전환 중…", 24f, bold = true)
        private val buttons = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        private val pending = mutableListOf<Triple<String, Boolean, String>>()
        private var dialog: AlertDialog? = null

        fun show() {
            val content = LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(20))
                addView(title)
                addView(lines, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
                addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
            }
            dialog = AlertDialog.Builder(this@HomeActivity).setView(content).setCancelable(false).show()
            pending.forEach { addLine(it.first, it.second, it.third) }
            pending.clear()
        }

        fun step(label: String, ok: Boolean, msg: String) {
            if (dialog == null) pending += Triple(label, ok, msg) else addLine(label, ok, msg)
        }

        private fun addLine(label: String, ok: Boolean, msg: String) {
            // dry-run 요청 본문은 끝에 "보냈다면" 으로 한 번만 보여 준다.
            val shown = if (msg.startsWith("dry-run: ")) "시험 모드라 보내지 않음" else msg
            lines.addView(text("${if (ok) "✓" else "✗"}  $label — $shown", 17f, if (ok) Ui.INK else Ui.BAD).apply {
                setPadding(0, dp(4), 0, dp(4))
            })
        }

        fun finish(r: SwitchResult, recorded: List<String>) {
            if (dialog == null) {
                // 확인 창에서 취소했거나 사전 점검에서 멈춘 경우: 창 없이 이유만 알린다.
                if (!r.success && r.failedAt?.name != "CONFIRM") {
                    AlertDialog.Builder(this@HomeActivity).setTitle("전환할 수 없습니다").setMessage(r.summary)
                        .setPositiveButton("확인", null).show()
                }
                return
            }
            title.text = when {
                r.success && r.dryRun -> "시험 완료 (실제로는 보내지 않음)"
                r.success -> "${floor}층 준비 완료"
                else -> "전환 실패 — ${r.failedAt?.label}"
            }
            title.setTextColor(if (r.success) Ui.OK else Ui.BAD)
            recorded.forEach { lines.addView(text("보냈다면: $it", 13f, Ui.MUTED)) }
            if (!r.success) lines.addView(text("로봇은 그대로입니다. 청소 앱의 '위치 선택'으로 직접 위치를 잡을 수 있습니다.", 16f, Ui.MUTED))

            val close = bigButton("닫기", Ui.SOFT, Ui.INK).apply { setOnClickListener { dialog?.dismiss() } }
            buttons.addView(close, LinearLayout.LayoutParams(0, dp(60), 1f))
            if (r.success && live) {
                val clean = bigButton("청소 앱 열기").apply {
                    setOnClickListener {
                        dialog?.dismiss()
                        packageManager.getLaunchIntentForPackage("com.keenon.peanut.clean")?.let { startActivity(it) }
                    }
                }
                buttons.addView(clean, LinearLayout.LayoutParams(0, dp(60), 1f).apply { leftMargin = dp(12) })
            }
            buttons.visibility = View.VISIBLE
        }
    }
}
