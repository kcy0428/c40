# Keenon C40 청소 로봇 분석

- 분석일: 2026-10-15
- 방법: Wi-Fi ADB(`192.168.0.67:5555`) + 루트 셸(`su 0`)로 로봇 안의 Android 태블릿을 읽기 전용으로 조사
- 원본 출력: 조사 스크립트 결과에서 정리 (아래 값은 모두 실제로 확인한 것)

---

## 1. 전체 구조

C40은 **보드가 두 개**입니다. 이번에 ADB로 붙은 쪽은 **UI/앱 담당 Android 태블릿**이고, 주행·SLAM은 **별도의 ROS 메인보드**가 맡습니다.

```
            ┌──────────── 사내/매장 Wi-Fi (13205-2.4GHz, 192.168.0.0/24)
            │
   [PC] ── wlan0 192.168.0.67 ─┐
                               │  Android 10 태블릿 (RK3288, Keenon UI)
                               │   - com.keenon.peanut.clean (청소 앱)
                               │   - :8089, :8090 리슨
   내부 유선망 192.168.64.0/24  │
            eth0 192.168.64.10 ┘
                 │
                 │  SMB(445) : /home, /config 공유를 태블릿이 마운트
                 │  TCP 9013 : 태블릿 → ROS 보드 연결
                 ▼
   ROS 메인보드 192.168.64.20  (Ubuntu 14.04 Trusty / ROS Indigo, armhf)
     - slam-ros v2.10.1-c40alpha7
     - 라이다, 카메라, 섀시, LoRa 등 하드웨어 직접 제어
```

클라우드: 태블릿이 `18.183.159.x`(AWS 도쿄 리전)와 MQTT(1883, 8883/TLS)와 HTTPS(443)로 연결되어 있습니다. 원격 지도 폴더 이름은 `remote.robotkeenon.com`입니다.

---

## 2. Android 태블릿 (ADB 대상)

| 항목 | 값 |
|---|---|
| 제조사 / 모델 | keenon / `Delivery Robot` (`rk3288_keenon_tablet`) |
| 기기 식별 속성 | `ro.keenon.machine = C55` (C40 ROS 패키지와 다름, 공통 태블릿을 쓰는 것으로 추정) |
| 시리얼 | `<SERIAL>` |
| SoC | Rockchip RK3288, ARMv7 4코어, Mali GPU (32비트 전용, `armeabi-v7a`) |
| RAM | 2 GB (사용 가능 약 0.95 GB) |
| 저장소 | eMMC, `/data` 10 GB 중 3.6 GB 사용 |
| OS | Android 10 (SDK 29), 보안 패치 2021-03-05 |
| 빌드 | `rk3288_EDP11.6_v10.3.1`, 2025-04-10, **userdebug** |
| 커널 | Linux 4.19.193 |
| 화면 | 1920×1080, 240 dpi, 터치 `ilitek_ts` |
| 로캘 / 시간대 | ko-KR / Asia/Seoul |
| 보안 상태 | SELinux **permissive**, 부트로더 잠금 해제(`flash.locked=0`, verifiedboot `orange`), `/system/xbin/su` 있음 |

### 2.1 USB 장치 (태블릿 내부 버스)

| VID:PID | 장치 | 용도 |
|---|---|---|
| `0bda:8179` | Realtek RTL8188EU | Wi-Fi (wlan0) |
| `2c7c:0125` | Quectel EG25-GC | LTE 모뎀 → `/dev/ttyUSB0~3` |
| `1a40:0101` | USB 2.0 허브 | – |

시리얼 포트 `/dev/ttyS1~S4`가 있고 권한은 모두 `rw-rw-rw-`입니다. 입력 장치는 `adc-keys`, `gpio-keys`, `ilitek_ts`입니다.

> 참고: PC에서는 로봇을 Rockchip `VID_2207`(이름 "Delivery Robot")로 인식한 기록이 있지만, 이번에는 USB로 잡히지 않았습니다. 지금은 Wi-Fi ADB만 사용합니다.

### 2.2 네트워크

| 인터페이스 | IP | 비고 |
|---|---|---|
| wlan0 | 192.168.0.67/24 | SSID `13205-2.4GHz`, RSSI −42 dBm, 150 Mbps. 저장된 다른 SSID: `keenon_kr` |
| eth0 | 192.168.64.10/24 | ROS 보드와 연결된 내부망 |
| DNS | 202.30.55.11 / 202.30.55.55 | |

리슨 포트:

| 포트 | 소유자 | 비고 |
|---|---|---|
| 5555 | adbd (shell) | Wi-Fi ADB, 부팅 시 자동으로 켜짐 (아래 6장) |
| 8089, 8090 | uid 10096 = `com.keenon.peanut.clean` | 청소 앱의 로컬 서버 |

### 2.3 설치 앱

**Keenon 앱**

| 패키지 | 버전 | 설명 |
|---|---|---|
| `com.keenon.peanut.clean` | v3.7.6 (24) | **청소 메인 앱**. 메모리를 가장 많이 씀(약 280 MB), 8089/8090 리슨 |
| `com.keenon.peanut.peanutservice` | v4.9.10 (330) | 백그라운드 서비스 |
| `com.keenon.android_peanut_mfg` | v3.6.1 (180) | 공장/생산 테스트 도구 |
| `com.keenon.systemservice` | 시스템 앱 | uid 1000(system) |
| `com.keenon.remote_control_oray` | 시스템 앱 | Oray 원격 제어(향일규) |
| `com.keenon.krlog` | 시스템 앱 | 로그 수집 → `/sdcard/krlog/log_YYYYMMDD` |
| `com.keenon.usb0ethernet.overlay` | 오버레이 | USB 이더넷 설정 |
| `android.rockchip.update.service` | 시스템 앱 | OTA (`www.rockchip.com:2300`) |

**기타 앱**

| 패키지 | 버전 | 비고 |
|---|---|---|
| `kr.c40.floor` | 0.2.0 (2) | **2026-10-14 설치**. 권한은 INTERNET만 요청. 직접 만든 앱으로 보임 |
| `com.termux` | 0.118.3 | 터미널 |
| `lysesoft.andsmb` | 4.2 | SMB 클라이언트 |
| `ru.zdevs.zarchiver.pro` | 0.9.3 | 압축 해제 |
| `com.google.android.inputmethod.latin` | 11.1 | Gboard |
| `com.ilitek.touchutility` | 2.0.5 | 터치 패널 도구 |

분석 시점에 화면 맨 앞에 떠 있던 앱은 기본 런처(`com.android.launcher3`)였습니다. 청소 앱은 백그라운드에서 돌고 있었습니다.

### 2.4 전원

Android 배터리 서비스는 `present: false, level: 0`으로 나옵니다. 태블릿은 로봇 전원을 받아 동작하고, **실제 배터리 정보는 ROS 쪽(섀시)이 관리**합니다.

---

## 3. ROS 메인보드 (192.168.64.20)

태블릿이 SMB(vers=1.0, `username=root`)로 ROS 보드의 두 디렉터리를 마운트합니다.

| ROS 보드 공유 | 태블릿 경로 |
|---|---|
| `//192.168.64.20/home` | `/sdcard/ros/home` |
| `//192.168.64.20/config` | `/sdcard/ros/config` |

용량은 57 GB 중 46 GB 사용(84%)입니다.

### 3.1 소프트웨어

- 패키지: `slam-ros-v2.10.1-c40alpha7-0-ga2e1a44` (2026-01-27 설치, `ros-indigo-*_trusty_armhf.deb` 형식)
- 자동 실행: `/work/scripts/guard.py` (`bin/keenon`, `script/app`)
- 서비스 이름: `keenonrobot` (`sudo service keenonrobot restart`)
- 진단 도구: `~/dev_diag` (`dev_diag -t chassis`, `dev_diag -t lora` 등)

주요 ROS 패키지를 기능별로 묶으면:

| 분류 | 패키지 |
|---|---|
| SLAM / 위치추정 | cartographer, gmapping, keenon-vslam, peanut-localization, location-fusion, robot-location, label-fusion |
| 주행 계획 | move-base, global-planner-user, navfn, teb-local-planner, teb-clean-local-planner, kplanner, tangent-planner, pnc-core, nav2-rotation-shim-controller |
| 청소 | cleanning-planner, cover-planner, segmentation, virtual-wall-generator, topo-map-generator |
| 센서 드라이버 | free-lidar-driver, ldlidar, ydlidar-ros-driver, sdkeli-ls-udp, micro-laser, astra-camera, berxel-camera, sy-tof-dev |
| 하드웨어 | chassis, lora-driver, elevator-task |
| 작업 / 스케줄 | task-manager, task-dispatcher, schedule-ros, multi-goals-planner |
| 기타 | web-backend, database, robot-monitor, safe-decision, anomalies-detection, keenonrecorder |

### 3.2 센서 설정 (`/sdcard/ros/config/sensors`)

| 파일 | 값 |
|---|---|
| `lidar_type.txt` | `free` (free-lidar-driver) |
| `camera_type.txt` | `mx` |
| `mx_version.txt` | `11.1.35` |
| 기타 | 좌/우 하단 레이저 캘리브레이션(`link_low_laser_*.yaml`), `micro_laser_bind.yaml`, `auto_calib.json` |

### 3.3 데이터 위치

| 경로 (`/sdcard/ros/...`) | 내용 |
|---|---|
| `config/database/peanut.db` | 메인 DB (1.3 MB) |
| `config/database/statistics.db` | 통계 DB (153 MB) |
| `config/database/pbstream/` | Cartographer 지도 |
| `config/schedule/topo_path.db` | 위상 경로 지도 |
| `config/schedule/schedule.config.json` | 회피/스케줄 설정 (`path_avoid_enable: true`, `schedule_distance: 6`) |
| `config/cleaning_report/` | 청소 리포트 |
| `config/remotemap/` | 클라우드 지도 동기화 |
| `home/keenon/{log,bag,idlebag,image,core}` | ROS 로그, rosbag, 이미지, 코어 덤프 |
| `home/websocketlog.log` | 웹소켓 로그 (약 20 MB, 계속 갱신됨) |

---

## 4. 태블릿 쪽 데이터 (`/sdcard`)

| 경로 | 내용 |
|---|---|
| `/sdcard/peanut/` | 앱 리소스 (domain, language, media, voice, scene, params 등) |
| `/sdcard/keenon/` | crashs, db, maplog |
| `/sdcard/krlog/` | 날짜별 로그 |
| `/sdcard/MapSources/`, `/sdcard/mapImage/` | 지도 이미지/영상 |
| `/sdcard/ui.xml` | UI 설정 |

---

## 5. 보안 관점 메모

- userdebug 빌드에 루트(`su`)가 있고 SELinux가 permissive라서, ADB로 접속하면 바로 루트 권한을 얻을 수 있습니다.
- 이제 Wi-Fi ADB가 **인증 없이** 5555 포트로 항상 열려 있습니다. 같은 Wi-Fi에 있는 사람은 누구나 접속할 수 있다는 뜻입니다.
- ROS 보드와의 SMB는 SMBv1에 root 계정을 씁니다(내부 유선망 한정).
- 보안 패치가 2021-03에 머물러 있습니다.

신뢰할 수 없는 네트워크에 붙일 때는 Wi-Fi ADB를 꺼 두세요(아래 6장).

---

## 6. Wi-Fi ADB 설정 기록

| 항목 | 내용 |
|---|---|
| 접속 | `adb connect 192.168.0.67:5555` |
| 영구 설정 | `su 0 setprop persist.internet_adb_enable 1` |
| 동작 원리 | `/vendor/etc/init/hw/init.rk30board.rc`가 이 값이 1이면 `service.adb.tcp.port=5555`로 설정하고, 0이면 포트를 0으로 만들어 끕니다 |
| 끄기 | `adb shell su 0 setprop persist.internet_adb_enable 0` |
| 추가로 저장된 값 | `persist.adb.tcp.port=5678` (Rockchip 스크립트가 덮어쓰므로 실제로는 쓰이지 않음) |
| 미확인 | 재부팅 후 유지되는지는 아직 테스트하지 않음 |

로봇 IP가 바뀔 수 있으니 공유기에서 MAC `<MAC>`에 DHCP 예약을 걸어 두는 것을 권장합니다.

---

## 7. 인터넷 조사 결과

### 7.1 공식 제품 사양 (KLEENBOT C40)

출처: [Keenon 공식 C40 페이지](https://www.keenon.com/en/product/C40)

| 항목 | 값 |
|---|---|
| 크기 | 578×500×690 mm (스퀴지 포함 616×550×690 mm) |
| 무게 | 70 kg (배터리 포함) |
| 최소 통과 폭 | 650 mm |
| 배터리 | DC 25.6 V, 50 Ah. 완충 2시간, 교체 5초 |
| 작동 시간 | 스크러빙 최대 5시간, 스위핑 최대 12시간 |
| 청소 폭 | 스위핑 560 mm(사이드 브러시 2개), 흡입·스크러빙 400 mm |
| 청소 능력 | 최대 1,100 m²/h, 흡입력 최대 23,500 Pa |
| 탱크 | 청수 16 L, 오수 11 L(일부 자료는 14 L), 먼지봉투 8 L |
| 센서 | 라이다 1, 스테레오 비전(듀얼), 라인 레이저 4, 초음파 3, 총 10개 |
| 주행 | VSLAM 경로 계획, 카펫 자동 감지, 멀티 로봇 스케줄링, IoT 엘리베이터·자동문 통과 |
| 화면 | 11.6인치 터치스크린 |
| 국내 출시 | 2025-06 클린코리아쇼(코엑스)에서 공식 출시 ([theteams 보도](https://www.theteams.kr/news/detail/3557)) |

**로봇에서 확인한 값과 맞춰 보기**

| 공식 사양 | 로봇에서 확인한 것 |
|---|---|
| 11.6인치 화면 | 빌드명 `rk3288_EDP11.6`, 1920×1080 |
| 라이다 1개 | `lidar_type.txt = free` (free-lidar-driver) |
| 스테레오 비전 | `camera_type.txt = mx`, keenon-vslam 패키지 |
| 라인 레이저 4개 | micro-laser 패키지, 좌/우 `link_low_laser_*.yaml` |
| 엘리베이터 연동 | elevator-task, keenon-elevator-msgs 패키지 |
| 멀티 로봇 스케줄링 | schedule-ros, `schedule.config.json` |

### 7.2 Keenon Cloud Open Platform API (공식, 클라우드 경유)

출처: GitHub [Allbotix/Keenon-App](https://github.com/Allbotix/Keenon-App)에 올라온 공식 문서 `Open Platform Document V2.2.0.pdf`, `Keenon Open Platform API Docking Apply Instruction-V3.pdf`. 사용 예제는 [Gistex-Robotic/keenonAPI](https://github.com/Gistex-Robotic/keenonAPI)(Python), [janghanie1/keenonServer](https://github.com/janghanie1/keenonServer)(Node.js, 한국어)에 있습니다.

- **사용 신청 절차**: Keenon Cloud 계정 → 고객 등록 → Keenon에 이메일 신청(회사명, 서버, 매장 ID, 로봇 모델, 비밀유지 서약서) → 승인되면 `clientId`, `clientSecret` 발급. 매장 바인딩과 **공인 IP 화이트리스트** 설정이 필요합니다.
- **도메인**: 중국 `console.peanut.keenonrobot.com` / EU `es.robotkeenon.com` / 일본(아시아) `cloud.robotkeenon.com`. 이 로봇은 `remote.robotkeenon.com`과 AWS 도쿄 리전으로 연결되어 있어 **일본 서버 계열**로 보입니다(추정).
- **인증**: `POST /api/open/oauth/token` (client_credentials 방식) → `Authorization: bearer <token>`
- **호출 제한**: 너무 자주 호출하면 IP나 client_id가 차단됩니다. 상태 조회는 약 3초 간격을 권장하고, 가능하면 웹훅 콜백을 쓰라고 안내합니다.

**청소 로봇 API (3.8장)**

| 기능 | 메서드 · 경로 |
|---|---|
| 스케줄 목록 | GET `/api/open/custom/clean/strategy/list` |
| 청소 기록 | GET `/api/open/custom/clean/log/list` |
| 로봇 상태 | GET `/api/open/custom/clean/robot/status?robotSn=` |
| 청소 구역 목록 | GET `/api/open/custom/clean/robot/area/list` |
| 복귀(충전) | POST `/api/open/custom/clean/robot/recharge/task` |
| 작업 종료 | POST `/api/open/custom/clean/robot/finish/task` |
| 작업 일시정지 | POST `/api/open/custom/clean/robot/pause/task` |
| 청소 모드 목록 | GET `/api/open/custom/clean/robot/strategy/clean/model` |
| 복귀 지점 목록 | GET `/api/open/custom/clean/robot/strategy/back/point` |
| 임시 작업 | POST `/api/open/custom/clean/robot/strategy/temporary/task` |
| 스케줄 생성/수정/삭제 | POST/PUT/… `/api/open/custom/clean/robot/strategy/task` |
| 지도·위치·배터리 | `/api/open/custom/robot/map/position`, `/robot/location`, `/robot/battery/level` |

**청소 로봇 상태값**

| 구분 | 값 |
|---|---|
| mainState | 1 대기, 2 운영, 3 작업, 4 충전, −1 오프라인 |
| subState | 11 대기, 21 운영 중, 30~34 충전(31 도킹 맞추는 중, 32 충전 중, 33 충전대 위, 34 유선 충전), 40 작업, 41 주행, 42 청소, 43 자가 세척, 44 복귀, 45 청소 일시정지, 46 복귀 일시정지, 47 수동(손으로 밀기) |
| hardwareState | 청수 탱크(−1 없음 / 0 빔 / 1 적음 / 2 보통 / 3 가득), 오수 탱크(−1 / 0 / 1 / 2 가득), 먼지봉투 장착 여부, 롤러 브러시(0 올림 / 1 내림), 손잡이 |
| globalState | rosConnect, locationSuc, scram(비상정지), faulting, upgrading, scheduling |

> 이 API를 쓰려면 **Keenon 승인과 클라우드 계정이 필요합니다.** 로컬망만으로는 사용할 수 없습니다.

### 7.3 Peanut SDK (안드로이드 앱 개발용, 로컬)

출처: 같은 저장소의 `Peanut SDKV1.3.0 (English).docx`, `libs/peanut-sdk-release.aar`

태블릿 위에서 돌아가는 Android 앱이 로봇 하드웨어를 직접 제어할 수 있게 해 주는 라이브러리입니다. **배송 로봇(T 시리즈) 기준 문서**라서 C40 청소 기능(브러시, 탱크 등)은 다루지 않습니다. 다만 통신 구조는 이 로봇과 그대로 일치합니다.

| SDK 설정 | 값 | 이 로봇에서 확인한 것 |
|---|---|---|
| 통신 방식 | `COM`(시리얼), `COM_COAP`, `COAP`, `HTTP` | ROS 패키지에 `libcoap` 포함 |
| CoAP 포트 | `5683` | – |
| `REMOTE_LINK_PROXY` | **`192.168.64.20`** (라이다 측위 모델용) | ROS 보드 IP와 동일 |
| `LOCAL_LINK_PROXY` | `127.0.0.1` (스테레오 비전 측위 모델용) | – |
| 메인보드 시리얼 | `COM1 = /dev/ttyS1` | `/dev/ttyS1` 있음 |
| 램프·도어 보드 시리얼 | `COM2 = /dev/ttyS2` | `/dev/ttyS2` 있음 |
| 인증 | `appId` / `secret` 오프라인 인증 (Keenon에서 발급) | – |

SDK 주요 기능: 런타임 정보(작업 모드, 배터리, 비상정지, 모터), 내비게이션(목표 지점 이동, 일시정지/재개, 수동 이동, 속도 설정), 충전(자동/수동/정지), 모터 잠금, 재부팅, 설정 읽기/쓰기, 지도 가져오기/내보내기, 토픽 구독(`BATTERY_STATUS`, `NAVIGATION_STATUS`, `RUNTIME_HEARTBEAT`, `SENSOR_COLLISION`, `ELEVATOR_STATUS` 등).

SDK 문서의 주의사항: 기본 Keenon 앱과 SDK 앱이 **동시에** 로봇과 통신하면 오류가 납니다. 개발할 때는 기본 앱을 강제 종료하라고 안내합니다.

> 확인 결과: 분석 시점에 태블릿에 열린 UDP 5683 소켓은 없었습니다. 청소 앱은 TCP 9013으로 ROS 보드와 통신하고 있어서, C40 청소 앱은 CoAP가 아닌 다른 프로토콜을 쓰는 것으로 보입니다(추정).

### 7.4 참고할 만한 공개 프로젝트

| 저장소 | 내용 |
|---|---|
| [Allbotix/Keenon-App](https://github.com/Allbotix/Keenon-App) | 공식 SDK(.aar), 샘플 APK, Open Platform 문서 |
| [Gistex-Robotic/keenonAPI](https://github.com/Gistex-Robotic/keenonAPI) | 클라우드 API Python 예제 (토큰, 매장, 로봇 상태, 위치, 지도) |
| [janghanie1/keenonServer](https://github.com/janghanie1/keenonServer) | 클라우드 API 중계 서버 (Node.js, 한국인 개발자) |
| [JaySorawit/KeenonRPA](https://github.com/JaySorawit/KeenonRPA) | 접근성 서비스로 Keenon 앱 UI를 자동 조작 (T6 대상) + 소켓 명령 |
| [AntonyJuza/depth-camera-ws](https://github.com/AntonyJuza/depth-camera-ws) | Keenon T5의 IR 스테레오 뎁스 카메라를 ZeroMQ(`tcp://10.11.11.1:20000`)로 받아 ROS 2로 발행. 설정 파일 이름이 `mx_camera_user_cfg.yml`이라 **C40의 `camera_type = mx`와 같은 계열 카메라일 가능성**이 있음 |

### 7.5 찾지 못한 것

- Keenon 관련 CVE나 보안 권고: 없음
- C40 공식 사용자 매뉴얼이나 서비스 매뉴얼 PDF: 공개된 것 없음
- ROS 보드 접속 정보(SSH 계정 등)나 9013 포트 프로토콜에 대한 공개 자료: 없음

---

## 8. 다음 단계 후보

1. **ROS 보드 직접 접근**: 태블릿을 거쳐 192.168.64.20에 접속(SSH 등). 이번에는 포트 스캔 도구가 동작하지 않아 445와 9013 외에 열린 포트는 확인하지 못했습니다.
2. **청소 앱 프로토콜 분석**: 태블릿과 ROS 보드 사이의 TCP 9013 트래픽, 청소 앱의 8089/8090 로컬 API.
3. **클라우드 API 사용**: 로봇 판매처를 통해 Open Platform 사용 신청(7.2). 가장 공식적인 제어 방법입니다.
4. **`kr.c40.floor` 앱 연동**: 위 방법 중 하나를 사용하는 방향.
5. **DB 구조 확인**: `peanut.db`, `topo_path.db`를 PC로 복사해 SQLite 스키마 확인.

> 참고: 태블릿의 `/data/local/tmp`에는 이전에 작업한 흔적(usb0 네트워크 테스트 스크립트, `scrcpy-server.jar`, tcpdump 결과 등)이 남아 있습니다.
