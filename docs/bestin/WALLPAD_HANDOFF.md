# 월패드 연동 핸드오프 문서

**날짜:** 2026-04-03
**상태:** 데브킷 테스트 완료, 월패드 실기기 연동 진행 중

---

## 1. 지금까지 한 것

### 모델 개발 (t527-stt)

| 작업 | 결과 |
|------|------|
| Conformer CTC NB 변환 | NeMo → ONNX → Acuity → NB (102MB) |
| PTQ baseline | avg_all CER 15.59% (18,368샘플) |
| QAT 100k (10ep, margin 0.3) | avg_all CER 9.30% |
| **QAT 1M (1ep, margin 0.3)** | **avg_all CER 8.86% (현재 Best)** |
| Calibration 실험 | aihub100 > mix50 > real100. 개수가 소스보다 중요 |
| K-Medoids calib 100개 | 분포 기반 선택 완료, 테스트 대기 |
| FP32 vs QAT 비교 | 007.저음질에서 QAT가 FP32 능가 → hallucination 분석 |
| 에러 유형 분석 | ins/del/sub 분석, 간투어 "음" 83회 발견 |
| BCResNet wakeword NB | 229KB, NPU 8ms, recall 94.3% |
| Silero VAD | ONNX Runtime CPU 0.05ms (NB 변환 불가 확인) |

### 앱 개발 (t527-pipeline)

| 작업 | 결과 |
|------|------|
| t527_pipeline | VT → STT Activity 앱 (고정 3초 녹음) |
| t527_vad_pipeline | VT → VAD → STT Activity 앱 (가변 길이 녹음) |
| **t527_vad_service** | **Foreground Service 앱 (월패드용)** |
| bestin_test | 베스틴 테스트 앱 (버튼 → BR) |
| C/JNI mel 전처리 | Conformer Slaney + BCResNet HTK, KissFFT |
| CTC 디코더 | greedy decode + 간투어 후처리 7종 |

### 데브킷 테스트 결과

| 항목 | 결과 |
|------|------|
| Service 백그라운드 실행 | OK |
| VT → VAD → STT → overlay | OK |
| BR 수신 → 서비스 시작 | OK |
| USB 마이크 실시간 인식 | OK |
| 부팅 자동 시작 (BootReceiver) | OK |

---

## 2. 월패드 연동 방식 (확정)

### 통신 구조

```
베스틴 앱 → 부팅 시 intent로 서비스 실행 (1회, 이후 통신 없음)
우리 서비스 → 자체적으로 VT 상시 대기 → STT → Toast 결과 표시
```

- **베스틴이 할 일:** 부팅 시 `startForegroundService(intent)` 1번
- **우리 패키지명:** `com.t527.vad_service`
- **마이크:** FM1388 디지털 마이크 (서비스가 직접 AudioRecord로 열음)
- **결과 표시:** Toast

### 베스틴 코드 예시

```java
Intent intent = new Intent();
intent.setClassName("com.t527.vad_service",
    "com.t527.wav2vecdemo.VadPipelineService");
context.startForegroundService(intent);
```

---

## 3. FM1388 디지털 마이크

### 활성화

```bash
setprop vendor.audio.output.active.mic DMIC
```

### 동작 확인 (root 필요)

```bash
su
fm_fm1388
# 정상: "find device /dev/fm1388_front" 출력
```

### 녹음 테스트

```bash
su → fm_fm1388 → e → 파일명 → 채널(01347) → f
저장: /sdcard/파일명.wav
```

### 앱 연동 미확인

- `AudioRecord(MediaRecorder.AudioSource.MIC, 48000, MONO, PCM16)` 으로 FM1388이 잡히는지 확인 필요
- 안 되면 `setprop` 먼저 실행하거나 AudioSource 변경

---

## 4. 서비스 앱 구조

```
t527_vad_service/app/src/main/java/com/t527/wav2vecdemo/
├── VadPipelineActivity.java      # 런처: 권한 → 서비스 시작 → finish()
├── VadPipelineService.java       # Foreground Service: VT → VAD → STT → Toast
├── SttBroadcastReceiver.java     # BR 수신
├── BootReceiver.java             # 부팅 자동 시작
└── conformer/
    ├── AwConformerJni.java        # NPU JNI
    ├── ConformerDecoder.java      # CTC 디코더
    └── SileroVad.java             # Silero VAD

assets/models/
├── Conformer/network_binary.nb   # 102MB NPU uint8
├── Wakeword/network_binary.nb    # 229KB NPU uint8
└── VAD/silero_vad.onnx           # 2.3MB CPU
```

---

## 5. 빌드/설치/테스트

```bash
# 빌드 (C:\Users\nsbb\ 경로에서, 공백 경로 안 됨)
cd C:\Users\nsbb\AndroidStudioProjects\t527_vad_service
.\gradlew.bat assembleDebug

# 설치
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell pm grant com.t527.vad_service android.permission.RECORD_AUDIO
adb shell appops set com.t527.vad_service SYSTEM_ALERT_WINDOW allow

# 서비스 시작
adb shell am start -n com.t527.vad_service/com.t527.wav2vecdemo.VadPipelineActivity

# 로그 확인
adb logcat -s VadPipelineService SileroVad
```

---

## 6. 모델 성능

| 단계 | 모델 | 크기 | 추론 | 성능 |
|------|------|------|------|------|
| VT | BCResNet-t2 NPU uint8 | 229KB | 8ms | recall 94.3% |
| VAD | Silero v5 CPU | 2.3MB | 0.05ms | - |
| STT | Conformer CTC NPU uint8 | 102MB | 250ms/chunk | CER 8.86% |

전체 파이프라인: "하이 원더" → VAD 녹음 → STT → Toast ≈ **~1.5초**

---

## 7. 할 일

1. **FM1388 → AudioRecord 연동 확인** — 월패드에서 AudioSource 테스트
2. **월패드에 APK 설치** — NPU 드라이버(libVIPlite.so) 존재 확인
3. **Toast 동작 확인** — 월패드에서 됨 (확인됨)
4. **베스틴 부팅 시 서비스 시작** — 베스틴 개발자에게 패키지명 전달
5. **실환경 음성 테스트** — FM1388 마이크 품질, 거리별 인식률

---

## 8. 관련 문서

| 문서 | 위치 |
|------|------|
| 서비스 아키텍처 | `t527-pipeline/docs/bestin/SERVICE_ARCHITECTURE.md` |
| 이 핸드오프 문서 | `t527-pipeline/docs/bestin/WALLPAD_HANDOFF.md` |
| STT 성능 비교 | `t527-stt/conformer/results/QUANTIZATION_COMPARISON.md` |
| QAT 실험 요약 | `t527-stt/conformer/experiments/260331_qat_all_experiments_summary.md` |
| 에러 유형 분석 | `t527-stt/conformer/results/qat_100k_calib_aihub100_18k/FP32_VS_QAT_ERROR_ANALYSIS.md` |
| 007 QAT>FP32 분석 | `t527-stt/conformer/results/qat_100k_calib_aihub100_18k/007_QAT_BEATS_FP32_ANALYSIS.md` |
| TODO 개선 목록 | `t527-stt/conformer/docs/TODO_IMPROVEMENTS.md` |
| 파이프라인 README | `t527-pipeline/README.md` |
