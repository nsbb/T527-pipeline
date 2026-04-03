# 월패드 연동 문제 기록

**날짜:** 2026-04-03
**디바이스:** IHN-1300D (Android 13, API 33)
**adb:** 00f75c0572408721ed9

---

## 1. Activity에서 VT 루프 미시작

**증상:** 서비스 시작 후 모델 로드까지 성공하나 VT 루프가 안 돌아감

**원인:** `VadPipelineActivity`가 서비스 시작 시 `ACTION_MIC_GRANTED` action을 안 넣음. `onStartCommand`에서 해당 action이 있어야만 `pipelineLoop()` 시작.

**해결:** `intent.setAction(VadPipelineService.ACTION_MIC_GRANTED)` 추가

---

## 2. Toast 안 됨 (Android 13 Service 제한)

**증상:** Foreground Service에서 `Toast.makeText().show()` 호출해도 화면에 안 뜸

**원인:** Android 13 (API 33)에서 Service 컨텍스트의 Toast가 제한됨. 월패드 커스텀 ROM(베스틴) 영향 가능성도 있음.

**해결:** `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW` 권한으로 overlay 표시. 데브킷과 월패드 모두에서 동작 확인.

---

## 3. "하이 원더" 후 "어"/"음" 간투어 인식

**증상:** wakeword 감지 후 STT가 "어", "음" 등 짧은 간투어를 인식

**원인:** wakeword("하이 원더") 꼬리 음성이 AudioRecord 버퍼에 남아서 VAD가 음성으로 잡음. 무음 구간도 speechChunks에 포함되어 total duration이 실제보다 길게 측정됨.

**시도한 방법:**
| 방법 | 결과 |
|------|------|
| AudioRecord 버퍼 flush 0.5초 | 부분 해결. 여전히 잔향 잡힘 |
| flush 0.8초 | 부분 해결 |
| flush 1.0초 | 부분 해결. "어" 1.12초로 잡혀서 여전히 통과 |
| total duration < 1.0초 필터 | "어"가 무음 패딩 포함 1.12초로 통과 |
| total duration < 1.5초 필터 | "보일러 틀어줘"(1.06초)도 같이 잘림 |
| **speech-only duration < 0.5초 필터 (채택)** | **실제 음성 프레임만 카운트. "어" 0.13초 → 필터됨, 짧은 명령은 통과** |

**최종 해결:** flush 1.0초 + speech-only 0.5초 필터 조합

---

## 4. Silence timeout으로 인한 2-chunk STT

**증상:** "엘리베이터 불러줘" 같은 명령이 2 chunk로 처리됨 (npu 500ms+)

**원인:** silence timeout 1.5초 → 발화(~2초) + 무음 대기(~1.5초) = 3.5초 > Conformer 윈도우 3.01초

**해결:** silence timeout 1.5초 → 0.8초로 축소. 대부분의 명령이 1 chunk(~250ms)로 처리됨.

---

## 5. FM1388 마이크 — wakeword prob 낮음

**증상:** 데브킷 USB 마이크에서 prob 0.55~0.64이던 "하이 원더"가 월패드 FM1388에서 0.40~0.51로 낮아짐. threshold(0.40) 턱걸이.

**원인:** FM1388 디지털 마이크의 주파수 응답/게인이 USB 마이크와 다름. wakeword 모델(BCResNet)이 USB 마이크 특성으로 양자화됨.

**시도한 방법:**
| 방법 | 결과 |
|------|------|
| threshold 올리기 (0.40 → 0.45) | 안 됨. 이미 턱걸이라 못 잡힘 |
| 소프트웨어 게인 3x | **악화**. prob 0.29까지 하락. mel 분포가 학습 때와 달라져서 모델이 오히려 못 잡음 |
| tinymix 하드웨어 게인 | FM1388(card 1)에 게인 컨트롤 없음 |

**현재 상태:** threshold 0.40, 게인 없음으로 유지. 근본 해결은 FM1388 녹음 데이터로 wakeword calib 재수행 필요.

---

## 6. ★ 마이크 점유 충돌 (wallpadcall 앱)

**증상:** wakeword가 "들어오다가 안들어오다가" 함. prob이 0.10에 고정되다가 갑자기 잡히기도 함.

**원인:** `kr.co.icontrols.wallpadcall` (월패드 전화 앱)이 `AudioSource.UNPROCESSED`로 마이크를 동시 점유. Android가 우리 앱의 AudioRecord를 **silenced** 처리.

```
dumpsys audio 로그:
  session:641 -- source client=UNPROCESSED -- pack:kr.co.icontrols.wallpadcall
  session:761 -- source client=MIC -- pack:com.t527.vad_service -- silenced
```

반복적으로 silenced → release → 재시작 됨.

**시도한 방법:** 없음 (발견 직후)

**가능한 해결 방향:**
1. `AudioSource.VOICE_RECOGNITION` 사용 — 우선순위가 더 높을 수 있음
2. `AudioSource.UNPROCESSED` 사용 — wallpadcall과 같은 소스로 경쟁
3. 베스틴에 요청 — wallpadcall 앱이 통화 중이 아닐 때 마이크 놓게 수정
4. `AudioAttributes` 설정 — usage/contentType으로 우선순위 조정

---

## 7. 월패드 저장 공간 부족 (3GB /data)

**증상:** APK 재설치 시 `INSTALL_FAILED_INSUFFICIENT_STORAGE`

**원인:** /data 3GB 중 2.7GB 사용 (90%). APK 132MB + 모델 에셋 102MB.

**해결:** 기존 앱 uninstall 후 install. uninstall로 ~350MB 확보 가능.

---

## 월패드 환경 정보

| 항목 | 값 |
|------|-----|
| 모델 | IHN-1300D |
| Android | 13 (API 33) |
| 빌드 | 2026.02.27.01 |
| 마이크 | FM1388 디지털 (`/dev/fm1388_front`) |
| DMIC | 활성화 (`vendor.audio.output.active.mic = DMIC`) |
| NPU 드라이버 | libVIPlite.so, libVIPuser.so (32+64bit) |
| 오디오 카드 | card 0: audiocodec, card 1: sndi2s0 (FM1388), card 2: Loopback |
| 저장 공간 | /data 3.0GB (여유 ~350MB) |
| 화면 | 1920x1080 |
| 마이크 점유 충돌 | `kr.co.icontrols.wallpadcall` (UNPROCESSED 소스) |

---

## 현재 설정값

| 파라미터 | 값 | 비고 |
|---------|-----|------|
| VT threshold | 0.40 | FM1388에서 턱걸이 |
| VAD threshold | 0.50 | |
| Silence timeout | 800ms | 1500 → 800 (2-chunk 방지) |
| Flush | 1.0초 | wakeword 꼬리 제거 |
| Min speech (speech-only) | 0.5초 | 간투어 필터 |
| Max speech | 10초 | |
| MIC gain | 없음 | 3x 시도했으나 악화 |
| Overlay | 반투명 보라색, 중앙+300px 아래 | Toast 불가 → overlay |
