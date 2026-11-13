# 머문자리 (Meomunjari)

차박러가 직접 만드는 차박 지도. 차가 밤새 기록한 실측 데이터로 스팟의 신뢰를 만드는 Pleos Connect 앱입니다.
1단계 범위는 서버 없이 로컬 JSON 목 데이터로 시연 시나리오 8단계가 끝까지 동작하는 것입니다.

## 빌드 플레이버

| 플레이버 | 차량·위치·내비 | 쓰임새 |
| --- | --- | --- |
| `mock` | 앱 안 시뮬레이터 + 우측 하단 **가상 차량 콘솔** | 일반 에뮬레이터·폰. Pleos SDK 없이 빌드됨 |
| `pleos` | Vehicle 2.3.2 · FusedLocation 1.1.3 · NaviHelper 3.0.5 + AAOS 기어/속도 | Pleos Connect 에뮬레이터 + Virtual Console |

Android Studio 좌측 **Build Variants**에서 `mockDebug` / `pleosDebug`를 고릅니다.

## 처음 빌드할 때 확인할 것

Android SDK 및 Google Maven·Pleos 저장소 접근이 필요합니다. 내비 경유 요청은 NaviHelper 3.0.5의 타입과 이벤트를 사용합니다.

## 검증과 문서 버전

`./gradlew :app:testMockDebugUnitTest :app:assembleMockDebug :app:assemblePleosDebug`로 단위 테스트와 두 APK를 검증합니다. 실제 차량의 경유지 수락 여부는 Pleos 환경에서 확인해야 합니다.

[기획 v1](docs/application-v1.md) · [기획 v2](docs/application-v2.md) · [문장별 변경표](docs/changes-v1-v2.md) · [에너지 계산과 시장 수치 근거](docs/energy-and-evidence-v2.md) · [기상 시각 v2.2](docs/wake-time-v2.2.md) · [충전 화면 v2.3](docs/charging-screen-v2.3.md) · [출발 전 경로 비교 v2.4](docs/departure-comparison-v2.4.md) · [시간대별 목업 편집 v2.5](docs/hourly-weather-v2.5.md)

신청서 수정본은 [v3 기대효과](docs/application-v3.md), [v4 차량에서 해결할 문제](docs/application-v4.md), [v5 에너지 예측](docs/application-v5.md), [v6 차종별 공조 전력 계산 계획](docs/application-v6.md) 순서로 확인할 수 있습니다. v2.5 목업의 [기상 시각 종료 리포트](docs/images/wake-v2.5-mock.png)는 8시간 체류 뒤 예측과 목업 차량 잔량을 비교한 화면입니다.

## 시연 시나리오 (mock 플레이버)

1. **테마 검색** → `#별보기`, `#반려견동반` → '내 차가 들어갈 수 있는 곳만' 켜짐 → 미시령 옛길(소형 SUV까지)이 빠짐
2. **인제 방태산 계곡 쉼터** → 실측 리포트 "11월 기준 새벽 실내 최저 9°C · 공조 8시간 사용 시 배터리 14% 소모 · 기록 12회 중 비 온 날 3회", "지금 2대 차박 중"
3. 11월 기준 속초 시내에서 가상 콘솔 배터리 62% → 아침 잔량 "충분해요" / 25% → "경로상 충전소에서 N분 충전"
4. **충전소를 경유해 차박지로** 또는 **여기로 바로 갈래요** → 차량 내비 경로 설정
5. 콘솔 **목적지까지 자동 주행** → 경유 시 충전소에서 **모의 충전 시작** → **차박지로 출발** → 차박지 도착 후 기어 **P** → 3초 뒤 도착 인증
6. **이 스팟 고수의 세팅**(별헌터) → 차박 모드 시작 → 공조·시트·창문·조명 적용 결과
7. 차박 중 화면에서 **시연 가속** 켜기 → 콘솔로 실내 온도·와이퍼 바꾸기 → **차박 종료**
8. 실측 리포트 + 리뷰·`#일출맛집` 작성 → 탐험가·기록왕 진행도 +1, 테마 마스터(별 헌터) 획득

## 구조

```
app/src/main/java/com/example/meomunjari/
  core/     모델, Geo, 게이트웨이 인터페이스(Vehicle/Location/Navi), VirtualConsole
  domain/   태그·검색·리포트 집계·에너지 예측·도착 인증·세팅 호환성·뱃지 엔진·차박 기록 (순수 Kotlin)
  trip/     TripStore — 목적지 → 도착 인증 → 차박 모드 → 실측 기록 → 리뷰 흐름 상태
  data/     Repository 인터페이스 + Mock 구현(assets/mock JSON)
  ui/       Compose 화면 S1~S9, 가상 콘솔, 테마
  di/       AppContainer (수동 DI)
app/src/mock/   SimulatedWorld, GatewayFactory(mock)
app/src/pleos/  Pleos SDK 게이트웨이, AAOS 기어/속도, GatewayFactory(pleos), 권한 매니페스트
```

설계 문서: 머문자리 설계서 (Claude 아티팩트)
