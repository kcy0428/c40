# scene 전환 실측 (4차)

- 분석일: 2026-10-15
- 근거: 로봇 logcat `/sdcard/krlog/log_20261015` (11:57~12:42, 태블릿 시계). 사용자가 지도 관리 앱으로 새 scene 을 만들고 적용한 구간이다. 원본 로그는 기기 식별자와 토큰이 있어 `local/logs/` (git 제외) 에만 두고, 여기에는 요청 형식만 적는다.
- 표기: **[실측]** = 로그/파일로 확인, **[추정]** = 로그에서 읽은 흐름으로 짐작, **[미검증]** = 해 보지 않음

## 1. scene 이란 [실측]

scene 은 **지도 한 장이 아니라 "지도 묶음"** 이다. `/sdcard/peanut/scene/scene_hotel_<sceneCode>.json` 한 파일이 한 scene 이다.

| 항목 | 내용 |
|---|---|
| `sceneCode`, `sceneName` | 식별자와 이름 (예: `tNCAXl` "라이노스 빌딩 지도", `BBTmda` "군산대 2층지도") |
| `rosMap` | ROS 지도 DB 묶음 zip 1개 (`md5`, 클라우드 `url`, `id`=mapUuid). **그 안에 여러 층의 지도가 들어간다** |
| `targetInfoList` | 건물/층/지점 목록 (`buildingName`, `floorId`, `floorName`, `name`, `type`) |

- 판교 `tNCAXl` 은 1~5층이 한 scene 이다 (층별 지도는 따로, 묶음은 하나).
- `4aNNWV` "Floor 7 office" 처럼 한 층뿐인 scene 도 있다.
- scene 의 `targetInfoList` 가 층·건물을 여러 개 담을 수 있으므로, 한 scene 에 층을 더 추가하는 구조는 데이터상 가능하다 [추정, 지도 관리 앱에서 해 보지 않음].

## 2. scene 전환 = 지도 묶음 교체 + ROS 재부팅 + 로컬라이즈 [실측]

12:28 에 판교 `tNCAXl` → 군산 `BBTmda` 로 바꾼 로그의 순서 (세 번의 푸시가 모두 같았다):

| 시각 | 동작 | 요청 |
|---|---|---|
| 12:28:02.5 | 청소 앱이 scene 변경 통지 | `PeanutResourceManager.postSceneChange` (`切换本地资源场景`, sceneCode=BBTmda) |
| 12:28:02.6 | 지도 묶음 전송 지시 | CoAP `POST /runtime/download {"md5":..,"url":"tftp://192.168.64.10:9527/<md5>.zip","type":"database","mapUuid":..}` |
| 12:28:03.3 | 설정 전송 | `POST /runtime/resourceConfig {...}` |
| 12:28:03.3 | 적용 지시 | `POST /runtime/download_action {"action":1}` |
| 12:28:03.8 | **ROS 재부팅** | `POST /devices/reboot {"id":1,"name":"robot-arm","isReposition":false}` |
| 12:29:15 | 재부팅 완료 확인 후 동기화 | `RosMapDownLoadManager.queryRosRestartStatus`, `rosMapDataSync` (`mSceneChanged=true, oldScene=tNCAXl`), `inter_app` 에 `map.scene.code`, `map.uuid` 기록 |
| 12:29:30 | **로컬라이즈** | `POST /clean/relocationPosition {"action":1,"id":0}` → 약 6초 뒤 `{"action":2,"id":0}` (사이에 `/motor/enable` 0/1) |

- 재부팅 지시부터 로컬라이즈 시작까지 약 **86~89초**.
- 지도 묶음 zip 은 **태블릿(192.168.64.10:9527, TFTP)** 이 ROS 에 내려준다. ROS 쪽 `uuidzip/` 에는 현재 scene 의 zip 하나만 남는다 (`<md5>.zip`).
- 시작점은 **지도 관리 앱(`mapping-app`) → 클라우드 API** 로 보인다: `/api/tool_support/mapping/v4/robot/sync` (robot 목록, uuid, sceneCode). zip 의 원본 주소는 `cloud.robotkeenon.com` [추정: 클라우드 경유 전환].
- 청소 앱 HTTP `:8089` 에는 scene 전환 API 가 **없다** (`reference/api/http_8089_routes.tsv`, ROS 재시작 `/restartRosNode` 만 있음).

## 3. 같은 scene 안의 "층 변경" 은 앱 쪽 표시만 바꾼다 [실측]

청소 앱 구역 선택 화면의 층 탭(`changeFloor {"params":{"mapId":..}}`)을 연달아 눌렀을 때 12:42:16~34 사이 ROS 요청이 없었다. 02 문서의 결론(지도 표시 전환, ROS 층은 위치 지정이 결정)과 일치한다.

## 4. 설계에 주는 영향

| | A. 한 scene 에 여러 층 | B. 층마다 scene |
|---|---|---|
| 전환 | 위치 지정(지표점)만 — 현재 구현 (`core`) | 위 2장 전체 (zip 교체 + ROS 재부팅 + 로컬라이즈) |
| 소요 | 수 초~수십 초 | 수 분 |
| 위험 | 낮음 | 지도 DB 교체, ROS 재시작, 클라우드/지도 앱 연동 |
| 외부 앱 호출 | `:8089` / CoAP 로 가능 | 청소 앱 API 없음. 지도 앱·클라우드 흐름을 대신하거나 zip 을 직접 올려야 함 [미검증] |

→ 가능하면 **A**. B 는 현재 `FloorSwitchSequencer` 가 다루지 않는다 (설계안 03 은 `/runtime/download`, `/devices/reboot` 사용을 금지해 두었다).

## 5. 사고 기록: 판교 scene 이 수정됨 [실측]

오늘 11:57 의 첫 푸시로 판교 `tNCAXl` 의 `scene_hotel_tNCAXl.json` 이 바뀌었다 (`rosMap.md5` `a2d86fd9…` → `830c6e11…`, 푸시한 mapUuid `45c85a85…`). 로봇의 ROS 는 지금 군산 scene(`BBTmda`)의 지도로 돌고 있고 판교 ROS 지도는 로봇에 남아 있지 않다. 원본은 백업(`pangyo_20261014`, 두 디스크)에 있다.

## 6. 아직 모르는 것

1. 지도 관리 앱에서 한 scene 에 층을 추가하는 방법 (A 가능 여부)
2. A 구조에서 로컬라이즈(`action 1/2`)가 어느 층의 지도로 위치를 잡는지
3. B 를 외부에서 트리거하는 방법 (클라우드 API 인증 필요 여부, zip 을 로컬에서 직접 올릴 수 있는지)
4. 11:57 푸시가 판교 scene 의 어떤 변경(저장, 편집)이었는지
