# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

The repo holds research documents (written in Korean) and data extracted from a Keenon C40 cleaning robot, plus an early-stage Android app (`app/`, Kotlin, minSdk/targetSdk 29; Californium for CoAP, OkHttp for HTTP) for a "one-click floor change". See `README.md` for the goal and the progress checklist.

Three Gradle modules:

- `core` (plain Kotlin/JVM, no Android dependency) — `RobotGateway` (GET only) and `WriteGateway` (POST) transports; `RealGateway` (OkHttp + Californium); `GatewayRobotReader` parses responses into typed state; `RobotCommands` (`LiveCommands` sends, `DryRunCommands` only records); `FloorSwitchSequencer` runs precheck → confirm → relocate → verify → map sync. It never starts cleaning.
- `mock-robot` (JVM) — a fake robot: HTTP `:8089` plus CoAP/UDP `:5683`, with failure modes (`REJECT`, `NEVER_LOCATES`, `WRONG_FLOOR`, `MAP_STUCK`, `CLEANING`, `LEGACY_FIELDS`). Response formats copy the 2026-10-14 real probes. Its landmarks 101–103 are fictional, because the real robot has no `landmark` points yet.
- `app` (Android) — `MainActivity` is the read-only probe (GET only, logs to a file). `SwitchActivity` is the floor-switch screen. It is **dry-run by default**: writes go out only when the user ticks "실제 전송 허용" and confirms the dialog. "청소 앱 열기" only opens the clean app; automatic cleaning start is not implemented.

Safety rules in the code: any failed step stops the sequence; unreadable state counts as a failure; verification ignores the first 3 s after the request (stale status); the clean app must be idle (`cleanStatus` 65535 only; other values are unmeasured and block).

Still unmeasured on the real robot (guarded by tests against the mock only): that `action 3` works, what `/position/status` returns after it (the idle response has no `floor`/`type`/`poseId`), and the `charge/status` `event` value that means "on the charger" (`SequencerConfig.chargingEvents` is empty). `/task/status` is 404 on the real robot.

## Build and run (Windows)

Gradle 9.8.1 (wrapper), AGP 9.4.1, `compileSdk` 36. The system `JAVA_HOME` is JDK 11, which is too old, so point Gradle at Android Studio's bundled JDK for each shell:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties` (git-ignored) must contain `sdk.dir=C\:\\Users\\RTX\\AppData\\Local\\Android\\Sdk`.
- Emulator AVD `c40_api29` (Android 10, x86_64). Start it with `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd c40_api29`. The app detects the emulator and targets `10.0.2.2` (the host PC) instead of the robot's `localhost:8089` and `192.168.64.20:5683`.
- Two `adb` binaries exist (the SDK one and `D:\peanut-sdk-v\tools\platform-tools`). Always pass `-s <device>` (`emulator-5554` or the robot's `ip:5555`).
- Probe logs are saved on the device in `/sdcard/Android/data/kr.c40.floor/files/`; fetch them with `adb pull`.

```powershell
.\gradlew.bat :core:test                  # sequencer unit tests (virtual clock, no network)
.\gradlew.bat :mock-robot:test            # end-to-end tests: RealGateway over real HTTP + CoAP against the mock
.\gradlew.bat :mock-robot:test --tests "kr.c40.floor.mock.EndToEndTest.1층에서 2층으로 전환한다"   # one test
.\gradlew.bat :mock-robot:run --args="--mode normal"    # fake robot on :8089 / UDP :5683 for the emulator
# switch mode while running: http://localhost:8089/mock/mode?name=REJECT   state: /mock/state
```

- Test results are in `*/build/test-results/test/*.xml`. Check the XML, because Gradle's console output can hide that tests ran.
- The robot is reached over Wi-Fi adb (`192.168.0.67:5555`, see memory). Install with `D:\peanut-sdk-v\tools\platform-tools\adb.exe -s 192.168.0.67:5555 install -r app\build\outputs\apk\debug\app-debug.apk`. The onboard ROS Ubuntu (`192.168.64.20`, ssh open) is not needed by the app.

## Reading order and precedence

- `docs/01_robot_investigation.md` — first survey (read-only `adb` investigation): system info, apps, control-board communication, map/pose data, logs.
- `docs/02_clean_app_internals.md` — decompile analysis of `com.keenon.peanut.clean` v3.7.6.
- `docs/03_app_design.md` — app design and development phases.

Some floor-change assumptions in 01 were corrected by 02. **Trust 02 and 03 over 01.** In particular, 01's "`changeFloor` switches the ROS map" and its "store start coordinates per floor" plan are superseded.

## Robot architecture (from 01)

- Two boards. An Android 10 tablet (RK3288, userdebug build, no camera; internal IP `192.168.64.10`) runs the apps. A ROS Indigo board on Ubuntu 14.04 (`192.168.64.20`) does SLAM (Cartographer) and navigation. The tablet also has Wi-Fi (`adb` over `192.168.1.155:5555` was used for the survey).
- The apps talk to ROS through the Keenon PeanutSDK over **CoAP/UDP 5683** with JSON payloads. Requests carry `path`, `type` (GET/POST/POST_LARGE/OBSERVER) and `msgType` (CON/NON). Responses look like `{"code":0,"data":{...},"msg":"success","status":0}`. Map DBs go over TFTP, files over SMB, and the lower board over serial.
- The clean app (`com.keenon.peanut.clean`) opens an **unauthenticated REST server on `:8089`** and a web UI on `:8090`. The web UI calls native code through `WebViewJavascriptBridge`. The 8089 server is the preferred integration point, because sending CoAP directly may conflict with the clean app's SDK session (unverified).
- The map-management app (`com.keenon.peanut.peanutservice`) shares map state with the clean app through `/sdcard/peanut/inter_app/app_share_params.properties`.
- Applying an edited map means sending a DB zip over TFTP and then rebooting ROS. Whether a plain floor change takes that path was open in 01, and 02 later showed ROS does not switch maps at all.
- Two separate map databases exist. `kc01.db` (clean app side) holds `ROBOT_MAP`, `MAP_POINTS` and `MAP_AREA`. `peanut.db` (ROS side) holds `pose`, `init_pose`, `zone`, `dynamic_map` and more.

## Key facts from 02 and 03

- ROS does not "switch" maps. Its DB holds the maps for floors 1–5, and the robot's floor is determined by the result of relocation. The clean app follows the floor ROS reports (`/position/robotPosInfo`) and calls `changeLocalMap` itself.
- The one-click flow is essentially one CoAP request: `POST coap://192.168.64.20:5683/clean/relocationPosition {"action":3,"id":<landmark id>}`. The new app stores only a floor → landmark id mapping, and coordinates stay in ROS. Landmarks are registered once per floor in the map-management app.
- After relocation, the app checks `/position/status` (`status 0`, target floor, expected `poseId`) and `GET :8089/getCurrentLoadMapInfo` before starting a clean. It never starts cleaning when recognition fails.
- Doc claims are tagged **[코드]** (seen in decompiled code), **[실측]** (confirmed on the robot) and **[미검증]** (not yet run). `action 3` is still unmeasured. Keep these tags when you add facts, and do not promote a claim to [실측] without a real robot run.

## Data and coordinate gotchas

- The current robot state is 5 floors. Floors 2, 3 and 4 share one map image (same md5). Only floors 1 and 5 have cover zones, and `init_pose` has only one entry (the floor-1 charger area).
- Two coordinate systems are in use. ROS uses metres with quaternion orientation, and `kc01_map_points.COORDS_INFO` uses map-image pixels. Conversion (verified on floor 1): `px = (x − originx) / 0.05`, `py = height − (y − originy) / 0.05`. The resolution is 0.05 m/px and the origin is (−11.6, −29.4).
- The ROS board's clock is 1 hour behind the tablet, so allow for it when correlating ROS logs with logcat.
- Names inside DBs and logs are partly Chinese (for example `开机区域`, "boot area"). The exported CSV files use Korean names.
- Device identifiers (MAC, serial) were masked in the committed data. Keep them masked.

## Repository layout

- `reference/api/` — `coap_api_catalog.tsv` (path, method, group, SDK class, param keys), `http_8089_routes.tsv` (path, method, impl class, handler, args), `coap_paths_observed.txt` (paths seen in real logs), `clean_app_path_strings.txt` (all path strings in the app), `clean_app_components.txt`, `web_jsbridge_handlers.txt` and `WebViewJavascriptBridge.js`.
- `reference/db/` — SQL schemas (`kc01_schema.sql`, `ros_peanut_schema.sql`) and CSV exports (`kc01_*`, `ros_*`) of maps, points, zones and poses.
- `reference/config/` — `android_getprop.txt`, `peanut_properties.txt`, `ros_sensors.txt` and the schedule JSON files.
- `reference/logs/` — two excerpts: a relocation and map push on 2026-10-08, and a boot SDK handshake on 2026-10-12. Log timestamps use the tablet clock; useful logcat tags are `PeanutSDK:SDK--` (requests) and `PeanutSDK:API--` (responses).
- `local/` — APKs, decompiled sources, raw DBs and raw logs. It is git-ignored and local only; it may not exist in a fresh clone.

## Conventions

- Never commit APKs, `*.db` files, keys or decompiled Keenon code (see `.gitignore`). Put only the interface facts the app needs into `docs/`, and put the data extracted from the robot into `reference/`.
- The robot survey was read-only: it didn't change settings, maps or robot behaviour. Don't send write-type requests to a real robot (relocation, map load, task start) unless the user asks for it.
- Write new docs in Korean and number them after the existing ones (`04_...`).
