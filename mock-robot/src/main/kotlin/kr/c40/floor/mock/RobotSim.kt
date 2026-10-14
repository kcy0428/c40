package kr.c40.floor.mock

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class Mode {
    /** 요청하면 status 1 → (지연 후) status 0, 지표점 층으로 이동, 청소 앱 지도가 따라온다. */
    NORMAL,

    /** 위치 지정 요청을 거부한다. */
    REJECT,

    /** 요청은 받지만 status 1 에서 끝나지 않는다. */
    NEVER_LOCATES,

    /** 인식은 되지만 요청한 층이 아닌 층으로 보고한다. */
    WRONG_FLOOR,

    /** 인식은 되지만 청소 앱 지도가 따라오지 않는다 (`robotLoadMapOpenPosition` 으로만 바뀐다). */
    MAP_STUCK,

    /** 청소 작업 중으로 보고한다. */
    CLEANING,

    /** 인식 후에도 `/position/status` 에 floor/type/poseId 를 주지 않는다 (정지 상태 실측 형식). */
    LEGACY_FIELDS,
}

/** mock 전용 가상 지표점. 실제 로봇에는 아직 없고, 지도를 만들어 등록하면 이 자리를 실제 값이 대신한다. */
data class MockLandmark(val id: Int, val name: String, val floor: Int, val x: Double, val y: Double, val rotation: Double)

data class MockConfig(
    val locateDelayMs: Long = 3_000,
    val mapFollowDelayMs: Long = 2_000,
    val floors: Map<Int, String> = (1..5).associateWith { "mockmap$it" },
    val landmarks: List<MockLandmark> = listOf(
        MockLandmark(101, "1층 시작", 1, -2.86, -0.88, 1.57),
        MockLandmark(102, "2층 시작", 2, 1.20, -3.40, 0.00),
        MockLandmark(103, "3층 시작", 3, 1.20, -3.40, 0.00),
    ),
)

/**
 * 로봇 한 대의 상태를 시계(실시간)에 따라 계산한다. 응답 JSON 은 2026-10-14 실측 형식을 따른다.
 * 정지 상태의 첫 값은 실측값(1층, 부팅 구역, 배터리 98%)이다.
 */
class RobotSim(val cfg: MockConfig = MockConfig(), private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var mode: Mode = Mode.NORMAL

    /** 로봇이 받은 쓰기 요청 기록 ("POST 경로 본문"). */
    val writes = CopyOnWriteArrayList<String>()

    @Volatile private var relocatedAt: Long? = null
    @Volatile private var target: MockLandmark? = null
    @Volatile private var manualMapFloor: Int? = null
    private val startFloor = 1

    private fun located(): Boolean {
        val t = relocatedAt ?: return false
        return mode != Mode.NEVER_LOCATES && now() - t >= cfg.locateDelayMs
    }

    private fun inProgress() = relocatedAt != null && !located()

    private fun reportedFloor(): Int {
        if (!located()) return startFloor
        val real = target!!.floor
        return if (mode == Mode.WRONG_FLOOR) (cfg.floors.keys.first { it != real }) else real
    }

    private fun mapFloor(): Int {
        manualMapFloor?.let { return it }
        val t = relocatedAt ?: return startFloor
        val followed = mode != Mode.MAP_STUCK && located() && now() - t >= cfg.locateDelayMs + cfg.mapFollowDelayMs
        return if (followed) reportedFloor() else startFloor
    }

    fun relocate(path: String, body: String): GatewayReply {
        writes += "POST $path $body"
        if (mode == Mode.REJECT) return GatewayReply(false, error("rejected"))
        val j = JSONObject(body)
        if (j.optInt("action") != 3) return GatewayReply(false, error("unsupported action"))
        val lm = cfg.landmarks.find { it.id == j.optInt("id", -1) } ?: return GatewayReply(false, error("unknown landmark"))
        target = lm
        manualMapFloor = null
        relocatedAt = now()
        return GatewayReply(true, JSONObject().put("code", 0).put("data", JSONObject()).put("msg", "success").put("status", 0).toString())
    }

    fun loadMap(body: String): GatewayReply {
        writes += "POST /robotLoadMapOpenPosition $body"
        val id = JSONObject(body).optString("mapId")
        val floor = cfg.floors.entries.find { it.value == id }?.key
            ?: return GatewayReply(false, httpEnvelope(JSONObject(), ok = false))
        manualMapFloor = floor
        return GatewayReply(true, httpEnvelope(JSONObject(), ok = true))
    }

    // ---- 읽기 응답 ----------------------------------------------------------------------------

    fun positionStatus(): String {
        val d = JSONObject()
        if (located() && mode != Mode.LEGACY_FIELDS) {
            d.put("floor", reportedFloor()).put("poseId", target!!.id).put("type", 11)
            d.put("position", JSONArray().put(target!!.name)).put("status", 0)
        } else {
            val name = if (located()) target!!.name else "开机区域"
            d.put("position", JSONArray().put(name)).put("status", if (inProgress()) 1 else 0)
        }
        return coap(d)
    }

    fun robotPos(): String {
        val floor = reportedFloor()
        val lm = if (located()) target!! else null
        val d = JSONObject()
            .put("buildDesc", JSONArray()).put("elevatorPosState", 1).put("floor", floor)
            .put("robotPos", JSONObject().put("rotation", lm?.rotation ?: 1.91).put("x", lm?.x ?: -2.51).put("y", lm?.y ?: -0.77))
        return coap(d)
    }

    fun cleanStatus(): String = coap(
        JSONObject().put("cleanArea", 0.0).put("cleanNavStatus", 0).put("cleanStage", 0)
            .put("cleanStatus", if (mode == Mode.CLEANING) 1 else 65535)
            .put("complementArea", 0.0).put("curZoneId", 0).put("percent", 0.0).put("plannerArea", 0.0).put("zoneArea", 0.0),
    )

    fun chargeStatus(): String = coap(JSONObject().put("event", 254).put("power", 98))

    fun cleanPositionStatus(): String = coap(JSONObject().put("status", if (inProgress()) 1 else 0).put("type", 0))

    /** `dest_posesV2` 는 `code` 없이 `status` 만 있는 형식이다 (실측). */
    fun destPosesV2(): String {
        fun dest(id: Int, name: String, type: String) = JSONObject()
            .put("id", id).put("isDefaultCharge", 0).put("name", name).put("phone", 0).put("phoneStr", "-1").put("type", type)
        val floors = JSONArray()
        for (f in cfg.floors.keys.sorted()) {
            val dests = JSONArray()
            if (f == 1) dests.put(dest(3, "출발점3-1", "zone_start_pose"))
            cfg.landmarks.filter { it.floor == f }.forEach { dests.put(dest(it.id, it.name, "landmark")) }
            if (dests.length() > 0) floors.put(JSONObject().put("destArray", dests).put("floor", f).put("floorInfo", ""))
        }
        val building = JSONObject().put("buildingInfo", "").put("floorArray", floors)
        return JSONObject().put("data", JSONArray().put(building)).put("msg", "success").put("status", 0).toString()
    }

    fun mapFloorList(): String {
        val a = JSONArray()
        cfg.floors.toSortedMap().forEach { (f, id) -> a.put(JSONObject().put("floor", f).put("mapId", id).put("mapName", "$f")) }
        return httpEnvelope(a, ok = true)
    }

    fun currentMap(): String {
        val f = mapFloor()
        val d = JSONObject().put("mapId", cfg.floors.getValue(f)).put("floor", f).put("loadState", 1).put("mapName", "$f")
        return httpEnvelope(d, ok = true)
    }

    fun navigationPoints(): String = httpEnvelope(JSONArray(), ok = true)

    /** 화면 확인용 요약. */
    fun summary(): String = JSONObject()
        .put("mode", mode.name).put("reportedFloor", reportedFloor()).put("mapFloor", mapFloor())
        .put("located", located()).put("inProgress", inProgress()).put("writes", JSONArray(writes)).toString()

    // ---- 형식 --------------------------------------------------------------------------------

    private fun coap(data: JSONObject) =
        JSONObject().put("code", 0).put("data", data).put("msg", "success").put("status", 0).toString()

    private fun error(msg: String) =
        JSONObject().put("code", 1).put("data", JSONObject()).put("msg", msg).put("status", 1).toString()

    private fun httpEnvelope(data: Any, ok: Boolean) = JSONObject()
        .put("code", if (ok) "200" else "500").put("data", data).put("errorMsg", "")
        .put("msg", if (ok) "success" else "fail").put("responseType", 1).put("status", ok).toString()
}

data class GatewayReply(val ok: Boolean, val body: String)
