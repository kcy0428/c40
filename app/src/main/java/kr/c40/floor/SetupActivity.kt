package kr.c40.floor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.c40.floor.Ui.bigButton
import kr.c40.floor.Ui.box
import kr.c40.floor.Ui.dp
import kr.c40.floor.Ui.setEnabledLook
import kr.c40.floor.Ui.text
import kr.c40.floor.core.FloorMap
import kr.c40.floor.core.Pose2D
import java.util.concurrent.Executors

/**
 * 층별 위치 지정 방법 설정. 로봇에는 아무것도 보내지 않는다 (읽기만 한다).
 * - 기준 위치: 로봇을 그 층의 표시된 자리에 두고 위치가 맞게 잡혀 있을 때 "지금 위치 저장"
 * - 지표점: 지도 관리 앱에 등록한 지표점이 있으면 그것을 고른다
 */
class SetupActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: FloorConfigStore
    private lateinit var live: CheckBox
    private lateinit var state: TextView
    private lateinit var floorsBox: LinearLayout
    private var snap: RobotSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FloorConfigStore(this)

        live = CheckBox(this).apply {
            text = "실제 전송 (끄면 시험 모드: 로봇에 아무것도 보내지 않음)"
            textSize = 18f
            isChecked = store.liveSend
            setOnCheckedChangeListener { _, on ->
                if (!on) { store.liveSend = false; return@setOnCheckedChangeListener }
                AlertDialog.Builder(this@SetupActivity)
                    .setMessage("실제 전송을 켜면 층 버튼을 누를 때 로봇의 층과 위치가 바뀝니다.\n청소 중이 아닐 때만 동작합니다. 켤까요?")
                    .setPositiveButton("켜기") { _, _ -> store.liveSend = true }
                    .setNegativeButton("취소") { _, _ -> isChecked = false }
                    .setOnCancelListener { isChecked = false }
                    .show()
            }
        }
        state = text("로봇 상태 읽는 중…", 17f, Ui.MUTED)
        floorsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val refresh = Button(this).apply { text = "새로고침"; isAllCaps = false; setOnClickListener { load() } }
        val probe = Button(this).apply {
            text = "진단 (읽기 전용 조회)"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@SetupActivity, MainActivity::class.java)) }
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(22), dp(28), dp(28))
                addView(text("설정", 28f, bold = true))
                addView(live, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
                addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(refresh)
                    addView(probe)
                })
                addView(floorsBox)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        io.execute {
            val s = RobotSnapshot.read(true, null)
            runOnUiThread { snap = s; render() }
        }
    }

    private fun render() {
        val s = snap ?: return
        val p = s.pos
        state.text = if (p == null) "로봇 위치를 읽지 못했습니다"
        else "지금 로봇: ${p.floor}층 (%.2f, %.2f) 방향 %.0f° · %s".format(p.x, p.y, Math.toDegrees(p.rotation), if (s.located) "위치 확정" else "위치 미확정")
        floorsBox.removeAllViews()
        val floors = s.floors
        if (floors == null) {
            floorsBox.addView(text("층 목록을 읽지 못했습니다", 17f, Ui.BAD))
            return
        }
        floors.sortedBy { it.floor }.forEach { floorsBox.addView(section(it, s)) }
    }

    private fun section(fm: FloorMap, s: RobotSnapshot): LinearLayout {
        val spot = store.get(fm.floor)
        val status = when {
            spot == null -> "미설정" to Ui.WARN
            spot.mapId != fm.mapId -> "지도가 바뀌어 다시 설정 필요 (저장 당시 지도와 다름)" to Ui.WARN
            else -> "사용 중: ${spot.describe()}" to Ui.OK
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(Ui.SURFACE, 18, 2, Ui.LINE)
            setPadding(dp(22), dp(18), dp(22), dp(18))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        panel.addView(text("${fm.floor}층", 26f, bold = true))
        panel.addView(text(status.first, 17f, status.second))

        // 1) 지금 위치를 기준 위치로 저장
        val p = s.pos
        val canSave = p != null && s.located && p.floor == fm.floor
        panel.addView(bigButton("지금 로봇 위치를 ${fm.floor}층 기준 위치로 저장").apply {
            setEnabledLook(canSave)
            setOnClickListener {
                val pos = snap?.pos ?: return@setOnClickListener
                AlertDialog.Builder(this@SetupActivity)
                    .setMessage(
                        "지금 위치 (%.2f, %.2f), 방향 %.0f° 를 ${fm.floor}층 기준 위치로 저장합니다.\n\n".format(pos.x, pos.y, Math.toDegrees(pos.rotation)) +
                            "로봇 바닥(앞바퀴/본체 모서리) 위치와 방향을 바닥 테이프로 표시해 두세요. 다른 층에서 옮겨 온 뒤 같은 자리, 같은 방향에 두고 버튼을 누르면 됩니다.",
                    )
                    .setPositiveButton("저장") { _, _ ->
                        store.save(FloorSpot(fm.floor, Pose2D(pos.x, pos.y, pos.rotation), null, "", fm.mapId))
                        render()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(12) })
        if (!canSave) {
            panel.addView(text("로봇이 ${fm.floor}층에서 위치가 확정된 상태일 때 저장할 수 있습니다", 15f, Ui.MUTED))
        }

        // 2) 지표점 (지도 관리 앱에서 등록)
        val lms = s.landmarks?.filter { it.floor == fm.floor }.orEmpty()
        if (lms.isEmpty()) {
            panel.addView(text("지표점: 없음 (지도 관리 앱에서 등록하면 여기에 나옵니다)", 15f, Ui.MUTED).apply { setPadding(0, dp(10), 0, 0) })
        }
        lms.forEach { lm ->
            panel.addView(bigButton("지표점 '${lm.name}' 사용", Ui.SOFT, Ui.INK).apply {
                setOnClickListener {
                    store.save(FloorSpot(fm.floor, null, lm.id, lm.name, fm.mapId))
                    render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })
        }

        // 3) 좌표 직접 입력
        val x = num("x (m)", spot?.pose?.x)
        val y = num("y (m)", spot?.pose?.y)
        val th = num("방향 (도)", spot?.pose?.theta?.let { Math.toDegrees(it) })
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            listOf(x, y, th).forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            addView(Button(context).apply {
                text = "직접 입력 저장"
                isAllCaps = false
                setOnClickListener {
                    val vx = x.text.toString().toDoubleOrNull()
                    val vy = y.text.toString().toDoubleOrNull()
                    val vt = th.text.toString().toDoubleOrNull()
                    if (vx == null || vy == null || vt == null) {
                        AlertDialog.Builder(this@SetupActivity).setMessage("x, y, 방향을 숫자로 입력하세요").setPositiveButton("확인", null).show()
                        return@setOnClickListener
                    }
                    store.save(FloorSpot(fm.floor, Pose2D(vx, vy, Math.toRadians(vt)), null, "", fm.mapId))
                    render()
                }
            })
        })

        if (spot != null) {
            panel.addView(Button(this).apply {
                text = "${fm.floor}층 설정 지우기"
                isAllCaps = false
                setOnClickListener { store.clear(fm.floor); render() }
            })
        }
        return panel
    }

    private fun num(hint: String, v: Double?) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        v?.let { setText("%.2f".format(it)) }
    }
}
