package kr.c40.floor

import android.os.Build
import kr.c40.floor.core.DryRunCommands
import kr.c40.floor.core.FloorMap
import kr.c40.floor.core.GatewayRobotReader
import kr.c40.floor.core.Landmark
import kr.c40.floor.core.LiveCommands
import kr.c40.floor.core.OkHttpRosbridge
import kr.c40.floor.core.PositionStatus
import kr.c40.floor.core.RealGateway
import kr.c40.floor.core.RobotCommands
import kr.c40.floor.core.RobotEndpoints
import kr.c40.floor.core.RobotPos
import org.json.JSONObject

/** 화면들이 함께 쓰는 로봇 접속. 읽기는 [reader], 쓰기는 [commands] 로만 한다. */
object Robot {
    val endpoints: RobotEndpoints by lazy {
        val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true)
        if (emulator) RobotEndpoints.EMULATOR else RobotEndpoints.ROBOT
    }
    val gateway by lazy { RealGateway(endpoints) }
    val reader by lazy { GatewayRobotReader(gateway) }

    fun commands(live: Boolean): RobotCommands =
        if (live) LiveCommands(gateway, OkHttpRosbridge(endpoints.rosbridgeUrl)) else DryRunCommands()

    /** 청소 앱에 지금 올라가 있는 지도의 id 와 그림(`pic`). 읽지 못하면 null. */
    fun currentMapPic(): Pair<String, String>? {
        val r = gateway.httpGet("/getCurrentLoadMapInfo")
        if (!r.ok) return null
        return try {
            val d = JSONObject(r.body).getJSONObject("data")
            val id = d.optString("mapId")
            val pic = d.optString("pic")
            if (id.isEmpty() || pic.isEmpty()) null else id to pic
        } catch (_: Exception) {
            null
        }
    }
}

/** 화면 갱신용 상태 묶음. 각 값은 읽지 못하면 null. */
data class RobotSnapshot(
    val floors: List<FloorMap>?,
    val pos: RobotPos?,
    val status: PositionStatus?,
    val landmarks: List<Landmark>?,
) {
    val located get() = status?.status == 0

    companion object {
        fun read(withLists: Boolean, previous: RobotSnapshot?): RobotSnapshot {
            val r = Robot.reader
            return RobotSnapshot(
                floors = if (withLists) r.floors() else previous?.floors,
                pos = r.robotPos(),
                status = r.positionStatus(),
                landmarks = if (withLists) r.landmarks() else previous?.landmarks,
            )
        }
    }
}
