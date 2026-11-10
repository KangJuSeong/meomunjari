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

이 코드는 Google Maven·Pleos 저장소에 접근할 수 없는 환경에서 작성했습니다. Android Studio에서 첫 빌드가 이 레포의 실제 검증입니다.

1. **Kotlin 버전** — `gradle/libs.versions.toml`의 `kotlin = "2.3.0"`은 Compose·Serialization 컴파일러 플러그인 버전입니다. AGP 9.4가 다른 버전을 요구하면 이 값만 바꾸세요.
2. **Compose BOM** — `composeBom = "2025.06.00"`. Studio가 더 새 버전을 제안하면 올려도 됩니다.
3. **pleos 플레이버** — Door 외 도메인은 공개 문서에서 시그니처를 확인하지 못해 `PleosInvoker`가 메서드 이름으로 리플렉션 호출합니다(공식 샘플 `pleos-sample-vehicle-app`의 방식). 컴파일은 되지만, 실제 에뮬레이터에서 각 호출 결과는 Logcat `PleosVehicle`/`PleosInvoker` 태그로 확인하세요.

## 여기서 검증한 것

- `core`·`domain`·`trip`·`data`·`sim` 패키지: Kotlin 컴파일러로 컴파일 완료
- 단위 테스트 12개 통과 (`./gradlew testMockDebugUnitTest`)
  - `DomainTest`: 태그 정규화, 차급 필터, 실측 리포트 문구, 에너지 예측(62% → 아침 48% 충분 / 25% → 15분 충전), 도착 인증, 뱃지·등급, 고수 세팅
  - `DemoScenarioTest`: 시연 시나리오 4~8단계 전체 흐름 + 금지구역 등록 차단
- `pleos` 게이트웨이: SDK 표면 스텁으로 컴파일, 리플렉션 호출기 런타임 동작 확인
- UI(Compose) 코드는 이 환경에서 컴파일하지 못했습니다.

## 시연 시나리오 (mock 플레이버)

1. **테마 검색** → `#별보기`, `#반려견동반` → '내 차가 들어갈 수 있는 곳만' 켜짐 → 미시령 옛길(소형 SUV까지)이 빠짐
2. **인제 방태산 계곡 쉼터** → 실측 리포트 "11월 기준 새벽 실내 최저 9°C · 공조 8시간 사용 시 배터리 14% 소모 · 기록 12회 중 비 온 날 3회", "지금 2대 차박 중"
3. 가상 콘솔에서 배터리 62% → 아침 잔량 "충분해요" / 25% → "경로상 충전소에서 N분 충전"
4. **여기로 갈래요** → 내비 목적지 설정
5. 콘솔 **목적지까지 자동 주행** → 주행 중 화면 → 도착 후 기어 **P** → 3초 뒤 도착 인증
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
