package kr.c40.floor.core

enum class StepId(val label: String) {
    PRECHECK("사전 점검"),
    CONFIRM("사용자 확인"),
    RELOCATE("위치 지정"),
    VERIFY("인식 확인"),
    MAP_SYNC("지도 동기 확인"),
}

data class StepResult(val step: StepId, val ok: Boolean, val message: String)

data class SwitchResult(
    val success: Boolean,
    val dryRun: Boolean,
    val steps: List<StepResult>,
    val failedAt: StepId?,
) {
    val summary: String get() = steps.lastOrNull()?.message ?: ""
}

data class FloorTarget(val floor: Int, val landmarkId: Int)

data class SequencerConfig(
    /** `/clean/status` 의 `cleanStatus` 중 "작업 없음"으로 보는 값. 65535 는 정지 상태 실측값, 나머지는 미측정. */
    val idleCleanStatuses: Set<Int> = setOf(65535),
    /** `/charge/status` 의 `event` 중 "충전기 위/충전 중"으로 보는 값. 아직 측정하지 못해 비어 있다. */
    val chargingEvents: Set<Int> = emptySet(),
    val verifyTimeoutMs: Long = 60_000,
    val pollIntervalMs: Long = 1_000,
    /** 요청 직후 이 시간 동안은 이전 상태가 남아 있을 수 있어 판정하지 않는다. */
    val settleMs: Long = 3_000,
    /** 청소 앱이 ROS 층을 따라 지도를 바꾸길 기다리는 시간. 넘으면 `loadMap` 으로 맞춘다. */
    val mapFollowTimeoutMs: Long = 15_000,
    /** `RosLocationType` 11 = 지표점 위치 지정 ([코드]). */
    val locatedType: Int = 11,
)

/**
 * 층 전환 시퀀스 (docs/03 4장). 청소는 시작하지 않는다 — 이 시퀀서의 끝은 "준비 완료" 이다.
 * 어느 단계든 실패하면 즉시 멈춘다.
 */
class FloorSwitchSequencer(
    private val reader: RobotReader,
    private val commands: RobotCommands,
    private val clock: Clock = SystemClock,
    private val config: SequencerConfig = SequencerConfig(),
    private val onStep: (StepResult) -> Unit = {},
) {
    private val steps = mutableListOf<StepResult>()

    /** @param confirm 사용자에게 보여 줄 문구를 받아 승인 여부를 돌려준다. */
    fun run(target: FloorTarget, confirm: (String) -> Boolean): SwitchResult {
        steps.clear()

        val pre = precheck(target) ?: return fail(StepId.PRECHECK)
        if (!confirm(pre)) return stop(StepId.CONFIRM, "사용자가 취소했습니다")
        record(StepId.CONFIRM, true, "확인됨")

        if (!relocate(target)) return fail(StepId.RELOCATE)
        if (commands.isDryRun) {
            record(StepId.VERIFY, true, "dry-run 이라 실제로 전송하지 않았습니다. 인식·지도 확인은 생략합니다")
            return SwitchResult(true, true, steps.toList(), null)
        }

        if (!verify(target)) return fail(StepId.VERIFY)
        if (!mapSync(target)) return fail(StepId.MAP_SYNC)
        return SwitchResult(true, false, steps.toList(), null)
    }

    /** 통과하면 확인 창에 보여 줄 문구를, 실패하면 null 을 돌려준다. */
    private fun precheck(t: FloorTarget): String? {
        val landmarks = reader.landmarks() ?: return no(StepId.PRECHECK, "지표점 목록을 읽지 못했습니다")
        val lm = landmarks.find { it.id == t.landmarkId && it.floor == t.floor }
            ?: return no(StepId.PRECHECK, "${t.floor}층에 지표점 id ${t.landmarkId} 가 없습니다")

        val floors = reader.floors() ?: return no(StepId.PRECHECK, "층 목록을 읽지 못했습니다")
        if (floors.none { it.floor == t.floor }) return no(StepId.PRECHECK, "${t.floor}층 지도가 로봇에 없습니다")

        val clean = reader.cleanStatus() ?: return no(StepId.PRECHECK, "청소 상태를 읽지 못했습니다")
        if (clean !in config.idleCleanStatuses) return no(StepId.PRECHECK, "청소 작업 중이거나 상태를 알 수 없습니다 (cleanStatus=$clean)")

        val charge = reader.chargeStatus() ?: return no(StepId.PRECHECK, "충전 상태를 읽지 못했습니다")
        if (charge.event in config.chargingEvents) return no(StepId.PRECHECK, "충전기 위에 있습니다 (event=${charge.event})")

        val pos = reader.robotPos() ?: return no(StepId.PRECHECK, "현재 위치를 읽지 못했습니다")

        val text = "${t.floor}층으로 전환합니다 (지표점 '${lm.name}', 현재 ${pos.floor}층)"
        record(StepId.PRECHECK, true, "통과: 현재 ${pos.floor}층, 배터리 ${charge.power}%, 지표점 '${lm.name}'")
        return text
    }

    private fun relocate(t: FloorTarget): Boolean {
        var r = commands.relocateToLandmark(t.landmarkId)
        if (!r.ok) {
            r = commands.relocateToLandmarkFallback(t.landmarkId)
            if (!r.ok) return no(StepId.RELOCATE, "위치 지정 요청이 실패했습니다: ${r.code} ${r.body.take(120)}") != null
        }
        record(StepId.RELOCATE, true, if (commands.isDryRun) "dry-run: ${r.body}" else "요청 전송됨")
        return true
    }

    private fun verify(t: FloorTarget): Boolean {
        val t0 = clock.nowMs()
        var last = "관찰값 없음"
        while (clock.nowMs() - t0 < config.verifyTimeoutMs) {
            clock.sleep(config.pollIntervalMs)
            if (clock.nowMs() - t0 < config.settleMs) continue
            val st = reader.positionStatus()
            val pos = reader.robotPos()
            if (st == null || pos == null) {
                last = "상태를 읽지 못함"
                continue
            }
            last = "status=${st.status} floor=${pos.floor} type=${st.type} poseId=${st.poseId}"
            val located = st.status == 0 &&
                pos.floor == t.floor &&
                (st.floor == null || st.floor == t.floor) &&
                (st.type == null || st.type == config.locatedType) &&
                (st.poseId == null || st.poseId == t.landmarkId)
            if (located) {
                record(StepId.VERIFY, true, "인식됨: $last (${(clock.nowMs() - t0) / 1000}초)")
                return true
            }
        }
        return no(StepId.VERIFY, "제한 시간 안에 인식되지 않았습니다. 마지막 관찰: $last") != null
    }

    private fun mapSync(t: FloorTarget): Boolean {
        val expected = reader.floors()?.find { it.floor == t.floor }?.mapId
            ?: return no(StepId.MAP_SYNC, "목표 층 지도 id 를 읽지 못했습니다") != null

        if (waitForMap(expected)) {
            record(StepId.MAP_SYNC, true, "청소 앱 지도가 ${t.floor}층으로 바뀌었습니다")
            return true
        }
        val r = commands.loadMap(expected)
        if (!r.ok) return no(StepId.MAP_SYNC, "청소 앱 지도가 따라오지 않았고 수동 전환도 실패했습니다: ${r.code}") != null
        if (waitForMap(expected)) {
            record(StepId.MAP_SYNC, true, "청소 앱 지도를 수동으로 ${t.floor}층에 맞췄습니다")
            return true
        }
        return no(StepId.MAP_SYNC, "수동 전환 뒤에도 청소 앱 지도가 ${t.floor}층이 아닙니다") != null
    }

    private fun waitForMap(expected: String): Boolean {
        val t0 = clock.nowMs()
        while (true) {
            if (reader.currentMap()?.mapId == expected) return true
            if (clock.nowMs() - t0 >= config.mapFollowTimeoutMs) return false
            clock.sleep(config.pollIntervalMs)
        }
    }

    private fun record(step: StepId, ok: Boolean, msg: String) {
        val r = StepResult(step, ok, msg)
        steps += r
        onStep(r)
    }

    /** 실패를 기록하고 null 을 돌려준다 (`?: return` 과 `!= null` 두 형태로 쓰기 위함). */
    private fun no(step: StepId, msg: String): String? {
        record(step, false, msg)
        return null
    }

    private fun fail(step: StepId) = SwitchResult(false, commands.isDryRun, steps.toList(), step)

    private fun stop(step: StepId, msg: String): SwitchResult {
        record(step, false, msg)
        return fail(step)
    }
}
