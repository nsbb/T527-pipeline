# t527_vt_config_test

Wakeword(VT) 성능 테스트용 앱. "하이 원더" 감지 횟수를 카운팅하고, config 파일로 파라미터를 빌드 없이 변경 가능.

## 기능

- **VT only** — Wakeword 감지만 수행 (VAD/STT 없음)
- **카운팅** — 시스템 시작 후 총 감지 횟수 로그 (`[Wake #N]`)
- **JSON config** — threshold 등 파라미터를 빌드 없이 변경
- **Foreground Service** — 백그라운드 상시 실행

---

## 설치

```bash
ADB="adb"  # 또는 Windows: ADB="/mnt/c/Users/nsbb/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="00f75c0572408721ed9"  # 월패드 디바이스 ID

# 설치
$ADB -s $DEV install -r app-debug.apk

# 권한
$ADB -s $DEV shell pm grant com.t527.vad_service android.permission.RECORD_AUDIO
$ADB -s $DEV shell appops set com.t527.vad_service SYSTEM_ALERT_WINDOW allow

# 서비스 시작
$ADB -s $DEV shell am start -n com.t527.vad_service/com.t527.wav2vecdemo.VadPipelineActivity
```

---

## 로그 확인

```bash
# 실시간 로그
$ADB -s $DEV logcat -s VadPipelineService

# Wake 카운트만 보기
$ADB -s $DEV logcat -s VadPipelineService | grep "Wake #"
```

### 로그 출력 예시

```
VadPipelineService: Config: wk_th=0.40, vad_th=0.50, silence=800ms, flush=1000ms, min_speech=0.5s
VadPipelineService: Wakeword: true (VT-only mode)
VadPipelineService: Pipeline loop started
VadPipelineService: [Wake #1] Wake detected (count=1) — prob=0.52 (mel=27ms, npu=5ms) — STT save OFF, staying in wake mode
VadPipelineService: [Wake #2] Wake detected (count=2) — prob=0.51 (mel=22ms, npu=5ms) — STT save OFF, staying in wake mode
VadPipelineService: [Wake #3] Wake detected (count=3) — prob=0.48 (mel=18ms, npu=4ms) — STT save OFF, staying in wake mode
```

---

## Config 설정

### config.json 위치

| 경로 | 우선순위 |
|------|---------|
| `/sdcard/t527_vad_service/config.json` | 1순위 (이 파일 있으면 사용) |
| 앱 내장 `assets/config.json` | 2순위 (fallback) |

### config.json 내용

```json
{
  "wakeword": {
    "threshold": 0.40
  },
  "vad": {
    "threshold": 0.5,
    "silence_timeout_ms": 800,
    "max_speech_ms": 10000,
    "min_speech_seconds": 0.5
  },
  "audio": {
    "sample_rate_mic": 48000,
    "sample_rate_model": 16000,
    "flush_after_wake_ms": 1000
  },
  "stt": {
    "mel_scale": 0.025880949571728706,
    "mel_zp": 84,
    "seq_out": 76,
    "stride_out": 62
  }
}
```

### VT 테스트에서 주로 바꿀 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `wakeword.threshold` | 0.40 | 감지 확률 임계값 (0.0~1.0). 낮추면 민감, 높이면 엄격 |

---

## Config 변경 방법 (빌드 없이)

### Step 1: config.json 수정

PC에서 config.json 수정. 예: threshold를 0.35로 변경

### Step 2: 월패드에 push

```bash
$ADB -s $DEV push config.json /sdcard/t527_vad_service/config.json
```

### Step 3: 실시간 반영

```bash
$ADB -s $DEV shell am start-foreground-service \
  -n com.t527.vad_service/com.t527.wav2vecdemo.VadPipelineService \
  -a com.t527.vad_pipeline.ACTION_RELOAD_CONFIG
```

### 반영 시점 정리

| 방법 | 반영 |
|------|------|
| push만 | 반영 안 됨 |
| push + reload 명령 | **실시간 반영** (서비스 재시작 없이) |
| 앱 재시작 | 자동 반영 |
| 디바이스 재부팅 | 자동 반영 |

---

## 테스트 시나리오

### 1. 기본 검출률 테스트
1. 서비스 시작
2. 일정 거리에서 "하이 원더" 10회 반복
3. `grep "Wake #"` 로 카운트 확인
4. 검출률 = count / 10

### 2. threshold 비교
1. threshold=0.40 → 10회 → 검출률 기록
2. config.json 수정 → threshold=0.35 → push → reload
3. 같은 조건 10회 → 검출률 비교

### 3. FP32 원본 vs uint8 비교
1. uint8 NB 모델로 N회 → 검출률 기록
2. FP32 모델로 교체 (`network_binary.nb` 교체)
3. 같은 조건 N회 → 검출률 비교

---

## 모델

| 모델 | 크기 | 설명 |
|------|------|------|
| BCResNet-t2 | 229KB | Wakeword "하이 원더" NPU uint8 |

VT-only 모드이므로 Wakeword 모델만 로드합니다 (Conformer, VAD 미사용).
