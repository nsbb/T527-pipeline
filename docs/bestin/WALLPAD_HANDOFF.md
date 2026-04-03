# 월패드 연동 핸드오프 문서

**날짜:** 2026-04-03
**상태:** 데브킷 테스트 완료, 월패드 실기기 연동 진행 중

---

## 1. 현재 완료된 것

### 앱 4개

| 앱 | 위치 | 패키지 | 상태 |
|----|------|--------|------|
| **t527_pipeline** | AndroidStudioProjects/t527_pipeline | com.t527.pipeline | 원본 Activity 앱 (수정 금지) |
| **t527_vad_pipeline** | AndroidStudioProjects/t527_vad_pipeline | com.t527.vad_pipeline | VAD 추가 Activity 앱 (수정 금지) |
| **t527_vad_service** | AndroidStudioProjects/t527_vad_service | com.t527.vad_service | **Service 앱 (월패드용)** |
| **bestin_test** | AndroidStudioProjects/bestin_test | com.t527.bestin_test | 베스틴 테스트 앱 |

### Service 앱 동작 확인 (데브킷)
- Foreground Service 백그라운드 실행 OK
- VT(하이원더) → VAD → STT → overlay 표시 OK
- BR 수신 (ACTION_MIC_GRANTED, ACTION_START_STT) OK
- 부팅 시 자동 시작 (BootReceiver) OK

---

## 2. 월패드 연동 방식 (확정)

### BR (BroadcastReceiver)

베스틴 앱이 **부팅 시** intent로 우리 서비스 패키지를 실행해줌.

```java
// 베스틴 앱에서 부팅 시 실행
Intent intent = new Intent();
intent.setClassName("com.t527.vad_service", "com.t527.wav2vecdemo.VadPipelineService");
startForegroundService(intent);
```

또는 BR로:
```java
Intent intent = new Intent("com.t527.vad_pipeline.ACTION_MIC_GRANTED");
intent.setPackage("com.t527.vad_service");
sendBroadcast(intent);
```

### 서비스 실행 후 동작

```
베스틴 부팅 → intent로 서비스 시작
  → 모델 3개 로드 (Conformer 102MB, Wakeword 229KB, VAD 2.3MB)
  → 마이크 활성화
  → VT 상시 루프 ("하이 원더" 대기)
  → 감지 시 → VAD → STT → Toast/overlay 결과 표시
```

### STT 결과 전달

현재: Toast/overlay로 화면에 표시
월패드에서는 Toast로 표시 가능하다고 확인됨 (데브킷에서는 Toast 차단되어 overlay 사용)

---

## 3. 마이크: FM1388 디지털 마이크

월패드에는 USB 마이크가 아닌 **FM1388 디지털 마이크** 사용.

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
Supported modes:
The current dsp_mode = 1
1...VRMode (current used)
```

※ root 없이 실행 시 "can not find FM1388 device" 출력

### fm_fm1388 녹음 테스트

```bash
su
fm_fm1388
# e 입력 → 녹음 시작
# 파일명 입력 (예: demo1.wav)
# 채널 선택 (예: 01347)
# f 입력 → 녹음 종료
# 저장: /sdcard/demo1.wav
```

### 앱에서 FM1388 마이크 사용

현재 앱은 `MediaRecorder.AudioSource.MIC` + 48kHz로 녹음 중.
FM1388에서 AudioRecord로 녹음하려면:

1. `setprop vendor.audio.output.active.mic DMIC` 실행 후 앱 시작
2. AudioSource가 DMIC을 잡는지 확인 필요
3. 안 되면 AudioSource 값을 커스텀 값(3855 등)으로 변경 필요

**→ 월패드에서 실제 테스트하여 AudioSource 값 확인 필수**

---

## 4. 미확인 사항

| 항목 | 상태 | 비고 |
|------|------|------|
| FM1388 → AudioRecord 연동 | **미확인** | AudioSource 값 확인 필요 |
| Toast 동작 | 베스틴에서 됨 확인 | 데브킷에서는 차단됨 |
| 부팅 시 서비스 자동 시작 | **미확인** | 베스틴 BSP에서 intent로 실행해주는 방식 |
| STT 후 마이크 반납 여부 | **미확인** | 현재는 VT 상시 루프 |
| NPU 드라이버 존재 여부 | **미확인** | libVIPlite.so, libVIPuser.so 필요 |

---

## 5. 빌드 방법

```bash
# 서비스 앱 빌드 (WSL에서)
cd C:\Users\nsbb\AndroidStudioProjects\t527_vad_service
.\gradlew.bat assembleDebug

# APK 위치
app\build\outputs\apk\debug\app-debug.apk

# 설치 (NDK 경로에 공백 있으면 C:\Users\nsbb\ 경로에서 빌드해야 함)
adb install -r app-debug.apk
adb shell pm grant com.t527.vad_service android.permission.RECORD_AUDIO
adb shell appops set com.t527.vad_service SYSTEM_ALERT_WINDOW allow
```

### 서비스 시작 (수동)

```bash
adb shell am start -n com.t527.vad_service/com.t527.wav2vecdemo.VadPipelineActivity
```

### BR 테스트 (adb)

```bash
# 마이크 전달 → VT 시작
adb shell am broadcast -a com.t527.vad_pipeline.ACTION_MIC_GRANTED -p com.t527.vad_service

# 바로 STT (VT 건너뜀)
adb shell am broadcast -a com.t527.vad_pipeline.ACTION_START_STT -p com.t527.vad_service
```

---

## 6. 서비스 앱 파일 구조

```
t527_vad_service/app/src/main/java/com/t527/wav2vecdemo/
├── VadPipelineActivity.java      # 런처: 권한 획득 → 서비스 시작 → finish()
├── VadPipelineService.java       # Foreground Service: VT → VAD → STT
├── SttBroadcastReceiver.java     # BR 수신 → Service에 전달
├── BootReceiver.java             # 부팅 시 자동 시작
└── conformer/
    ├── AwConformerJni.java        # NPU JNI (Conformer + Wakeword)
    ├── ConformerDecoder.java      # CTC 디코더 + 간투어 후처리
    └── SileroVad.java             # Silero VAD (ONNX Runtime CPU)
```

### 모델 assets

```
app/src/main/assets/models/
├── Conformer/
│   ├── network_binary.nb          # 102MB, NPU uint8
│   ├── vocab_correct.json         # 2049 BPE vocab
│   └── nbg_meta.json              # 양자화 파라미터
├── Wakeword/
│   ├── network_binary.nb          # 229KB, NPU uint8
│   └── nbg_meta.json
└── VAD/
    └── silero_vad.onnx            # 2.3MB, CPU
```

---

## 7. 현재 모델 성능

| 단계 | 모델 | 추론 시간 | CER |
|------|------|----------|-----|
| VT | BCResNet-t2 NPU uint8 | 8ms | recall 94.3% |
| VAD | Silero v5 CPU | 0.05ms | - |
| STT | Conformer CTC NPU uint8 (QAT 1m_ep01) | 250ms/chunk | avg_all 8.86% |

---

## 8. 할 일

1. **FM1388 마이크 → AudioRecord 연동 확인** — AudioSource 값
2. **월패드에 APK 설치 + NPU 드라이버 확인**
3. **Toast 동작 확인** (월패드에서)
4. **베스틴 부팅 시 서비스 시작 연동**
5. **실환경 음성 테스트** — FM1388 마이크 품질, 거리별 인식률
