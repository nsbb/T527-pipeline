# 월패드 연동: VT→VAD→STT 백그라운드 서비스

## 개요

월패드(베스틴 앱)에 온디바이스 음성 AI를 연동.
Foreground Service로 백그라운드 상시 실행. 베스틴 앱과의 통신은 **최소한**:

1. **베스틴 → 우리:** 부팅 시 BR intent로 서비스 실행 (1회)
2. **우리 → 베스틴:** Toast로 STT 결과 표시
3. **그 외 통신 없음**

---

## 아키텍처

```
[베스틴 앱 (월패드)]                  [우리 서비스 앱 (com.t527.vad_service)]
  │                                VadPipelineService (Foreground Service)
  │ 부팅 시 intent로 서비스 실행      │
  ├── startForegroundService() ──→  ├── 모델 3개 로드
  │                                  ├── FM1388 마이크 열기
  │   (이후 통신 없음)               ├── VT 상시 루프 ("하이 원더" 대기)
  │                                  ├── 감지 → VAD → STT
  │   ← Toast ──────────────────   └── Toast로 결과 표시
```

---

## 마이크: FM1388 디지털 마이크

월패드에 내장된 FM1388 디지털 마이크 사용.
마이크를 "넘겨받는" 개념이 아니라, 서비스가 시작되면 직접 AudioRecord로 열어서 사용.

### 마이크 활성화

```bash
adb shell
setprop vendor.audio.output.active.mic DMIC
```

### 동작 확인 (root 필요)

```bash
adb shell
su
fm_fm1388
```

정상 출력:
```
lib_enum_device: find device /dev/fm1388_front.
Using Front FM1388 device /dev/fm1388_front
The current dsp_mode = 1
1...VRMode (current used)
```

### fm_fm1388 녹음 테스트

```bash
su → fm_fm1388 → e(녹음시작) → 파일명 → 채널(예:01347) → f(종료)
저장: /sdcard/파일명.wav
```

### 앱에서 FM1388 사용

현재 앱은 `MediaRecorder.AudioSource.MIC` + 48kHz 사용.
FM1388이 이 AudioSource로 잡히는지 **월패드에서 확인 필요**.
안 되면 `setprop` 먼저 실행하거나 AudioSource 값 변경 필요.

---

## 베스틴 앱에서 할 일 (1개만)

부팅 시 우리 서비스를 실행해주기만 하면 됨.

```java
// 베스틴 앱 — 부팅 시 실행
Intent intent = new Intent();
intent.setClassName("com.t527.vad_service",
    "com.t527.wav2vecdemo.VadPipelineService");
context.startForegroundService(intent);
```

**우리 패키지명:** `com.t527.vad_service`

### adb로 수동 테스트

```bash
# 서비스 시작 (Activity 경유)
adb shell am start -n com.t527.vad_service/com.t527.wav2vecdemo.VadPipelineActivity

# 서비스 직접 시작
adb shell am startservice -n com.t527.vad_service/com.t527.wav2vecdemo.VadPipelineService
```

---

## 서비스 동작 흐름

```
1. 베스틴 부팅 → intent로 서비스 시작

2. Service onCreate:
   → 모델 3개 로드 (Conformer 102MB, Wakeword 229KB, VAD 2.3MB)
   → FM1388 마이크 AudioRecord 생성 (48kHz MONO)
   → VT 루프 시작

3. VT 루프 (상시):
   → 0.5초마다 BCResNet NPU 추론 (8ms)
   → "하이 원더" 감지 (prob ≥ 0.40)
   → Toast: "하이 원더 감지!"

4. VAD + STT:
   → Silero VAD로 음성 구간 수집 (32ms 단위)
   → 무음 1.5초 → 녹음 종료
   → Conformer NPU 슬라이딩 윈도우 추론 (250ms/chunk)
   → CTC decode + 간투어 후처리
   → Toast: 인식 결과 표시

5. VT 루프 복귀 (3으로 돌아감)
```

---

## 앱 구성

### 서비스 앱 (t527_vad_service)

**위치:** `C:\Users\nsbb\AndroidStudioProjects\t527_vad_service\`
**패키지:** `com.t527.vad_service`

| 파일 | 역할 |
|------|------|
| `VadPipelineService.java` | Foreground Service — VT → VAD → STT → Toast |
| `SttBroadcastReceiver.java` | BR 수신 → Service 시작 |
| `BootReceiver.java` | 부팅 시 서비스 자동 시작 |
| `VadPipelineActivity.java` | 런처 Activity — 권한 획득 + 서비스 시작 → finish() |
| `conformer/AwConformerJni.java` | NPU JNI (Conformer + Wakeword) |
| `conformer/ConformerDecoder.java` | CTC 디코더 + 간투어 후처리 |
| `conformer/SileroVad.java` | Silero VAD (ONNX Runtime CPU) |

### 모델 assets

```
app/src/main/assets/models/
├── Conformer/network_binary.nb     (102MB, NPU uint8)
├── Conformer/vocab_correct.json    (2049 BPE vocab)
├── Conformer/nbg_meta.json
├── Wakeword/network_binary.nb      (229KB, NPU uint8)
├── Wakeword/nbg_meta.json
└── VAD/silero_vad.onnx             (2.3MB, CPU)
```

---

## 필요 권한

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

설치 후 권한 부여:
```bash
adb shell pm grant com.t527.vad_service android.permission.RECORD_AUDIO
adb shell appops set com.t527.vad_service SYSTEM_ALERT_WINDOW allow
```

---

## 모델 성능

| 단계 | 모델 | 추론 시간 | 성능 |
|------|------|----------|------|
| VT | BCResNet-t2 NPU uint8 | 8ms | recall 94.3% (th=0.40) |
| VAD | Silero v5 CPU (ONNX RT) | 0.05ms | - |
| STT | Conformer CTC NPU uint8 (QAT 1m_ep01) | 250ms/chunk | CER 8.86% (18k avg) |

---

## 미확인 사항

| 항목 | 비고 |
|------|------|
| FM1388 → AudioRecord 연동 | AudioSource.MIC로 잡히는지 월패드에서 확인 |
| Toast 동작 | 월패드에서는 됨 (데브킷에서는 차단되어 overlay 사용) |
| NPU 드라이버 | libVIPlite.so, libVIPuser.so 월패드에 있는지 확인 |
| 서비스 자동 시작 | 베스틴 부팅 시 intent로 실행해주는 방식 |

---

## 원본 보존

| 프로젝트 | 상태 |
|---------|------|
| `t527_pipeline` | 원본 VT+STT Activity 앱 (수정 금지) |
| `t527_vad_pipeline` | VAD 추가 Activity 앱 (수정 금지) |
| **`t527_vad_service`** | **Service 변환 앱 (월패드용, 현재 개발 중)** |
| `bestin_test` | 베스틴 테스트 앱 |
