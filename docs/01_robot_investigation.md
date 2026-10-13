# C40 로봇 조사 보고서 (1차)

- 조사일: 2026-10-13
- 조사 방법: `adb` (WiFi, `192.168.1.155:5555`) 읽기 전용 조사. 로봇 설정/지도/동작은 변경하지 않았다.
- 목적: 층 이동 후 "지도 전환 → 위치 지정 → 인식 확인 → 청소 시작"을 원클릭으로 수행하는 앱을 만들기 위한 사전 조사
- 기기 고유 식별자(MAC, 시리얼)는 공개 저장소이므로 마스킹했다.

## 0. 핵심 요약

| 항목 | 결과 |
|---|---|
| 구조 | Android 태블릿(RK3288, UI/앱) + ROS 제어부(내부망 `192.168.64.20`, SLAM/주행) 2보드 구조 |
| 앱 ↔ ROS 통신 | **CoAP over UDP 5683** (Keenon PeanutSDK). JSON 페이로드 |
| 앱이 여는 로컬 API | 청소 앱이 **HTTP 8089(REST, 인증 없음)**, **HTTP 8090(웹 UI)** 를 연다 |
| 지도 | 1~5층 5개 지도가 이미 하나의 "씬"에 등록됨. 2·3·4층은 같은 지도(md5 동일) 복사본 |
| 층 전환 | 청소 앱 웹 UI의 JS 브리지 `changeFloor({params:{mapId}})` 로 수행됨 (네이티브 내부 구현은 미확인) |
| 위치 지정 | CoAP `POST /clean/relocationPosition {"action":1\|2,"id":0}` 호출 실측. 결과는 `/position/status` 로 확인 |
| 루트 권한 | `su 0` 가능 (userdebug 빌드, SELinux permissive 로그 확인) |
| **태블릿 카메라** | **없음 (camera devices = 0)**. AprilTag 방식은 ROS 쪽 카메라를 써야 함 |
| 권장 방향 | **사전 정의 위치(바닥 스티커) 방식**을 먼저 구현. AprilTag는 2단계 과제 |

## 1. 시스템 기본 정보

### 1.1 Android 태블릿 (상위 UI 보드)

| 항목 | 값 |
|---|---|
| 제조사 / 모델 | keenon / `Delivery Robot` (device `rk3288_keenon_tablet`) |
| SoC / ABI | Rockchip RK3288 / armeabi-v7a (32bit) |
| Android | 10 (SDK 29), 빌드 `rk3288_EDP11.6_v10.3.1`, **userdebug** |
| 커널 | Linux 4.19.193 (2025-04-10 빌드) |
| RAM / 저장소 | 2GB / `/data` 10GB 중 3.7GB 사용 |
| 화면 | 11.6" EDP |
| 로봇 종류 | `robot.type=C40`, 앱 패키지 버전 `C40 S-LS-M013C00-RW-F00-V243` |
| 홈 런처 | `com.android.launcher3` |
| 기타 설치 앱 | Termux, AndSMB, ZArchiver, Gboard, ilitek 터치 유틸 |

### 1.2 네트워크

| 인터페이스 | 주소 | 용도 |
|---|---|---|
| `wlan0` | 192.168.1.155/24 | 외부 WiFi (adb, 클라우드) |
| `eth0` | 192.168.64.10/24 | 로봇 내부망 (ROS 제어부와 직결) |
| ROS 제어부 | 192.168.64.20 | SLAM / 주행 / 청소 제어 |

### 1.3 ROS 제어부 (하위 보드, `192.168.64.20`)

- Ubuntu 14.04(trusty) armhf + **ROS Indigo**, 패키지 `slam-ros-v2.10.1-c40alpha7`
- SLAM: Cartographer (`carto_map`), 그 외 `cleanning-planner`, `chassis`, `costmap-2d`, `astra-camera`, `berxel-camera` 등
- 센서 설정: `camera_type=mx`, `lidar_type=free`, 저상 레이저 좌/우 + 전방 엣지 레이저
- 시계가 태블릿보다 **1시간 느림** (ROS 로그 시각 해석 시 주의)
- 열린 TCP 포트: `22`(ssh), `80`, `8080`, `139/445`(samba), `9013`, `9090`, `11311`(roscore) / UDP `5683`(CoAP)
- 태블릿에 SMB로 마운트됨
  - `//192.168.64.20/home` → `/sdcard/ros/home`
  - `//192.168.64.20/config` → `/sdcard/ros/config`
- 시작 스크립트: `cd /work/scripts && python guard.py &`

## 2. 앱 구성

| 패키지 | 버전 | 역할 | UID |
|---|---|---|---|
| `com.keenon.peanut.clean` | v3.7.6 | **청소 앱** (메인, 항상 실행). 런처 `SetupActivity` | u0_a96 |
| `com.keenon.peanut.peanutservice` | v4.9.10 | **지도 관리 앱** (Chromium 기반, `SimpleChromeActivity`, `MapBaseApplication`). 평소엔 미실행 | u0_a92 |
| `com.keenon.systemservice` | v2.0.11 | 시스템 서비스(음성, 통계, 셸 유틸) | system |
| `com.keenon.android_peanut_mfg` | v3.6.1 | 공장/정비용 앱 | system |
| `com.keenon.krlog` | v1.2.6 | logcat 수집 → `/sdcard/krlog` | u0_a60 |
| `com.keenon.remote_control_oray` | v3.3.1 | 원격 제어(Sunlogin), ROS `9013` 연결 | system |

청소 앱의 주요 화면(Activity) — 전체 목록은 `reference/api/clean_app_components.txt`

- `ui.setup.SetupActivity`, `ui.activity.main.MainActivity`, `ui.work.WorkActivity`
- `ui.location.LocationActivity`, `ReLocationActivity`, `PositionChooseActivity`, `RetrieveLocationActivity` ← 위치 지정 관련
- `ui.activity.area.SelectAreaActivity` ← 층/구역 선택 (`onChangeFloor` 처리)
- `ui.password.PassWordActivity` (조사 시점의 전면 화면)

청소 앱은 지도 관리 앱으로 "점프"하는 구조이다(`switchEnableJumpMappingApp`). 두 앱은 `/sdcard/peanut/inter_app/app_share_params.properties` 로 지도 상태를 공유한다.

```
map.scene.code=tNCAXl
map.uuid=25188c19bf2244ae98be2a0977463597
map.push.state=success
```

## 3. 로봇 제어부와의 통신 경로

```
┌────────────────────── Android 태블릿 (192.168.64.10) ──────────────────────┐
│  지도 관리 앱(peanutservice)        청소 앱(peanut.clean)                  │
│        │  공유 properties / HTTP        │ HTTP :8089 REST / :8090 웹 UI    │
│        └───────────────────────────────┤                                  │
│                                PeanutSDK (RobotApi)                        │
└───────┬───────────────┬──────────────────┬─────────────────┬──────────────┘
        │ CoAP/UDP 5683 │ TFTP (지도 DB 전송)│ SMB 445 (파일 공유)│ 시리얼
        ▼               ▼                  ▼                 ▼
   ROS 제어부 192.168.64.20 (ROS Indigo, Cartographer)   STM32 보드
                                                  /dev/ttyS1, ttyS2, ttyUSB0
```

### 3.1 CoAP (주 제어 채널)

- `tcpdump` 로 확인: 태블릿 임시 포트 ↔ `192.168.64.20:5683` UDP 양방향
- SDK 로그: `defaultLinkType=COAP`, 2초 주기 `CoAPPing`
- 요청 형식: `path`, `type`(GET / POST / POST_LARGE / OBSERVER), `msgType`(CON / NON), JSON `params`
- 응답 형식: `{"code":0,"data":{...},"msg":"success","status":0}`

실제 로그에서 관측된 경로 (전체: `reference/api/coap_paths_observed.txt`)

| 분류 | 경로 | 비고 |
|---|---|---|
| 위치 상태 | `GET/OBSERVER /position/status` | `{"floor":1,"poseId":1,"position":["开机区域"],"status":0,"type":4}` |
| 현재 좌표 | `OBSERVER /position/robotPosInfo` | `{"floor":1,"robotPos":{"x":-2.86,"y":-0.88,"rotation":1.6}}` |
| 위치 재지정 | `POST /clean/relocationPosition` | `{"action":1,"id":0}` → 약 1.6초 뒤 `{"action":2,"id":0}` |
| 청소 위치 상태 | `GET/OBSERVER /clean/positionStatus` | `{"status":1,"type":1}` |
| 모터 | `POST /motor/enable` | `{"enable":0\|1}` (재지정 중 모터 해제 → 재활성) |
| 지도 DB 전송 | `POST /runtime/download` | `{"md5":..,"url":"tftp://192.168.64.10:9527/<md5>.zip","type":"database","mapUuid":..}` |
| 지도 적용 | `POST /runtime/download_action {"action":1}` → `POST /devices/reboot {"id":1,"name":"robot-arm","isReposition":false}` | ROS 재시작 동반 |
| 스케줄 설정 | `POST /runtime/resourceConfig` | |
| 상태 구독 | `/clean/status`, `/navigation/status`, `/charge/status`, `/elevator/status`, `/schedule/status` 등 | OBSERVER |
| 하위 보드 통과 | `POST /through/down`, `OBSERVER /through/up` | base64 바이너리 |

앱 코드에 존재하지만 로그에서 아직 관측하지 못한 후보 경로 (전체: `reference/api/clean_app_path_strings.txt`)

- 지도: `/map/v1/getMapList`, `/map/v1/getMapInfo`, `/map/v1/loadMap`, `/map_trans_pose`
- 위치: `/position/relocationPosition`, `/position/action`, `/position/location_status`, `/position/originJudge`, `/initialpose`
- 청소: `/clean/v1/startTask`, `/clean/v1/startQuickTask`, `/clean/v1/pauseTask`, `/clean/v1/stopTask`, `/clean/v1/recharge`

### 3.2 청소 앱 로컬 HTTP 서버 (원클릭 앱의 유력한 연동 지점)

- `:8089` — REST API (`PeanutHttpServer`), 인증 없음, `Access-Control-Allow-Origin: *`
- `:8090` — 내장 웹 UI(Vue). 예: `http://localhost:8090/#/checkArea`, `#/nav`

읽기 전용 GET 으로 실제 확인한 응답

| 요청 | 응답 |
|---|---|
| `GET :8089/mapFloorList` | 1~5층의 `floor`, `mapId`, `mapName` 목록 (아래 4.1 표와 일치) |
| `GET :8089/getCurrentLoadMapInfo` | 현재 로드된 지도 전체 정보(구역, 경로 등 약 214KB JSON) |
| `GET :8089/getMapInfo` | `{"code":"290001","msg":"地图id为空"}` → `mapId` 파라미터 필요 |

코드에 있는 그 외 경로(미호출): `/robotLoadMapOpenPosition`, `/listCurrentMapNavigationPoints`, `/getMapInfoFilterElevator`

### 3.3 웹 UI ↔ 네이티브 JS 브리지

웹 UI는 `WebViewJavascriptBridge` 로 네이티브를 호출한다 (`reference/api/web_jsbridge_handlers.txt`).

- **`changeFloor`** — `callHandler("changeFloor", {params:{mapId:"<MAP_ID>"}})`. 층 탭을 누르면 `getMapInfo(floor)` 후 호출됨
- 네이티브 쪽: `OriginalChangeFloorBean` → 로그 포맷 `RobotMapChangeNotifyEvent changeFloor end :FROM:%s-%s,TO:%s-%s`
- 그 외: `javaFun_useJava`(범용), `checkAreaList`, `checkNewAreaList`, `onMaxFloor`, `onH5Ready`

### 3.4 시리얼 / 클라우드

- 시리얼: `comRobot=/dev/ttyS1`, `comEmotion/comBattery=/dev/ttyS2`, `comLight=/dev/ttyUSB0`. 프레임 `AA AA … 55 55`
- 클라우드: MQTT `ssl://cloud.robotkeenon.com:8883`, REST `https://cloud.robotkeenon.com/api/...` (위치를 주기적으로 보고)

## 4. 지도 / 위치 관련 데이터

### 4.1 데이터 저장 위치

| 위치 | 내용 |
|---|---|
| `/sdcard/keenon/db/kc01.db` | **청소 앱 쪽 지도 DB**: `ROBOT_MAP`, `MAP_POINTS`, `MAP_AREA`, `MAP_VIRTUAL_WALL`, `CLEAN_STRATEGY`, `SYS_DICT*` |
| `/sdcard/ros/config/database/peanut.db` | **ROS 쪽 지도 DB**: `dynamic_map`, `pose`, `init_pose`, `zone`, `path`, `label`, `RobotPos`, `elevator` |
| `/sdcard/ros/config/database/uuidzip/<md5>.zip` | 마지막으로 푸시된 지도 DB 묶음 |
| `/sdcard/ros/config/schedule/` | 토폴로지 경로 (`path.json`, `topo_path.db`) |
| `/sdcard/peanut/scene/scene_hotel_*.json` | 씬(건물) 정의. 현재 사용: `tNCAXl` |
| `/sdcard/Android/data/com.keenon.peanut.clean/files/peanut_sources/peanut_db/` | 앱 컴포넌트/작업 DB |

스키마: `reference/db/*_schema.sql`, 데이터: `reference/db/*.csv`

등록된 지도 (`ROBOT_MAP`, 해상도 0.05 m/px, 원점 x=-11.6, y=-29.4)

| 층 | MAP_ID | 지도 md5 | 크기(px) | LOAD_STATE |
|---|---|---|---|---|
| 1 | `95475fe5cd3542089f9ef13e67232d85` | f846d0d6… | 577×811 | **1 (로드됨)** |
| 2 | `2dec60bde7f141a2be152260b1de02c5` | b0719dda… | 576×807 | 0 |
| 3 | `6e9a8461ef944180b48516ed17bbbc7d` | b0719dda… | 576×807 | 0 |
| 4 | `3ce4ab9232da45b181e0d0b4e9efed43` | b0719dda… | 576×807 | 0 |
| 5 | `2048c3d409b342229ad47f2a1d4f263c` | 4f72ff29… | 577×808 | 0 |

- 2~5층 지도는 2026-10-08 17:53 에 추가됨. **2·3·4층은 md5가 같은 동일 지도**
- 청소 구역(`cover_zone`)은 1층 20개, 5층 5개만 있음. **2~4층에는 청소 구역이 아직 없다**

### 4.2 좌표계

- ROS 좌표: 미터 단위, 자세는 쿼터니언(`orientation_z`, `orientation_w`)
- 앱 좌표(`MAP_POINTS.COORDS_INFO`): 지도 이미지 픽셀
- 변환(1층 실측 검증): `px = (x − originx) / 0.05`, `py = height − (y − originy) / 0.05`
  - 예) 출발점3-1: ROS (−2.4, −19.905) → 픽셀 (184.0, 189.9) ✔

### 4.3 위치 포인트 (`pose` 테이블, 전체: `reference/db/ros_pose.csv`)

| type | 의미 | 예 |
|---|---|---|
| `charge` | 충전기 | `1_자동 충전기` 1층 (−2.849, −0.280) |
| `zone_start_pose` | 구역 청소 출발점 | `출발점3-1` … 1층 14개 |
| `origin` | 대기/안내 지점 | `안내1층`, `안내5층` (2.592, −5.959) |
| `normal` | 일반 목적지 | `101`~`103`, `201`~`504` |
| `elevator_queue/wait/inside/avoid_obstacle` | 엘리베이터 관련 | 1층, 5층 |

- `init_pose`: 1건 (−2.864, −0.880, qz 0.698 / qw 0.716) = 충전기 앞 "开机区域(부팅 구역)". **현재 초기 위치는 1층 충전기 한 곳만 정의돼 있다**
- `label` 테이블(라벨 id + 층 + 자세)과 `vslamKeyframe`, `keyframe` 테이블이 스키마에 있으나 **모두 0건**. 태그/시각 기반 위치 인식 기능은 스키마상 존재하지만 미사용
- `RobotPos`: 마지막 저장 위치. `floor=5, validFlag=0` 으로 남아 있어 과거 5층으로 전환한 적이 있는 것으로 보임(추정)
- `elevator`: `available_floor=1,2,3,4,5`

### 4.4 위치 상태 값 (로그 기준 해석, 추정 포함)

`/position/status` 의 `status`: `0`=위치 확정, `1`=초기화/인식 중, `2`=위치 상실로 보임. `type=4`+`poseId` 는 초기 위치(부팅 구역) 기준 인식.

## 5. 동작 로그

### 5.1 로그 위치

| 경로 | 내용 |
|---|---|
| `/sdcard/krlog/log_YYYYMMDD/` | **전체 logcat** (2시간 단위 zip, 약 30일치). 앱·SDK 호출이 모두 기록됨 |
| `/sdcard/keenon/maplog/` | 지도 작업 시 logcat, `/sdcard/keenon/crashs/` 크래시 |
| `/sdcard/.000LogFilePad/webLog` | 웹 UI 로그 |
| `/sdcard/ros/home/keenon/log/keenonlog_*.log(.zip)` | **ROS 메인 로그** (약 11분 단위) |
| `/sdcard/ros/home/py_log/ros_clean_log.log`, `ros_clean_core.log` | ROS 청소 파이썬 로그 |
| `/sdcard/ros/home/keenon/schedule/`, `bag/`, `idlebag/` | 스케줄 로그, rosbag |
| `/sdcard/ros/home/websocketlog.log` | ROS 쪽 웹소켓 서버 로그(1분 주기 ping) |
| `/sdcard/ros/config/cleaning_report/`, 앱 `files/clean_record/` | 청소 리포트, 청소 결과 이미지(78건) |

유용한 logcat 태그: `PeanutSDK:SDK--`(CoAP 요청), `PeanutSDK:API--`(응답), `CLEAN:module.common:RosLocationManager`, `TopicManager`, `CLEAN:module.slam:CleanServiceImpl`

### 5.2 실측 시퀀스 ① 위치 재지정 (2026-10-08 17:50, `PositionChooseActivity`)

```
17:50:23.301  POST /clean/relocationPosition {"action":1,"id":0}   → success
17:50:23.307  POST /motor/enable {"enable":0}                      (로봇을 손으로 밀 수 있게 해제)
17:50:24.896  POST /clean/relocationPosition {"action":2,"id":0}
17:50:24.932  POST /motor/enable {"enable":1}
17:50:40.485  /position/status → {"floor":1,"poseId":1,"position":["开机区域"],"status":0,"type":4}
```

### 5.3 실측 시퀀스 ② 지도 푸시 (2026-10-08 17:50~17:53)

```
17:50:47  POST /runtime/download {type:"database", url:"tftp://192.168.64.10:9527/<md5>.zip", mapUuid}
17:50:48  POST /runtime/resourceConfig {...}
17:50:48  POST /runtime/download_action {"action":1}
17:50:48  POST /devices/reboot {"id":1,"name":"robot-arm","isReposition":false}
17:51:26  /position/status → status 2 (위치 없음)
17:51:34  /position/status → {"floor":1,"poseId":4,"status":0,"type":4}
17:51:57  mapPushSuccess
17:52:59  queryLocationStatus status 0 → setLocationSuc=true
```

지도 편집 후 적용은 "DB zip 을 TFTP 로 전송 → ROS 재부팅" 방식이다. 층 전환만 할 때도 이 경로를 타는지는 아직 확인하지 못했다.

발췌 원문: `reference/logs/`

### 5.4 조사 시점 로봇 상태

1층 충전기 위치 (−2.86, −0.88), 정지, 위치 확정(status 0), 청소 앱 비밀번호 화면.

## 6. 원클릭 층 전환 앱에 대한 시사점

### 6.1 두 방식 비교

| | 사전 정의 위치(바닥 스티커) | AprilTag / QR |
|---|---|---|
| 필요 데이터 | 층별 시작점 좌표(x, y, yaw) + `mapId` | 태그 id ↔ 층·자세 표 + 카메라 영상 |
| 하드웨어 | 추가 없음 | **태블릿에 카메라 없음.** ROS 쪽 mx 카메라 영상 접근(ROS 토픽) 또는 USB 카메라 추가 필요 |
| 기존 기능 재사용 | 청소 앱의 `changeFloor` + `relocationPosition` 흐름 그대로 | ROS `label` 테이블이 있으나 미사용, 동작 여부 불명 |
| 난이도 | 낮음 | 높음 |

→ **1단계는 스티커 방식**, AprilTag 는 ROS 카메라 토픽 접근성을 확인한 뒤 2단계로 권장.

### 6.2 스티커 방식 예상 흐름

1. `GET :8089/mapFloorList` 로 층 목록 표시
2. 사용자가 층 선택 → 층 전환 (`changeFloor` 에 해당하는 네이티브 동작 호출)
3. 저장해 둔 시작점 좌표로 위치 지정 (`/clean/relocationPosition` 또는 `/position/relocationPosition`, `/initialpose`)
4. `/position/status` 가 `status:0` 이고 `floor` 가 목표 층인지 확인
5. `/clean/v1/startTask` 로 청소 시작

### 6.3 아직 모르는 것 (다음 단계)

1. **`changeFloor` 네이티브 구현**: 어떤 CoAP 호출로 이어지는지(`/map/v1/loadMap`? DB 재전송+재부팅?). → APK 디컴파일(jadx) 또는 실제 층 전환 중 logcat 캡처로 확인
2. **임의 좌표로 위치 지정하는 API의 파라미터**: `relocationPosition` 의 `id` 의미(0 = 부팅 구역?), 좌표 직접 지정 가능 여부
3. **층별 초기 위치 등록 방법**: 현재 `init_pose` 는 1층 1건뿐. 2~5층 시작점을 지도 앱에서 추가할 수 있는지
4. **2~4층 청소 구역 부재**: 청소 시작 전 구역 생성 필요
5. `:8089` 의 쓰기성 엔드포인트 목록과 파라미터, `/clean/v1/startTask` 페이로드
6. ROS `9090`/`8080`/`80` 포트의 정체, ROS 카메라 토픽(AprilTag 용)
7. 외부 앱에서 CoAP 를 직접 보낼 때 청소 앱의 SDK 세션과 충돌 여부 → 가능하면 청소 앱의 `:8089` 를 경유하는 편이 안전

## 7. 저장소 자료 안내

| 경로 | 내용 |
|---|---|
| `reference/api/` | CoAP 관측 경로, 앱 내 경로 문자열 전체, 컴포넌트 목록, JS 브리지 |
| `reference/db/` | 두 DB 의 스키마와 지도/포인트/구역 CSV |
| `reference/config/` | 스케줄·센서 설정, getprop |
| `reference/logs/` | 위치 재지정·지도 푸시·부팅 핸드셰이크 로그 발췌 |
| `local/` (git 제외) | APK 2종, 원본 DB, 원본 로그 — 용량·저작권·민감정보 때문에 업로드하지 않음 |
