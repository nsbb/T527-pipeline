# 월패드 연동: VT→VAD→STT 백그라운드 서비스

## 개요

월패드(베스틴 앱)에 온디바이스 음성 AI를 연동.
Foreground Service로 백그라운드 상시 실행. 베스틴 앱이 BR로 마이크 전달 → VT 시작.

---

## 아키텍처

```
[베스틴 앱 (월패드)]                  [우리 서비스 앱]
  │                                VadPipelineService (Foreground Service)
  │                                  ├── 서비스 시작 → 모델 로드 → 마이크 대기
  ├── BR: ACTION_MIC_GRANTED ──→     ├── 마이크 획득 → VT 루프 시작
  │                                  ├── "하이 원더" 감지 → VAD → STT
  ├── BR: ACTION_START_STT ───→      ├── (옵션) VT 건너뛰고 바로 STT
  │                                  └── 결과: overlay/Toast 표시
  └── 부팅 시 자동 시작 (BootReceiver)
```

---

## 현재 상태 (2026-04-02)

### 완료
- 서비스 앱 빌드/설치/동작 확인 (T527 데브킷)
- VT(wakeword) → VAD → STT → overlay 표시 동작
- BR 수신 (ACTION_MIC_GRANTED, ACTION_START_STT) 동작 확인
- 베스틴 테스트 앱 빌드/설치/동작 확인
- adb BR 테스트 성공

### 미확인 (베스틴 개발자와 협의 필요)
- BR 시나리오: 마이크 전달 → VT+STT 1회 → 반납? or 상시?
- AudioSource 3855 사용 여부
- STT 결과 전달 방식: Toast? BR로 결과 반환?
- VT는 우리가 하는지 베스틴이 하는지

---

## BR Action 목록

| Action | 용도 | 보내는 쪽 |
|--------|------|----------|
| `com.t527.vad_pipeline.ACTION_MIC_GRANTED` | 마이크 전달 → VT 시작 | 베스틴 앱 |
| `com.t527.vad_pipeline.ACTION_START_STT` | VT 건너뛰고 바로 STT | 베스틴 앱 |

---

## 앱 구성

### 1. 서비스 앱 (t527_vad_service)

**위치:** `C:\Users\nsbb\AndroidStudioProjects\t527_vad_service\`
**패키지:** `com.t527.vad_service`

| 파일 | 역할 |
|------|------|
| `VadPipelineService.java` | Foreground Service — 마이크 대기 → VT → VAD → STT |
| `SttBroadcastReceiver.java` | BR 수신 → Service에 전달 |
| `BootReceiver.java` | 부팅 시 서비스 자동 시작 |
| `VadPipelineActivity.java` | 최소 Activity — 권한 획득 + 서비스 시작 후 finish() |

### 2. 베스틴 테스트 앱 (bestin_test)

**위치:** `C:\Users\nsbb\AndroidStudioProjects\bestin_test\`
**패키지:** `com.t527.bestin_test`

| 파일 | 역할 |
|------|------|
| `BestinActivity.java` | "BESTIN" 화면 + 버튼 → BR(ACTION_MIC_GRANTED) 전송 |

---

## 동작 흐름

### 1. 서비스 시작
```
앱 실행 (또는 부팅) → VadPipelineActivity
  → 마이크 권한 확인
  → startForegroundService(VadPipelineService)
  → finish() (Activity 종료, 서비스만 남음)

Service onCreate:
  → 모델 3개 로드 (Conformer, Wakeword, VAD)
  → 마이크 대기 상태 (VT 안 돌림)
```

### 2. 마이크 전달 (BR)
```
베스틴 앱 → sendBroadcast(ACTION_MIC_GRANTED)
  → SttBroadcastReceiver → Service.onStartCommand()
  → AudioRecord 생성 + VT 루프 시작
```

### 3. 하이원더 → STT
```
VT 루프: 0.5초마다 wakeword 체크
  → "하이 원더" 감지 (prob ≥ 0.40)
  → overlay "하이 원더 감지!"
  → VAD: 음성 구간 수집 (무음 1.5초 → 종료)
  → STT: 슬라이딩 윈도우 mel → NPU → CTC decode
  → overlay 결과 표시 (5초간)
  → VT 루프 복귀
```

---

## 베스틴 앱 코드 예시

```java
// 마이크 전달 (VT 시작)
Intent intent = new Intent("com.t527.vad_pipeline.ACTION_MIC_GRANTED");
intent.setPackage("com.t527.vad_service");
sendBroadcast(intent);

// 바로 STT (VT 건너뜀)
Intent intent = new Intent("com.t527.vad_pipeline.ACTION_START_STT");
intent.setPackage("com.t527.vad_service");
sendBroadcast(intent);
```

### adb 테스트

```bash
# 마이크 전달
adb shell am broadcast -a com.t527.vad_pipeline.ACTION_MIC_GRANTED -p com.t527.vad_service

# 바로 STT
adb shell am broadcast -a com.t527.vad_pipeline.ACTION_START_STT -p com.t527.vad_service
```

---

## 모델

| 모델 | 파일 | 크기 | 실행 환경 | 추론 시간 |
|------|------|------|----------|----------|
| BCResNet (VT) | network_binary.nb | 229KB | NPU | 8ms |
| Silero VAD | silero_vad.onnx | 2.3MB | CPU (ONNX RT) | 0.05ms |
| Conformer (STT) | network_binary.nb | 102MB | NPU | 250ms/chunk |

---

## 필요 권한

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

---

## 원본 보존

| 프로젝트 | 위치 | 상태 |
|---------|------|------|
| `t527_pipeline` | AndroidStudioProjects/t527_pipeline | 원본 Activity 앱 (수정 금지) |
| `t527_vad_pipeline` | AndroidStudioProjects/t527_vad_pipeline | VAD 추가 Activity 앱 (수정 금지) |
| `t527_vad_service` | AndroidStudioProjects/t527_vad_service | **Service 변환 앱 (현재 개발 중)** |
| `bestin_test` | AndroidStudioProjects/bestin_test | 베스틴 테스트 앱 |
