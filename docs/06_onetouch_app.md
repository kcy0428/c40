# 06. 원터치 층 전환 화면 (v0.3.0, 2026-10-15)

## 무엇이 바뀌었나

- 앱 첫 화면이 `HomeActivity`(원터치 화면)로 바뀌었다. 기존 진단 화면(`MainActivity`)은 설정 → "진단"에서 연다.
- 층마다 위치 지정 방법을 하나 고른다 (`SetupActivity`).
  - **기준 위치 (사전 정의 위치)**: 로봇을 그 층의 바닥 표시 자리에 두고 위치가 맞게 잡혀 있을 때 "지금 로봇 위치를 N층 기준 위치로 저장". 좌표 직접 입력도 된다.
  - **지표점**: 지도 관리 앱에 등록한 `landmark` 가 있으면 버튼으로 고른다 (CoAP `action 3`, docs/02).
- 저장할 때 그 층 지도 id(`mapFloorList`)를 함께 저장한다. 지도를 새로 만들어 id 가 바뀌면 "다시 설정 필요"로 표시하고 버튼을 막는다.
- 지도 미리보기: 청소 앱에 올라간 지도 그림(`getCurrentLoadMapInfo.pic`)을 지도 id 별로 앱 저장소에 캐시한다. 2026-10-15 "공대 3호" 1·2층 지도는 앱에 들어 있다(`assets/maps`).
- 기본은 **시험 모드**(아무것도 보내지 않음). 설정에서 "실제 전송"을 켜야 로봇에 요청을 보낸다.

## 기준 위치 방식의 요청 [실측: 서비스·인자 형식 / 미검증: 동작]

```
rosbridge ws://192.168.64.20:9090  call_service /switch_floor_map_only  (keenon_nav_msgs/SwitchMap, 제공 노드 /switch_map)
{"floor": <목표 층>, "PoseTagCurrent": <지금 ROS 위치>, "PoseTagNext": <목표 층 기준 위치>}
응답: {"result": bool, "msg": string}
```

엘리베이터 층 이동용 서비스로 보인다. 위치를 "현재 태그 → 다음 태그"로 옮기는 의미라면, 현재 태그에 지금 위치를 넣으면 로봇 위치가 기준 위치가 된다. **실제로 이렇게 동작하는지는 로봇에서 아직 시험하지 않았다.**

인식 확인: `/position/status` 의 `status 0`, `/position/robotPosInfo` 의 `floor == 목표 층`, 좌표가 기준 위치에서 1 m 이내. (`type`/`poseId` 는 이 방식에서 어떤 값이 오는지 몰라 보지 않는다.)

## 2026-10-15 실측 (읽기만)

| 요청 | 결과 |
|---|---|
| `GET /position/robotPosInfo` | `floor 2`, (0.09, 0.04), rotation 2.15 |
| `GET /position/status` | `{"position":["开机区域"],"status":0}` |
| `GET /charge/status` | `power 93`, `event 254` |
| `GET /navigation/dest_posesV2` | 2층 `2_자동 충전기`(charge) 1개뿐. `landmark` 없음 |
| `GET :8089/mapFloorList` | 1층 `95475fe5…`, 2층 `f48e2b45…` |
| `GET :8089/getCurrentLoadMapInfo` | 2층 지도 로드됨 |

## 검증

- `:core:test` 23개 (기준 위치 방식 5개 추가), `:mock-robot:test` 12개 통과.
- 에뮬레이터(API 28로 낮춰 빌드) + mock(`--real-maps`)에서 화면 흐름 확인: 설정 저장, 시험 모드 전환, 실제 전송 실패 처리(rosbridge 없음 → 위치 지정에서 멈춤), 지표점 방식 성공(인식 3초 → 청소 앱 지도 동기화 → "청소 앱 열기").
- 로봇에는 아직 설치하지 않았다.

## 남은 일

1. 로봇에서 `/switch_floor_map_only` 실제 동작 확인 (2층 → 1층 → 2층 왕복, 바퀴는 움직이지 않음).
2. 층마다 바닥 표시 자리를 정하고 기준 위치 저장.
3. `type` 값 확인 후 인식 판정 강화.
