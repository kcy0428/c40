package kr.c40.floor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FloorSwitchSequencerTest {
    private lateinit var clock: FakeClock
    private lateinit var robot: ScriptedRobot

    @Before
    fun setUp() {
        clock = FakeClock()
        robot = ScriptedRobot(clock)
    }

    private fun run(
        target: FloorTarget = FloorTarget(2, 102),
        commands: RobotCommands = robot,
        config: SequencerConfig = SequencerConfig(),
        confirm: (String) -> Boolean = { true },
    ) = FloorSwitchSequencer(robot, commands, clock, config).run(target, confirm)

    @Test
    fun `정상 전환은 모든 단계를 순서대로 통과한다`() {
        val r = run()
        assertTrue(r.summary, r.success)
        assertFalse(r.dryRun)
        assertEquals(
            listOf(StepId.PRECHECK, StepId.CONFIRM, StepId.RELOCATE, StepId.VERIFY, StepId.MAP_SYNC),
            r.steps.map { it.step },
        )
        assertEquals(listOf("relocate:102"), robot.sent)   // 지도는 청소 앱이 따라오므로 loadMap 은 필요 없다
    }

    @Test
    fun `청소 중이면 사전 점검에서 멈추고 아무것도 보내지 않는다`() {
        robot.cleanStatus = 1
        val r = run()
        assertFalse(r.success)
        assertEquals(StepId.PRECHECK, r.failedAt)
        assertTrue(robot.sent.isEmpty())
    }

    @Test
    fun `읽지 못한 상태는 통과로 보지 않는다`() {
        robot.cleanStatus = null
        assertEquals(StepId.PRECHECK, run().failedAt)
    }

    @Test
    fun `목표 층에 지표점이 없으면 멈춘다`() {
        robot.landmarks = listOf(Landmark(101, "1층 시작", 1))
        val r = run()
        assertEquals(StepId.PRECHECK, r.failedAt)
        assertTrue(robot.sent.isEmpty())
    }

    @Test
    fun `지표점 id 가 다른 층 것이면 멈춘다`() {
        val r = run(FloorTarget(2, 103))   // 103 은 3층 지표점
        assertEquals(StepId.PRECHECK, r.failedAt)
    }

    @Test
    fun `충전 중으로 설정된 event 면 멈춘다`() {
        robot.chargeEvent = 7
        val r = run(config = SequencerConfig(chargingEvents = setOf(7)))
        assertEquals(StepId.PRECHECK, r.failedAt)
    }

    @Test
    fun `사용자가 취소하면 요청을 보내지 않는다`() {
        val r = run(confirm = { false })
        assertEquals(StepId.CONFIRM, r.failedAt)
        assertTrue(robot.sent.isEmpty())
    }

    @Test
    fun `확인 창에는 목표 층과 지표점 이름이 들어간다`() {
        var shown = ""
        run(confirm = { shown = it; true })
        assertTrue(shown, "2층" in shown && "2층 시작" in shown)
    }

    @Test
    fun `첫 요청이 거부되면 다른 경로로 한 번 재시도한다`() {
        robot.relocateOk = false
        robot.fallbackOk = true
        val r = run()
        assertTrue(r.summary, r.success)
        assertEquals(listOf("relocate:102", "fallback:102"), robot.sent)
    }

    @Test
    fun `두 경로가 모두 거부되면 위치 지정에서 멈춘다`() {
        robot.relocateOk = false
        robot.fallbackOk = false
        val r = run()
        assertEquals(StepId.RELOCATE, r.failedAt)
        assertEquals(2, robot.sent.size)
    }

    @Test
    fun `인식이 끝나지 않으면 제한 시간 뒤 멈추고 지도 단계로 가지 않는다`() {
        robot.neverLocates = true
        val r = run()
        assertEquals(StepId.VERIFY, r.failedAt)
        assertTrue(clock.now >= 60_000)
        assertTrue(r.steps.none { it.step == StepId.MAP_SYNC })
    }

    @Test
    fun `다른 층으로 인식되면 성공으로 보지 않는다`() {
        robot.locatesOnFloor = 1
        assertEquals(StepId.VERIFY, run().failedAt)
    }

    @Test
    fun `type 이나 poseId 가 있으면 지표점 위치 지정 결과와 일치해야 한다`() {
        // 같은 층(1층) 지표점으로 전환할 때, 이전 상태(type 4, poseId 1)를 성공으로 오인하면 안 된다.
        val r = run(FloorTarget(1, 101))
        assertTrue(r.summary, r.success)
        assertTrue("요청 직후의 이전 상태로 통과하면 안 된다", clock.now >= 5_000)
    }

    @Test
    fun `type 과 poseId 가 없는 응답에서도 안정화 시간 전에는 판정하지 않는다`() {
        robot.legacyFields = true
        robot.locateDelayMs = 0
        val r = run(FloorTarget(1, 101))
        assertTrue(r.summary, r.success)
        assertTrue(clock.now >= SequencerConfig().settleMs)
    }

    @Test
    fun `청소 앱 지도가 따라오지 않으면 수동으로 맞춘다`() {
        robot.mapFollows = false
        val r = run()
        assertTrue(r.summary, r.success)
        assertEquals(listOf("relocate:102", "loadMap:map2"), robot.sent)
    }

    @Test
    fun `지도 수동 전환이 실패하면 지도 단계에서 멈춘다`() {
        robot.mapFollows = false
        robot.loadMapOk = false
        assertEquals(StepId.MAP_SYNC, run().failedAt)
    }

    @Test
    fun `dry-run 은 요청을 기록만 하고 로봇 상태를 바꾸지 않는다`() {
        val dry = DryRunCommands()
        val r = run(commands = dry)
        assertTrue(r.summary, r.success)
        assertTrue(r.dryRun)
        assertEquals(1, dry.recorded.size)
        assertTrue(dry.recorded[0], "\"action\":3" in dry.recorded[0] && "\"id\":102" in dry.recorded[0])
        assertTrue(robot.sent.isEmpty())
        assertNull(r.failedAt)
        assertEquals(1, robot.currentMap().floor)
    }

    private val floor2Spot = FloorTarget(2, pose = Pose2D(0.09, 0.04, 2.15))

    @Test
    fun `기준 위치 방식은 지표점 없이 층 지도 전환으로 위치를 지정한다`() {
        robot.landmarks = emptyList()
        robot.legacyFields = true   // type/poseId 값을 모르는 상태
        val r = run(floor2Spot)
        assertTrue(r.summary, r.success)
        assertEquals(listOf("switchMap:2@0.09,0.04 from 1@-2.5,-0.7"), robot.sent)
    }

    @Test
    fun `기준 위치에서 너무 멀리 인식되면 성공으로 보지 않는다`() {
        robot.poseOffsetM = 3.0
        assertEquals(StepId.VERIFY, run(floor2Spot).failedAt)
    }

    @Test
    fun `층 전환 서비스가 실패를 돌려주면 위치 지정에서 멈춘다`() {
        robot.relocateOk = false
        val r = run(floor2Spot)
        assertEquals(StepId.RELOCATE, r.failedAt)
        assertEquals(1, robot.sent.size)
    }

    @Test
    fun `기준 위치 dry-run 은 보낼 ROS 요청을 기록한다`() {
        val dry = DryRunCommands()
        val r = run(floor2Spot, commands = dry)
        assertTrue(r.summary, r.success)
        assertTrue(dry.recorded[0], "/switch_floor_map_only" in dry.recorded[0] && "\"floor\":2" in dry.recorded[0])
        assertTrue(robot.sent.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `지표점과 기준 위치를 함께 줄 수 없다`() {
        FloorTarget(2, 102, Pose2D(0.0, 0.0, 0.0))
    }

    @Test
    fun `dry-run 이어도 사전 점검은 그대로 적용된다`() {
        robot.cleanStatus = 3
        val dry = DryRunCommands()
        val r = run(commands = dry)
        assertEquals(StepId.PRECHECK, r.failedAt)
        assertTrue(dry.recorded.isEmpty())
    }
}
