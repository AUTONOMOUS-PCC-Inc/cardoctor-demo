# 신호 매핑표 (Signal Map)

앱의 판정이 실차에서 어떤 신호로 동작하는지, 그리고 에뮬레이터 시연에서 무엇이 실제 신호이고 무엇이 시연값인지 정리합니다.
검증 환경: Pleos Connect v2.1 에뮬레이터 (AAOS API 34), Vehicle SDK 2.3.2.

## 1. 차량 신호

| 앱 기능 | 실차 신호 (VHAL 속성) | SDK API | 권한 | 공개 여부 | 에뮬레이터 | 시연 방법 |
|---|---|---|---|---|---|---|
| 경고등 기반 고장 판정 (브레이크·ABS·에어백·과열·배터리·조향·TPMS·헤드램프) | `TELLTALES` | `Safety.getTelltalesInfo` | `PRIVATE_CAR_SAFETY` | **비공개** | 실제 신호 | Virtual Console `/vehicle/telltales/simulator` (GUI 시연 콘솔 FAULT INJECTION) |
| 야간 전조등 꺼짐 (`night_lights_off`) | `LOW_BEAM_LIGHTS_SWITCH`, `LOW_BEAM_LIGHTS_STATE` | `Light.getLowBeamLightSwitch` / `getCurrentLowBeamLightState` | `CAR_LIGHTS` | 공개 | 실제 신호 | 밤 칩 + 주행 칩, 전조등 스위치 OFF |
| 비·눈 판단 (와이퍼) | `WINDSHIELD_WIPERS_SWITCH`, `WINDSHIELD_WIPERS_STATE` | `Wiper.getCurrentWiperSwitch` / `getCurrentWiperState` | `READ_WINDSHIELD_WIPERS` | 공개 | 실제 신호 | GUI 시연 콘솔 "와이퍼 작동 (비)" |
| 주행 중 판단 (화면 잠금, 음성 우선) | `GEAR_SELECTION` (VENDOR) | `Gear.getCurrentGearSelectionState` | `CAR_DRIVE` | 공개 | 실제 신호 (P) | 앱 상단 "정차/주행 중" 칩 또는 GUI 시연 콘솔 DRIVING CONTEXT로 강제 |
| 구동 배터리 부족 (`low_ev_battery`, 15% 미만) | `EV_BATTERY_LEVEL`, `INFO_EV_BATTERY_CAPACITY` | `EvBattery.getEvBatteryLevel` / `getEvBatteryCapacity` | `CAR_ENERGY`, `CAR_INFO` | 공개 | 실제 신호 (77.4kWh / 100%) | 에뮬레이터 값 변경 시 자동 반영 |
| 바퀴별 타이어 공기압 (F1) | `TIRE_PRESSURE`, `CRITICALLY_LOW_TIRE_PRESSURE` | `Tire.getTirePressure` / `getLowTirePressure` | `CAR_SAFETY` | 공개 | **속성 없음** ("not in carPropertyConfig") | GUI 시연 콘솔 APP · TIRE PRESSURE 시연값 (adb 브로드캐스트) (화면에 "시연값" 표시) |

## 2. 주행 환경

| 환경 | 자동 소스 | 수동 | 비고 |
|---|---|---|---|
| 주행 중 | 기어 (P·N 이외) | 칩: 자동 → 강제 ON → 강제 OFF | 주행 중이면 자세히보기·리포트가 잠기고 음성 질문으로 안내 |
| 밤 | 시각 | 칩 | |
| 비·눈 | 와이퍼 신호, 없으면 날씨 API(MET Norway → Open-Meteo) | 칩 | |
| 고속도로 / 50km 이상 | (미연결) | 칩 | 실차에서는 내비 경로 정보로 대체 예정 |

## 3. 서비스

| 기능 | SDK / API | 권한 | 에뮬레이터 |
|---|---|---|---|
| 음성 경고 (F3) | Pleos TextToSpeech 3.3.1, `ON_DEVICE` + `ko_KR` | `TTS_SERVICE` | 동작 |
| 음성 질문 | Pleos SpeechToText 3.3.1, `ON_DEVICE` + `ko_KR` | `STT_SERVICE`, `RECORD_AUDIO` | 엔진 연결·요청 동작. 에뮬레이터는 마이크 입력이 없어 GUI 시연 콘솔 질문 버튼(adb 브로드캐스트)으로 같은 처리 경로를 시연 |
| 길안내 (F4) | Pleos NaviHelper 3.0.5 `requestRoute` | `NAVI_ROUTE` | 동작 (GPS를 판교로 고정) |
| 위치 | Pleos FusedLocation 1.1.3 | `FUSED_LOCATION`, `ACCESS_FINE_LOCATION` | 동작 |
| 정비소·주차장 검색 | Tmap POI | 앱 키 | 동작 |
| AI 설명 (F6) | Gemini | API 키 (Gemini API 전용 · 무료 등급 · 하루 300건) | 동작. 실패 시 규칙표 문구 |

## 4. `PRIVATE_CAR_SAFETY`를 못 받을 때 (공개 신호 모드)

경고등 읽기가 `권한 없음`으로 실패하면 앱은 멈추지 않고 공개 권한 신호만으로 계속 진단합니다.

- 계속 동작: 바퀴별 타이어 공기압, 구동 배터리 부족, 야간 전조등 꺼짐, 와이퍼 기반 악천후 가중치, 주행 중 잠금, 음성 질문·안내, 정비소 안내, 리포트·QR
- 빠지는 것: 경고등에서만 알 수 있는 고장(브레이크 시스템, ABS, 에어백, 과열, 12V 배터리, 조향 보조)
- 화면 하단에 "공개 신호 모드 · 경고등 권한 없음 → 타이어 · 배터리 · 전조등 신호로 진단"을 표시합니다.

## 5. 시연값과 실제 신호 구분

- 시연값으로 만든 판정은 감지 근거에 "시연값"이 붙고, 리포트 JSON에 `environmentSimulated: true`가 들어갑니다.
- 강제한 환경 칩은 칩 아래에 "강제"로 표시됩니다.
- 판정 근거(기본 심각도 + 가중치 = 점수)는 카드에 항상 보입니다.
