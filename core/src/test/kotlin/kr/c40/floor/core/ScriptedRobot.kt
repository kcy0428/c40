package kr.c40.floor.core

class FakeClock : Clock {
    var now = 0L
    override fun nowMs() = now
    override fun sleep(ms: Long) {
        now += ms
    }
}

/** 시계에 따라 상태가 변하는 가짜 로봇. 읽기와 명령을 모두 구현한다. */
class ScriptedRobot(private val clock: FakeClock) : RobotReader, RobotCommands {
    override val isDryRun = false

    // 시나리오 설정
    var startFloor = 1
    var landmarks = listOf(Landmark(101, "1층 시작", 1), Landmark(102, "2층 시작", 2), Landmark(103, "3층 시작", 3))
    var cleanStatus: Int? = 65535
    var chargeEvent = 254
    var relocateOk = true
    var fallbackOk = false
    var locateDelayMs = 5_000L
    var neverLocates = false
    var locatesOnFloor: Int? = null          // null 이면 요청한 층
    var legacyFields = false                 // true 면 type/poseId 를 주지 않는다 (정지 상태 실측 형식)
    var mapFollows = true
    var mapFollowDelayMs = 2_000L
    var loadMapOk = true

    // 결과 관찰
    val sent = mutableListOf<String>()
    private var relocatedAt: Long? = null
    private var targetFloor = startFloor
    private var targetId = 0
    private var mapManuallySet = false

    private fun located() = relocatedAt != null && !neverLocates && clock.nowMs() - relocatedAt!! >= locateDelayMs
    private fun floorNow() = if (located()) (locatesOnFloor ?: targetFloor) else startFloor

    override fun floors() = (1..5).map { FloorMap(it, "map$it") }
    override fun currentMap(): CurrentMap {
        val followed = mapManuallySet ||
            (mapFollows && located() && clock.nowMs() - relocatedAt!! >= locateDelayMs + mapFollowDelayMs)
        return CurrentMap("map${if (followed) targetFloor else startFloor}", if (followed) targetFloor else startFloor)
    }

    override fun positionStatus(): PositionStatus {
        val inProgress = relocatedAt != null && !located()
        return PositionStatus(
            status = if (inProgress) 1 else 0,
            type = if (legacyFields) null else if (located()) 11 else 4,
            poseId = if (legacyFields) null else if (located()) targetId else 1,
            floor = if (legacyFields) null else floorNow(),
        )
    }

    override fun robotPos() = RobotPos(floorNow(), -2.5, -0.7, 1.9)
    override fun cleanStatus() = cleanStatus
    override fun chargeStatus() = ChargeStatus(98, chargeEvent)
    override fun landmarks() = landmarks

    override fun relocateToLandmark(id: Int): GatewayResult {
        sent += "relocate:$id"
        return if (relocateOk) accept(id) else GatewayResult(false, "4.00", "reject", 0)
    }

    override fun relocateToLandmarkFallback(id: Int): GatewayResult {
        sent += "fallback:$id"
        return if (fallbackOk) accept(id) else GatewayResult(false, "4.00", "reject", 0)
    }

    override fun loadMap(mapId: String): GatewayResult {
        sent += "loadMap:$mapId"
        if (!loadMapOk) return GatewayResult(false, "HTTP 500", "", 0)
        mapManuallySet = true
        targetFloor = mapId.removePrefix("map").toInt()
        return GatewayResult(true, "HTTP 200", "", 0)
    }

    private fun accept(id: Int): GatewayResult {
        targetId = id
        targetFloor = landmarks.first { it.id == id }.floor
        relocatedAt = clock.nowMs()
        return GatewayResult(true, "2.05", "ok", 0)
    }
}
