# 읽기 전용 조회 실측 (3차)

- 실측일: 2026-10-14
- 방법: `kr.c40.floor` v0.1.0 (읽기 전용 조회 앱)을 로봇 태블릿에 설치해 실행. GET 요청 6개만 전송. 원문: `reference/logs/20261014_readonly_probe.txt`
- 로봇 상태: 1층 부팅 구역 근처 정지, 배터리 99%, 작업 없음

## 결과 [실측]

| 요청 | 결과 |
|---|---|
| `GET localhost:8089/mapFloorList` | 200, 1~5층 목록. 01 문서와 일치 |
| `GET localhost:8089/getCurrentLoadMapInfo` | 200, 214,007자 |
| CoAP `GET /position/status` | 2.05, `{"position":["开机区域"],"status":0}` |
| CoAP `GET /position/robotPosInfo` | 2.05, `floor 1`, x -2.51, y -0.77, rotation 1.91 |
| CoAP `GET /clean/status` | 2.05, `cleanStatus 65535`, 작업 없음 |
| CoAP `GET /charge/status` | 2.05, `power 99`, `event 254` |

## 확인된 것

- 외부 앱이 `192.168.64.20:5683` 으로 CoAP GET 을 직접 보내도 청소 앱과 충돌하지 않았다. (쓰기 요청은 미검증)
- 앱은 `localhost:8089` 에 접근할 수 있다.
- 응답 시간은 CoAP 6~163ms, HTTP 149~232ms.

## 02 문서와 다른 점

- 정지 상태의 `/position/status` 에는 `floor`, `poseId`, `type` 이 **없다** (`position`, `status` 만). 02 문서 2.3 의 성공 판정(`floor`, `type == 11`, `poseId`)은 위치 지정을 실제로 한 뒤에 필드가 생기는지 확인해야 한다. 층은 `/position/robotPosInfo` 의 `floor` 로도 읽을 수 있다.
