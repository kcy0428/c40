# 청소 앱 내부 분석 (2차)

- 분석일: 2026-10-13
- 대상: `com.keenon.peanut.clean` v3.7.6 (jadx 1.5.1 디컴파일)
- 디컴파일 원본은 Keenon 저작물이라 저장소에 올리지 않는다(`local/decompiled/`, git 제외). 여기에는 앱 제작에 필요한 **인터페이스 사실**만 적는다.
- 표기: **[코드]** = 디컴파일 코드에서 확인, **[실측]** = 로봇 로그/응답으로 확인, **[미검증]** = 코드상 그렇게 보이나 로봇에서 아직 실행해 보지 않음

## 1. 결론: 1차 보고서의 가정 수정

| 1차에서의 가정 | 실제 |
|---|---|
| 층 전환은 청소 앱의 `changeFloor` 로 ROS 지도를 바꾸는 것 | **ROS는 지도를 "전환"하지 않는다.** ROS DB에는 1~5층 지도가 모두 들어 있고, 로봇이 어느 층에 있는지는 **위치 지정(relocation) 결과로 결정**된다 |
| 새 앱이 청소 앱에 층 전환을 명령해야 한다 | **청소 앱은 ROS가 보고하는 층을 보고 스스로 지도를 바꾼다** (2.1) |
| 층별 시작점 좌표를 새 앱이 저장해야 한다 | Keenon 에 이미 **"지표점(landmark)" 기능**이 있다. 층별로 지표점을 등록하면 ROS가 좌표를 보관하고, 앱은 **id 하나**로 위치 지정을 요청한다 (2.2) |

따라서 원클릭의 핵심 동작은 사실상 **CoAP 요청 1개**다.

```
POST coap://192.168.64.20:5683/clean/relocationPosition   {"action":3,"id":<목표 층 지표점 id>}
```

## 2. 동작 원리

### 2.1 청소 앱의 지도는 ROS 층을 자동으로 따라간다 [코드]

`PositionManager` 가 ROS 의 `/position/robotPosInfo`(층 + 좌표, 상시 구독)를 받을 때마다:

1. 보고된 `floor` 가 현재 로드된 지도의 층과 다르면
2. 그 층의 지도(`ROBOT_MAP.FLOOR`)를 찾아 `MapInfoServiceImpl.changeLocalMap(mapId, true)` 호출
3. `changeLocalMap` 은 앱 DB의 `LOAD_STATE` 를 바꾸고(이전 지도 0, 새 지도 1) 지도 캐시를 갱신한 뒤 클라우드에 층 변경을 알림

`changeLocalMap` 안에는 **ROS 로 보내는 요청이 없다.** 앱 쪽 표시·상태만 바꾸는 함수다.

같은 함수를 부르는 다른 경로:

| 경로 | 설명 |
|---|---|
| `POST http://localhost:8089/robotLoadMapOpenPosition` body `{"mapId":"..."}` | 외부에서 호출 가능한 HTTP 경로 [코드, 미검증] |
| 웹 UI JS 브리지 `changeFloor({params:{mapId}})` | 구역 선택 화면의 층 탭 (화면 표시용) |

→ 새 앱이 층 전환을 따로 명령할 필요는 없다. 위치 지정이 성공해 ROS 가 새 층을 보고하면 청소 앱이 알아서 따라온다. 필요하면 `/robotLoadMapOpenPosition` 으로 미리 맞춰 둘 수는 있다.

### 2.2 위치 지정(relocation) [코드 + 일부 실측]

CoAP `POST /clean/relocationPosition` (실패 시 앱은 `/position/relocationPosition` 로 재시도), 파라미터 `{"action":<int>,"id":<int>}`

| action | 의미 | 근거 |
|---|---|---|
| 1 | 위치 재지정 모드 진입 (`id:0`) | [실측] 2026-10-08 로그 |
| 2 | 위치 재지정 모드 종료 (`id:0`) | [실측] |
| **3** | **지정한 지점 id 위치로 위치 지정** (`id` = 지점의 ROS id) | [코드] `PositionChooseActivity.onPostDst`, 로봇에서는 [미검증] |

`action 3` 에 쓸 수 있는 지점은 앱의 "위치 선택" 화면 목록과 같다. 이 목록은 CoAP `GET /navigation/dest_posesV2` 의 `landmarks` 중 아래 타입만 골라 **층별 탭**으로 보여 준다.

| 타입 문자열 | 의미 |
|---|---|
| `landmark` | **지표점(地标点)** — 위치 지정용으로 사용자가 등록하는 지점 |
| `water_supply`, `water_drain` | 급수/배수 지점 |

각 항목은 `id`, `name`, `floor`, `pose(position, orientation)`, `mapMd5` 를 가진다. **지점이 층 정보를 갖고 있으므로, 지점 id 를 고르는 것이 곧 층을 고르는 것이다.**

**현재 이 로봇에는 `landmark` 타입 지점이 하나도 없다** (ROS `pose` 테이블에 `charge`, `zone_start_pose`, `origin`, `normal`, `elevator_*` 만 있음). 그래서 지금은 층을 옮긴 뒤 간단히 위치를 잡을 방법이 없고 절차가 복잡한 것이다.

### 2.3 위치 인식 결과 확인 [실측 + 코드]

| 요청 | 응답 예 | 해석 |
|---|---|---|
| `GET /position/status` | `{"floor":1,"poseId":1,"position":["开机区域"],"status":0,"type":4}` | `status` 0 = 확정, 1 = 인식 중, 2 = 위치 없음(로그 기준 추정). `floor` = 인식된 층 |
| `GET /position/robotPosInfo` | `{"floor":1,"robotPos":{"x":-2.86,"y":-0.88,"rotation":1.6},"elevatorPosState":1}` | 현재 층과 좌표 |
| `GET /clean/positionStatus` | `{"status":1,"type":0}` | 재지정 진행 상태. `type` 0 = 실시간 위치 인식, 1 = 선(線) 특징 |
| `GET /position/realtimePositionStatus` | | 실시간 인식 상태 |

`/position/status` 의 `type` 값 (`RosLocationType`):

| type | 의미 | poseId 필요 |
|---|---|---|
| 1 | 웹에서 온 `/initialpose` 전달 | |
| 2 | 지도 도구의 `/map_trans_pose` | |
| 3 | 환경 매칭 부팅 지점 (`vslam_init`, "开机区域") | ○ |
| 4 | 충전기 위치 (충전기에 올라갈 때) | ○ |
| 5 | 충전기 위치 (충전기에서 내려올 때) | |
| 6 | 라벨 / VSLAM 초기 위치 인식 | |
| 7 | 소프트 재부팅 후 재인식 | |
| **11** | **지표점 위치 지정** | ○ |

→ 성공 판정: `status == 0` 이고 `floor == 목표 층` 이고 `type == 11`, `poseId == 요청한 id`.

type 1·2 는 임의 좌표를 직접 넣는 경로가 ROS 쪽에 있음을 뜻한다. 앱은 rosbridge(`ws://192.168.64.20:9090`) 클라이언트도 갖고 있으나 조사 시점에는 연결돼 있지 않았다. 좌표 직접 지정은 지표점 방식이 안 될 때의 대안이다 [미검증].

type 6 과 ROS DB 의 `label` 테이블은 **라벨(천장/벽 태그) 기반 초기 위치 인식 기능이 Keenon 에 원래 있다**는 뜻이다. AprilTag 를 직접 구현하기 전에 이 기능을 쓸 수 있는지 확인할 가치가 있다.

### 2.4 청소 시작 [코드, 미검증]

| 수준 | 방법 | 비고 |
|---|---|---|
| ROS 직접 | CoAP `POST /clean/dstV2 {"dst":<zoneId>,"allowCrossCarpet":false}` | 구역 하나 청소. 청소 앱의 작업 기록·상태와 어긋날 수 있음 |
| 청소 앱 경유 | 명령 `CLEAN_BEGIN_WEB`(작업 시작), `CLEAN_STRATEGY_TEMPORARY_TASK_WEB`(임시 작업) | 클라우드/웹에서 오는 명령 형식. 처리부 `CleanServerServiceImpl`. 외부 앱에서 넣는 경로는 추가 분석 필요 |
| 청소 앱 화면 | 청소 앱을 앞으로 띄워 사용자가 시작 | 가장 안전한 1차 구현 |

관련 CoAP: `/clean/action {"action":..}`, `/clean/status`, `/task/status`

## 3. 인터페이스 목록

### 3.1 CoAP (ROS 제어부) — `reference/api/coap_api_catalog.tsv`

SDK 에 정의된 223개 API 의 경로, 메서드, 파라미터 키 전체. 원클릭 앱에 필요한 것:

| 경로 | 메서드 | 파라미터 |
|---|---|---|
| `/navigation/dest_posesV2` | GET | — (지점 목록) |
| `/clean/relocationPosition` | POST | `action`, `id` |
| `/position/relocationPosition` | POST | `action`, `id` |
| `/position/status` | GET / OBSERVE | — |
| `/position/robotPosInfo` | GET / OBSERVE | — |
| `/clean/positionStatus` | GET / OBSERVE | — |
| `/motor/enable` | POST | `enable` (0/1) |
| `/clean/dstV2` | POST | `dst`, `allowCrossCarpet` |
| `/clean/status`, `/task/status`, `/charge/status` | GET | — |

- 전송: CoAP over UDP `192.168.64.20:5683`, 본문 JSON
- 응답: `{"code":0,"data":{...},"msg":"success","status":0}`
- 2초 주기 ping 으로 연결 감시 (`CoAPPing`)

### 3.2 청소 앱 HTTP `:8089` — `reference/api/http_8089_routes.tsv`

62개 경로. 인증 없음. 응답 `{"code":"200","data":...,"msg":"success","status":true}`

| 경로 | 메서드 | 용도 | 상태 |
|---|---|---|---|
| `/mapFloorList` | GET | 층 → `mapId` 목록 | [실측] |
| `/getCurrentLoadMapInfo` | GET | 현재 로드된 지도 전체 정보 | [실측] |
| `/getMapInfo` | GET | `mapId` 로 지도 정보 | [실측] (`mapId` 필수) |
| `/listCurrentMapNavigationPoints` | GET | 현재 지도의 지점 목록 | [코드] |
| `/robotLoadMapOpenPosition` | POST | `{"mapId"}` 로 앱 지도 전환 | [코드] |
| `/restartRosNode` | POST | ROS 노드 재시작 | [코드] **주의** |
| `/getTimerCleanStrategyList`, `/saveCleanStrategy` 등 | | 청소 작업(전략) 관리 | [코드] |

### 3.3 그 외

- rosbridge: `ws://192.168.64.20:9090` (앱에 클라이언트 존재)
- JS 브리지 명령 목록(`RobotCmdEnum`): `ROBOT_MAP_CHANGE`, `ROBOT_OPEN_POSITION_WEB`, `CLEAN_BEGIN_WEB`, `ROBOT_RECHARGE_WEB` 등

## 4. 위치 지정 시 청소 앱이 함께 하는 일 [코드]

청소 앱의 위치 선택 화면은 위치 지정 전에 아래를 확인한다. 새 앱도 같은 조건을 지켜야 한다.

- 충전기에 올라가 충전 중이면 거부
- 손잡이(armrest) 상태 확인
- 재지정 모드 동안 모터 잠금 해제(`/motor/enable 0`) 후 종료 시 복구(`1`)

## 5. 남은 확인 사항 (로봇에서 직접 해 봐야 하는 것)

1. **지표점 등록 방법**: 지도 관리 앱에서 2~5층에 `landmark` 지점을 만들 수 있는지, 만든 뒤 `dest_posesV2` 에 나오는지
2. **`action 3` 실제 동작**: 다른 층의 지표점 id 로 요청했을 때 ROS 가 층을 바꾸고 `status 0` 이 되는지, 걸리는 시간
3. **2·3·4층 구분**: 세 층이 같은 지도라 ROS 는 센서로 구분하지 못한다. 지표점 id 로 층이 정해지는지 확인
4. 청소 앱이 떠 있는 상태에서 외부 앱이 CoAP 를 보내도 문제없는지 (읽기 요청부터)
5. 외부 앱에서 청소 작업을 시작시키는 가장 깔끔한 경로
