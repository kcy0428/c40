# C40 원클릭 층 전환 — 조사 정리

- 작성일: 2026-10-15
- 목표: 로봇을 손으로 다른 층에 옮긴 뒤, 지금의 "지도 관리 앱 → 층 변경 → 청소 앱 → 로컬라이즈" 4단계를 **버튼 한 번**으로 줄인다. 위치는 천장 AprilTag나 미리 정해 둔 위치로 잡는다.
- 근거 자료
  1. 팀 저장소 [kcy0428/c40](https://github.com/kcy0428/c40) (`kr.c40.floor` 앱 소스와 docs 01~05, 마지막 커밋 2026-10-15 12:46)
  2. 이전 프로젝트 폴더 `..\peanut-sdk-v1.3.6` (Keenon **T10** 안내 로봇용 RobotNav, SDK v1.3.6 문서)
  3. 오늘 로봇에서 읽기 전용으로 직접 조회한 결과 → `robot_dump_2026-10-15\`
- 표기: **[실측]** 로봇에서 확인 / **[코드]** 디컴파일 코드에서 확인 / **[추정]** / **[미검증]** 아직 해 보지 않음. 팀 저장소와 같은 규칙입니다.

---

## 0. 요약

| 질문 | 답 |
|---|---|
| 층 전환의 실체 | ROS DB에는 여러 층의 지도가 같이 들어 있고, **위치 지정(relocation)으로 층이 정해진다.** 청소 앱은 ROS가 보고하는 층을 보고 지도를 스스로 바꾼다 [코드, 팀 docs/02] |
| 원클릭의 핵심 | CoAP `POST 192.168.64.20:5683/clean/relocationPosition {"action":3,"id":<지표점 id>}` 요청 1개 [코드, 미검증] |
| 이미 된 것 | 층 전환 시퀀서, mock 로봇, 테스트, 층 전환 화면(기본은 dry-run). `kr.c40.floor` v0.2.0이 로봇에 설치되어 있음 [실측] |
| 막힌 것 | ① 지금 지도에 **지표점(landmark)이 하나도 없음** [실측] ② `action 3`이 실제로 동작하는지 아직 확인 안 함 |
| **오늘 새로 확인** | ① 로봇의 scene이 오늘 12:28에 **군산 `BBTmda`로 바뀜. 지도는 1층 + 2층 두 장** (팀 docs의 "5개 층"은 이전 판교 scene 기준) ② ROS 보드의 rosbridge `:9090`과 SSH `:22`가 열려 있음 ③ ROS에 **층 전환 서비스**(`/switch_floor_map_only`, `/switch_dest_floor_map`)와 **천장 라벨 측위 기능**(`/ros_label_fusion`, `label` 테이블)이 원래 있음 |
| AprilTag | 직접 만들기 전에 **Keenon 자체 라벨 측위**를 쓸 수 있는지 먼저 확인하는 게 좋음. 지금은 꺼져 있고(`setHasVSlamLabel false`) 등록된 라벨이 0개 [실측] |

---

## 1. 로봇 현재 상태 (2026-10-15 14:00경) [실측]

### 1.1 scene과 지도

| 항목 | 값 |
|---|---|
| 현재 scene | `BBTmda` "군산대 2층지도" (`/sdcard/peanut/inter_app/app_share_params.properties`: `map.scene.code=BBTmda`, `map.uuid=5ec86333…`) |
| 로봇에 있는 scene 파일 | `4aNNWV`, `BBTmda`, `SQbxeF`, `kj0T5Q`, `tNCAXl`(판교, 1~5층) |
| ROS 지도 zip | `config/database/uuidzip/4f9c77c0….zip` (현재 scene의 zip 1개만 있음) |

ROS `peanut.db`의 `dynamic_map` 테이블:

| floor | 종류 | floorInfo / building | 크기(px) | origin |
|---|---|---|---|---|
| 1 | carto_map, virtual_wall, user_map | `1` / `A` | 742×1919 | (−19.55, −54.5) |
| 2 | carto_map, virtual_wall, user_map | `2층` / `공대` | 1920×744 | (−40.3, −16.3) |

→ **한 scene에 서로 다른 건물·층의 지도 두 장이 들어 있습니다.** 팀 docs/05의 "A 구조(한 scene에 여러 층)"가 지금 실제로 갖춰져 있다는 뜻입니다.

### 1.2 지점 (`pose` 테이블)

| target_id | 이름 | type | floor |
|---|---|---|---|
| 1 | 1_자동 충전기 | `charge` | 1 |
| 2 | 양보 포인트_2 | `elevator_avoid_obstacle` | 2 |
| 15 | 출발점15-2 | `zone_start_pose` | 2 |

- `landmark` 타입 지점이 **없습니다.** `action 3`을 쓰려면 지도 관리 앱에서 층마다 지표점을 먼저 등록해야 합니다.
- `init_pose`(부팅 시 위치)는 1개뿐이고, 1층 충전기 근처입니다.
- `label` 0행, `vslamKeyframe` 0행, `semantic_map` 0행, `elevator` 0행.
- `zone` 16행(청소 구역)과 `path` 3행이 있습니다.

### 1.3 오늘 로그에서 본 동작

- 13:50~13:55: 충전대 → 충전 해제(`CHARGE_LEAVE`) → 충전대 맞추기(`CHARGE_MATCH`) → `IDLE` → `HANDLE`(손으로 미는 모드). 13:55에는 relocation 상태가 `status=1 type=1`(인식 중)로 이어졌습니다. → 누군가 로봇을 직접 다루던 중으로 보입니다.
- ROS 쪽 `/ros_label_fusion`은 1분마다 `wait for label message ...`만 남기고 있습니다. 라벨 측위 노드는 돌고 있지만 라벨 입력이 없다는 뜻입니다.

---

## 2. 층 전환에 쓸 수 있는 경로 (쉬운 순서)

### 경로 A. 지표점 위치 지정 — 팀 설계안 (권장)

```
POST coap://192.168.64.20:5683/clean/relocationPosition   {"action":3,"id":<landmark target_id>}
→ /position/status, /position/robotPosInfo 로 floor·status 확인
→ :8089/getCurrentLoadMapInfo 로 청소 앱 지도가 따라왔는지 확인 (안 따라오면 /robotLoadMapOpenPosition)
```

- `kr.c40.floor`의 `FloorSwitchSequencer`가 이미 이 흐름을 구현하고 있습니다(사전 점검 → 확인 → 위치 지정 → 인식 확인 → 지도 동기화).
- 할 일: ① 1층과 2층에 지표점 등록(지도 관리 앱) ② 청소 앱 "위치 선택" 화면으로 한 번 해 보고 로그 캡처(팀 설계안의 A 단계) ③ 그다음 앱으로 실제 전송
- "미리 정해 둔 위치" 방식이 바로 이것입니다. 바닥 스티커 위에 로봇을 놓고 그 층의 지표점 id로 위치를 지정합니다.

### 경로 B. ROS 층 전환 서비스 직접 호출 [미검증, 새로 발견]

rosbridge `ws://192.168.64.20:9090`(태블릿 경유)에 아래 서비스가 있습니다. **오늘은 목록과 형식만 읽었고, 호출하지는 않았습니다.**

| 서비스 | 타입 | 요청 필드 | 추정 용도 |
|---|---|---|---|
| `/switch_floor_map_only` | `keenon_nav_msgs/SwitchMap` | `floor`, `PoseTagCurrent`(Pose), `PoseTagNext`(Pose) | 지도만 층 전환 |
| `/switch_dest_floor_map` | `keenon_nav_msgs/SwitchMap` | 위와 같음 | 엘리베이터 도착 후 목표 층 지도로 전환 + 위치 지정 |
| `/nav_switch_map_prepare`, `/elevator_switch_map_prepare` | `std_srvs/Empty` | – | 전환 준비 |
| `/notify_map_change` | `keenon_nav_msgs/NotifyMapChange` | `floor`, `type` | 지도 변경 통지 |
| `/localization/set_pose` | `nav_msgs/SetMap` | `map`, `initial_pose` | 지도와 초기 위치 지정 |
| `/initialpose_config` | `keenon_ui_msgs/initialpose_config_msg` | `config_pose` | 초기 위치 설정 |
| `/init_location_node/config` | `keenon_location_msgs/init_pose_config` | `data{id,name,x,y,th,floor,angleRange,confidence,penetrate}`, `opt` | 부팅 위치 등록 |
| `/save_current_pose`, `/save_current_init_pose` | DB 저장 | – | 현재 위치를 지점이나 부팅 위치로 저장 |

- `SwitchMap`은 엘리베이터 승강 흐름에서 쓰는 서비스로 보입니다. "층 + 현재 위치 + 다음 위치"를 한 번에 넘기므로 **층 전환과 위치 지정을 한 번에** 할 가능성이 있습니다 [추정].
- ROS 파라미터 `peanut_localization_node/relocation/switchMapSetPoseWaitTime = 4.0`이 이 흐름을 뒷받침합니다.
- 위험: 청소 앱을 거치지 않으므로 청소 앱 상태와 어긋날 수 있습니다. 팀 설계 원칙(지도 다운로드, 재부팅 금지)과는 충돌하지 않지만, **경로 A가 안 될 때 쓰는 대안**으로 두는 게 맞습니다.
- 관련 서비스 전체 타입 정의: `robot_dump_2026-10-15/ros_rosapi_nodes_services.json`

### 경로 C. scene 교체 — 층마다 scene을 따로 만든 경우 (비권장)

팀 docs/05: zip을 TFTP로 전송 → `/runtime/download_action` → **ROS 재부팅** → 로컬라이즈. 약 90초가 걸리고 위험합니다. 지금 군산 scene처럼 **한 scene에 층을 모아 두면 필요 없습니다.**

---

## 3. 천장 태그 / AprilTag

### 3.1 Keenon에 원래 있는 라벨 측위 [실측 + 추정]

| 근거 | 내용 |
|---|---|
| ROS 노드 | `/ros_label_fusion`, `/ros_label_filter` 실행 중 |
| 서비스 | `/label_recognized {label_id}`, `/label_map {gmapping_label{x,y,z,yaw,roll,pitch,id,floor_id}, show}`, `/database/config_label {option, label{id,floor,bind_map_md5,pose}}`, `/label_fusion/delete_labels`, `/localization/label_pose_check`, `/label_switch/param_cmd` |
| DB | `label` 테이블: `label_id`, `base_map`, **`floor`**, position/orientation → **라벨 하나 = 층 + 자세** |
| 파라미터 | `persistence_params/label_switch = 2`, `location_fusion/checkLabelRobotType = "T8"`, 라벨 설정 파일 `/etc/ros/runtime/maps/label.json`(ROS 보드, SMB 공유 밖이라 아직 못 읽음) |
| 청소 앱 | `RosLocationType 6` = "라벨/VSLAM 초기 위치 인식" [코드], 오늘 로그 `setHasVSlamLabel false` |
| SDK 문서 v1.3.6 | 오류 코드 `203306 没有标签数据超时`(라벨 데이터 없음 타임아웃). 엘리베이터 흐름에 "**跨楼层地标点重定位**"(층을 넘는 지표점 재지정) 문구가 있음 |
| 홈 폴더 | `ros/home/label/map_labels.txt`, `raw_labels.txt` (둘 다 0바이트, 오늘 12:36 갱신) |

→ Keenon 배송 로봇(T8 등)은 **천장 라벨(태그)** 로 위치를 잡는 기능이 있고, C40 ROS에도 같은 모듈이 들어 있습니다. 라벨 하나에 층 정보가 들어가므로 **"태그를 보면 층과 위치가 함께 정해지는"** 구조입니다 [추정].

**확인할 것**: ① C40 카메라(`mx`, 스테레오)가 천장 라벨을 볼 수 있는 위치·방향인지 ② Keenon 라벨이 어떤 규격인지(AprilTag인지 자체 마커인지) ③ 지도 관리 앱에 라벨 등록 메뉴가 있는지

### 3.2 직접 구현할 경우

- 태블릿에는 카메라가 없습니다(팀 docs). USB 카메라를 추가하거나 ROS 카메라 스트림을 써야 합니다.
- 참고: Keenon T5의 IR 스테레오 카메라를 ZeroMQ(`tcp://10.11.11.1:20000`)로 받아 쓰는 공개 드라이버가 있습니다([AntonyJuza/depth-camera-ws](https://github.com/AntonyJuza/depth-camera-ws)). C40 카메라도 `mx` 계열이라 비슷한 경로가 있을 수 있습니다 [추정].
- 태그를 인식한 뒤에는 "태그 id → 지표점 id"로 연결해 경로 A를 그대로 쓰면 됩니다(팀 설계안 6장).
- T10 프로젝트 경험(`peanut-sdk-v1.3.6\하드웨어_사양.md`): Keenon VSLAM은 천장 높이 2~6 m가 필요하고, 조건이 나쁘면 relocation 점수가 무너졌습니다. 태그 방식에도 같은 천장 조건 점검이 필요합니다.

---

## 4. 통신 경로 정리 (C40)

| 경로 | 주소 | 용도 | 상태 |
|---|---|---|---|
| CoAP | UDP `192.168.64.20:5683` | Peanut SDK ↔ ROS (위치 지정, 상태) | 팀 앱에서 GET 실측 |
| 청소 앱 REST | `localhost:8089` (인증 없음) | 층 목록, 현재 지도, 지도 전환 | 실측 |
| 청소 앱 웹 UI | `:8090` | JS 브리지 | – |
| rosbridge | `ws://192.168.64.20:9090` (+ `/control/rosbridge_websocket`) | ROS 서비스/토픽 | **오늘 실측** (nodes, services 응답 OK, topics는 타임아웃) |
| SSH | `192.168.64.20:22` | ROS 보드 셸 | 포트 열림만 확인, 계정 모름 |
| 기타 열린 포트 | 80, 8080, 9013, 9091, 11311(ROS master), 12386(WebSocket), 34569(HTTP), 445(SMB) | – | 열림만 확인 |
| TFTP | 태블릿 `192.168.64.10:9527` | 지도 zip 전송 | 팀 docs/05 |

**PC에서 rosbridge 쓰는 법** (T10 프로젝트 방식이 C40에서도 그대로 동작함):

```powershell
adb -s 192.168.0.67:5555 forward tcp:19090 tcp:19090
adb -s 192.168.0.67:5555 shell "toybox nc -p 19090 -L toybox nc 192.168.64.20 9090"   # 이 창을 켜 둔다
# 다른 창에서: python ..\peanut-sdk-v1.3.6\floor_build\rosws.py /rosapi/nodes
```

> 태블릿의 `busybox nc`로는 포트 검사가 제대로 안 됩니다. `toybox nc`를 쓰세요.

---

## 5. `peanut-sdk-v1.3.6` 폴더 (T10 프로젝트)에서 가져다 쓸 것

| 파일 | 쓸모 |
|---|---|
| `floor_build/rosws.py` | 라이브러리 없이 rosbridge 서비스를 호출하는 Python 코드. 오늘 C40 조회에 그대로 사용함 |
| `floor_build/floorkit.py` | `/database/config_dynamic_map`, `/database/config_pose`로 층과 지점을 읽는 예제. 층이 온전하려면 `carto_map`, `virtual_wall`, `topo`, `topo_mid_data`가 있어야 한다는 메모가 있음(T10 기준) |
| `릴레이_켜기.cmd`, `relay_start.cmd` | PC ↔ rosbridge 릴레이 (IP만 `192.168.0.67`로 바꾸면 됨) |
| `wireshark_captures/scripts/ros_live_sub.py` 등 | ROS 토픽 구독 예제 |
| `doc/Peanut SDKV1.3.6开发文档.docx` | SDK 1.3.6 (2026-06). 엘리베이터 상태 토픽 `ELEVATOR_STATUS {curFloor, dstFloor, …}`, 층을 넘는 지표점 재지정, 지도 데이터 가져오기(1.3.6에 추가) |
| `하드웨어_사양.md` | T10의 ROS 노드 50개 목록. C40의 57개와 거의 같은 구성(`/switch_map`, `/location_fusion`, `/ros_label_*`) |

> 주의: T10에서는 `floor=1`이 건물 2층이었습니다(앱 상수 기준). **C40은 층 번호를 따로 확인해야 합니다.** 지금 군산 scene은 floor 1 = A동 1층, floor 2 = 공대 2층입니다.

---

## 6. 다음 할 일 (제안 순서)

1. **지표점 등록**: 지도 관리 앱에서 1층(A)과 2층(공대) 시작 위치에 `landmark`를 하나씩 만든 뒤, `pose` 테이블과 CoAP `/navigation/dest_posesV2`에 나오는지 확인
2. **청소 앱으로 1회 실측**: 청소 앱 "위치 선택"으로 다른 층의 지표점을 골라 위치 지정 → logcat(`PeanutSDK:SDK--`, `RosLocationManager`)과 ROS 로그(`peanut_localization_node`)를 캡처. `action 3` 요청·응답과 `/position/status` 필드를 확보
3. **`kr.c40.floor`로 실제 전송**: `SequencerConfig`의 미측정 값(`chargingEvents`, 인식 후 `status`/`type`/`poseId`)을 2번 결과로 채움
4. (병행) **라벨 측위 확인**: ROS 보드의 `/etc/ros/runtime/maps/label.json`(SSH 계정이 필요하거나 판매처 문의), 지도 관리 앱의 라벨 메뉴, C40 카메라 방향 확인
5. 경로 A가 안 되면 경로 B(`/switch_floor_map_only`)를 **로봇이 정지해 있을 때 한 번만** 시험

---

## 7. 이번 조사에서 한 일과 남긴 것

- 모두 **읽기 전용**입니다. 위치 지정, 지도 전환, 작업 시작 같은 쓰기 요청은 보내지 않았습니다. rosbridge도 `/rosapi/*` 조회만 했습니다.
- 태블릿에 만든 임시 파일(`/data/local/tmp/c40dump`, 스크립트)과 릴레이 포워딩은 지웠습니다.
- 수집 파일: `robot_dump_2026-10-15\`

| 파일 | 내용 |
|---|---|
| `ros_peanut.db` (+ `-shm`) | ROS 지도 DB 복사본 (현재 군산 scene) |
| `scene_hotel_*.json` | scene 5개 정의 |
| `app_share_params.properties` | 현재 scene 코드, 지도 uuid |
| `ros_rosapi_nodes_services.json` | ROS 노드 57개, 서비스 323개, 관련 서비스 83개의 타입과 요청 필드 |
| `ros_params_label_floor.json` | ROS 파라미터 4,558개 이름 + 라벨/층/위치 관련 값 |
| `ros_log_floor_label_excerpt.txt` | ROS 로그(12:53~14:06) 중 위치·라벨 관련 줄 |
| `tablet_log_position_excerpt.txt` | 태블릿 logcat(오늘) 중 위치·층 관련 줄 |
| `schedule*.json`, `aside_path_1floor.json`, 센서 설정 파일 | ROS config 복사 |

> 원본 DB와 로그에는 기기 식별자가 들어 있습니다. 팀 저장소에 올릴 때는 팀 규칙대로 가려야 합니다(`*.db`는 커밋 금지).
>
> 참고: 팀 저장소 CLAUDE.md에는 "Wi-Fi adb를 쓰지 않는다"는 메모가 있습니다. 이번에 켠 Wi-Fi ADB(`persist.internet_adb_enable=1`, 포트 5555, 인증 없음)는 팀원과 공유해 두는 게 좋습니다.
