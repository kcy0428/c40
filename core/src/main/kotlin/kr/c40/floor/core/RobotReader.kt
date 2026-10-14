package kr.c40.floor.core

import org.json.JSONObject

data class FloorMap(val floor: Int, val mapId: String)
data class CurrentMap(val mapId: String, val floor: Int)

/** `type`, `poseId`, `floor` 는 위치 지정 직후에만 오는 것으로 보여 null 일 수 있다 (정지 상태 실측에서는 없었다). */
data class PositionStatus(val status: Int, val type: Int?, val poseId: Int?, val floor: Int?)
data class RobotPos(val floor: Int, val x: Double, val y: Double, val rotation: Double)
data class ChargeStatus(val power: Int, val event: Int)
data class Landmark(val id: Int, val name: String, val floor: Int)

/** 파싱된 로봇 상태. null 은 "읽지 못함" 이다. */
interface RobotReader {
    fun floors(): List<FloorMap>?
    fun currentMap(): CurrentMap?
    fun positionStatus(): PositionStatus?
    fun robotPos(): RobotPos?
    fun cleanStatus(): Int?
    fun chargeStatus(): ChargeStatus?
    fun landmarks(): List<Landmark>?
}

/** 2026-10-14 실측 응답 형식 기준 ([docs/04](../../../../../../../../docs/04_readonly_probe_results.md)). */
class GatewayRobotReader(private val gw: RobotGateway) : RobotReader {

    override fun floors(): List<FloorMap>? {
        val data = http("/mapFloorList")?.optJSONArray("data") ?: return null
        return (0 until data.length()).map { data.getJSONObject(it) }
            .map { FloorMap(it.getInt("floor"), it.getString("mapId")) }
    }

    override fun currentMap(): CurrentMap? {
        val d = http("/getCurrentLoadMapInfo")?.optJSONObject("data") ?: return null
        val id = d.optString("mapId", "")
        return if (id.isEmpty()) null else CurrentMap(id, d.optInt("floor", -1))
    }

    override fun positionStatus(): PositionStatus? {
        val d = coap("/position/status")?.optJSONObject("data") ?: return null
        return PositionStatus(
            status = d.optInt("status", -1),
            type = d.optIntOrNull("type"),
            poseId = d.optIntOrNull("poseId"),
            floor = d.optIntOrNull("floor"),
        )
    }

    override fun robotPos(): RobotPos? {
        val d = coap("/position/robotPosInfo")?.optJSONObject("data") ?: return null
        val p = d.optJSONObject("robotPos") ?: return null
        return RobotPos(d.optInt("floor", -1), p.optDouble("x"), p.optDouble("y"), p.optDouble("rotation"))
    }

    override fun cleanStatus(): Int? =
        coap("/clean/status")?.optJSONObject("data")?.optIntOrNull("cleanStatus")

    override fun chargeStatus(): ChargeStatus? {
        val d = coap("/charge/status")?.optJSONObject("data") ?: return null
        return ChargeStatus(d.optInt("power", -1), d.optInt("event", -1))
    }

    /** `/navigation/dest_posesV2`: data[].floorArray[].destArray[] 중 type == "landmark" 만 고른다. */
    override fun landmarks(): List<Landmark>? {
        val buildings = coap("/navigation/dest_posesV2")?.optJSONArray("data") ?: return null
        val out = mutableListOf<Landmark>()
        for (b in 0 until buildings.length()) {
            val floors = buildings.getJSONObject(b).optJSONArray("floorArray") ?: continue
            for (f in 0 until floors.length()) {
                val fl = floors.getJSONObject(f)
                val dests = fl.optJSONArray("destArray") ?: continue
                for (i in 0 until dests.length()) {
                    val d = dests.getJSONObject(i)
                    if (d.optString("type") == "landmark") {
                        out += Landmark(d.getInt("id"), d.optString("name"), fl.optInt("floor", -1))
                    }
                }
            }
        }
        return out
    }

    private fun http(path: String): JSONObject? {
        val r = gw.httpGet(path)
        if (!r.ok) return null
        val j = parse(r.body) ?: return null
        return if (j.optBoolean("status", false)) j else null
    }

    /** CoAP 응답은 `code` 가 0 이거나(`status` 0), `dest_posesV2` 처럼 `code` 없이 `status` 만 0 이다. */
    private fun coap(path: String): JSONObject? {
        val r = gw.coapGet(path)
        if (!r.ok) return null
        val j = parse(r.body) ?: return null
        val okCode = !j.has("code") || j.optInt("code", -1) == 0
        return if (okCode && j.optInt("status", -1) == 0) j else null
    }

    private fun parse(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
}
