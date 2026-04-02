# 월패드 연동: VT→VAD→STT 백그라운드 서비스

## 개요

월패드(베스틴 앱)에 온디바이스 음성 AI를 연동.
기존 Activity 기반 앱을 **Foreground Service**로 변환하여 백그라운드 상시 실행.

---

## 아키텍처

```
[베스틴 앱 (월패드)]                  [우리 서비스 앱]
  │                                VadPipelineService (Foreground Service)
  │                                  ├── VT 상시 대기 (BCResNet NPU, 8ms)
  ├── sendBroadcast() ── BR ──→      ├── BR 수신 → STT 트리거
  │   action:                        ├── VAD (Silero CPU, 0.05ms)
  │   "com.t527.vad_pipeline         ├── STT (Conformer NPU, 250ms/chunk)
  │    .ACTION_START_STT"            └── Toast 결과 표시
  │
  └── 부팅 시 자동 시작 (BootReceiver)
```

## 동작 시나리오

### 시나리오 1: 하이원더 (VT 트리거)
```
서비스 상시 실행 → VT 루프 (0.5초마다 wakeword 체크)
    → "하이 원더" 감지 (prob ≥ 0.40)
    → VAD 시작 (음성 구간 자동 감지)
    → 무음 1.5초 → STT 실행
    → Toast로 텍스트 표시
    → VT 루프 복귀
```

### 시나리오 2: 베스틴 앱에서 BR 트리거
```
베스틴 앱 → sendBroadcast(ACTION_START_STT)
    → SttBroadcastReceiver.onReceive()
    → Service에 STT 시작 신호
    → VAD 시작 (VT 건너뜀)
    → 무음 1.5초 → STT 실행
    → Toast로 텍스트 표시
```

---

## 앱 구성

### 1. 서비스 앱 (t527_vad_service)

| 파일 | 역할 |
|------|------|
| `VadPipelineService.java` | Foreground Service — VT + VAD + STT |
| `SttBroadcastReceiver.java` | BR 수신 → Service에 STT 트리거 |
| `BootReceiver.java` | 부팅 시 서비스 자동 시작 |
| `VadPipelineActivity.java` | 최소 Activity — 권한 획득 + 서비스 시작 후 종료 |

- **패키지명:** `com.t527.vad_pipeline`
- **BR action:** `com.t527.vad_pipeline.ACTION_START_STT`

### 2. 베스틴 테스트 앱 (bestin_test)

| 파일 | 역할 |
|------|------|
| `BestinTestActivity.java` | 버튼 → BR 전송 |

- **패키지명:** `com.t527.bestin_test`

---

## 모델

| 모델 | 파일 | 크기 | 실행 환경 | 추론 시간 |
|------|------|------|----------|----------|
| BCResNet (VT) | network_binary.nb | 229KB | NPU | 8ms |
| Silero VAD | silero_vad.onnx | 2.3MB | CPU (ONNX RT) | 0.05ms |
| Conformer (STT) | network_binary.nb | 102MB | NPU | 250ms/chunk |

---

## 베스틴 앱 연동 방법

### 베스틴 앱에서 STT 요청 보내기

```java
// 베스틴 앱 코드
Intent intent = new Intent("com.t527.vad_pipeline.ACTION_START_STT");
intent.setPackage("com.t527.vad_pipeline");
sendBroadcast(intent);
```

### adb로 테스트

```bash
adb shell am broadcast -a com.t527.vad_pipeline.ACTION_START_STT -p com.t527.vad_pipeline
```

---

## 필요 권한

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

---

## 검증 계획

1. 데브킷(T527)에 서비스 앱 설치 → logcat으로 서비스 시작 확인
2. "하이 원더" → Toast 결과 확인
3. 베스틴 테스트 앱 설치 → 버튼 클릭 → Toast 결과 확인
4. `adb shell am broadcast` 로 BR 트리거 확인
5. 재부팅 후 서비스 자동 시작 확인

---

## 원본 보존

- `t527_vad_pipeline/` — 원본 Activity 앱 (수정 금지)
- `t527_vad_service/` — Service 변환 앱 (이 프로젝트)
