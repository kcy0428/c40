package kr.c40.floor.core

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * 로봇 상태를 바꾸는 요청. 시퀀서만 사용한다.
 * 기본 구현은 [DryRunCommands] 이고, [LiveCommands] 는 화면에서 "실제 전송"을 켠 경우에만 만든다.
 */
interface RobotCommands {
    val isDryRun: Boolean

    /** CoAP `POST /clean/relocationPosition {"action":3,"id":<지표점 id>}` ([코드], 로봇에서는 미검증). */
    fun relocateToLandmark(id: Int): GatewayResult

    /** 청소 앱이 첫 요청이 실패했을 때 재시도하는 경로 `/position/relocationPosition`. */
    fun relocateToLandmarkFallback(id: Int): GatewayResult

    /**
     * ROS `/switch_floor_map_only` (keenon_nav_msgs/SwitchMap, 엘리베이터 층 이동용)로 층 지도를 바꾸고
     * 로봇 위치를 [target] 으로 둔다. `PoseTagCurrent` = 지금 ROS 가 아는 위치, `PoseTagNext` = 목표 층 기준 위치.
     * 서비스와 인자 형식은 [실측](rosapi), 실제 동작은 [미검증].
     */
    fun relocateToPose(floor: Int, target: Pose2D, current: RobotPos): GatewayResult

    /** `POST :8089/robotLoadMapOpenPosition {"mapId":...}` 로 청소 앱의 지도를 맞춘다 ([코드], 미검증). */
    fun loadMap(mapId: String): GatewayResult
}

class LiveCommands(private val w: WriteGateway, private val ros: RosServiceCaller? = null) : RobotCommands {
    override val isDryRun = false

    override fun relocateToLandmark(id: Int) = coap("/clean/relocationPosition", id)
    override fun relocateToLandmarkFallback(id: Int) = coap("/position/relocationPosition", id)

    /**
     * 1) `/switch_floor_map_only` 로 층 지도를 바꾼다 — 2026-10-15 실측: 층과 청소 앱 지도는 바뀌지만 위치는 그대로였다.
     * 2) `/initialpose` 로 기준 위치를 준다 (RosLocationType 1, [미검증]).
     */
    override fun relocateToPose(floor: Int, target: Pose2D, current: RobotPos): GatewayResult {
        val r = ros ?: return GatewayResult(false, "ERR", "rosbridge 가 설정되지 않았습니다", 0)
        if (current.floor != floor) {
            val s = r.callService("/switch_floor_map_only", switchMapArgs(floor, target, current))
            if (!s.ok) return s
        }
        return r.publish("/initialpose", "geometry_msgs/PoseWithCovarianceStamped", initialPoseMsg(target))
    }

    override fun loadMap(mapId: String): GatewayResult {
        val r = w.httpPost("/robotLoadMapOpenPosition", JSONObject().put("mapId", mapId).toString())
        val body = try { JSONObject(r.body) } catch (_: Exception) { null }
        return if (r.ok && body?.optBoolean("status", false) == true) r else r.copy(ok = false)
    }

    private fun coap(path: String, id: Int): GatewayResult {
        val r = w.coapPost(path, JSONObject().put("action", 3).put("id", id).toString())
        val body = try { JSONObject(r.body) } catch (_: Exception) { null }
        return if (r.ok && body?.optInt("code", -1) == 0) r else r.copy(ok = false)
    }
}

/** 보낼 요청을 기록만 하고 아무것도 전송하지 않는다. */
class DryRunCommands : RobotCommands {
    override val isDryRun = true
    val recorded = mutableListOf<String>()

    override fun relocateToLandmark(id: Int) = record("POST /clean/relocationPosition {\"action\":3,\"id\":$id}")
    override fun relocateToLandmarkFallback(id: Int) = record("POST /position/relocationPosition {\"action\":3,\"id\":$id}")
    override fun relocateToPose(floor: Int, target: Pose2D, current: RobotPos): GatewayResult {
        if (current.floor != floor) record("ROS /switch_floor_map_only ${switchMapArgs(floor, target, current)}")
        return record("ROS /initialpose ${initialPoseMsg(target)}")
    }
    override fun loadMap(mapId: String) = record("POST :8089/robotLoadMapOpenPosition {\"mapId\":\"$mapId\"}")

    private fun record(text: String): GatewayResult {
        recorded += text
        return GatewayResult(true, "DRY-RUN", text, 0)
    }
}

internal fun switchMapArgs(floor: Int, target: Pose2D, current: RobotPos): JSONObject =
    JSONObject()
        .put("floor", floor)
        .put("PoseTagCurrent", rosPose(current.x, current.y, current.rotation))
        .put("PoseTagNext", rosPose(target.x, target.y, target.theta))

/** geometry_msgs/PoseWithCovarianceStamped. 공분산은 rviz "2D Pose Estimate" 기본값 (x·y 0.25, yaw 0.0685). */
internal fun initialPoseMsg(target: Pose2D): JSONObject {
    val cov = org.json.JSONArray()
    for (i in 0 until 36) cov.put(when (i) { 0, 7 -> 0.25; 35 -> 0.06853891945200942; else -> 0.0 })
    return JSONObject()
        .put("header", JSONObject().put("frame_id", "map"))
        .put("pose", JSONObject().put("pose", rosPose(target.x, target.y, target.theta)).put("covariance", cov))
}

private fun rosPose(x: Double, y: Double, theta: Double): JSONObject =
    JSONObject()
        .put("position", JSONObject().put("x", x).put("y", y).put("z", 0.0))
        .put("orientation", JSONObject().put("x", 0.0).put("y", 0.0).put("z", sin(theta / 2)).put("w", cos(theta / 2)))
