package kr.c40.floor

import android.content.Context
import kr.c40.floor.core.FloorTarget
import kr.c40.floor.core.Pose2D
import org.json.JSONObject

/**
 * 층별 위치 지정 방법. [pose] 와 [landmarkId] 중 하나만 쓴다.
 * [mapId] 는 저장할 때의 그 층 지도 id 다. 지도를 다시 만들면 id 가 바뀌므로, 그때는 좌표를 믿지 않고 다시 설정하게 한다.
 */
data class FloorSpot(
    val floor: Int,
    val pose: Pose2D?,
    val landmarkId: Int?,
    val landmarkName: String,
    val mapId: String,
) {
    fun toTarget() = if (pose != null) FloorTarget(floor, pose = pose) else FloorTarget(floor, landmarkId = landmarkId)

    fun describe(): String =
        if (pose != null) "기준 위치 (%.2f, %.2f)".format(pose.x, pose.y) else "지표점 '$landmarkName'"
}

class FloorConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("floor_config", Context.MODE_PRIVATE)

    var liveSend: Boolean
        get() = prefs.getBoolean("live_send", false)
        set(v) = prefs.edit().putBoolean("live_send", v).apply()

    fun get(floor: Int): FloorSpot? {
        val s = prefs.getString("floor_$floor", null) ?: return null
        return try {
            val j = JSONObject(s)
            val pose = j.optJSONObject("pose")?.let { Pose2D(it.getDouble("x"), it.getDouble("y"), it.getDouble("theta")) }
            val lm = if (j.has("landmarkId")) j.getInt("landmarkId") else null
            if ((pose == null) == (lm == null)) null
            else FloorSpot(floor, pose, lm, j.optString("landmarkName"), j.optString("mapId"))
        } catch (_: Exception) {
            null
        }
    }

    fun save(spot: FloorSpot) {
        val j = JSONObject().put("mapId", spot.mapId)
        spot.pose?.let { j.put("pose", JSONObject().put("x", it.x).put("y", it.y).put("theta", it.theta)) }
        spot.landmarkId?.let { j.put("landmarkId", it).put("landmarkName", spot.landmarkName) }
        prefs.edit().putString("floor_${spot.floor}", j.toString()).apply()
    }

    fun clear(floor: Int) = prefs.edit().remove("floor_$floor").apply()
}
