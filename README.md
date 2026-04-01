# T527 온디바이스 음성 AI 파이프라인

T527 Vivante NPU에서 **서버 없이** 동작하는 음성 AI 파이프라인.

```
"하이 원더" → [BCResNet 7ms] → 3초 녹음 → [Conformer 250ms] → 한국어 텍스트
```

---

## 성능

| 단계 | 모델 | NPU 추론 | 전체 지연 |
|------|------|---------|---------|
| Wakeword (VT) | BCResNet-t2 uint8 | **8ms** | mel 36ms + npu 8ms = **44ms** |
| STT | Conformer CTC uint8 | **250ms** | mel 70ms + npu 250ms = **320ms** |
| **파이프라인 전체** | | | "하이원더" → 텍스트 **~3.5초** |

### STT 정확도

| | FP32 (서버) | uint8 (T527 NPU) | 양자화 손실 |
|---|-----------|-----------------|----------|
| avg_real (자체 368개) | 6.03% | **7.24%** | +1.21%p |
| QAT가 양자화 손실 88% 제거 | | PTQ 16.44% → QAT 7.24% | |

### Wakeword 정확도

| | T527 uint8 | RK3588 FP16 |
|---|-----------|-------------|
| Recall (th=0.40) | **94.3%** | 93.95% |
| FP | 6 | 8 |
| 추론 시간 | **8ms** | 8ms |

---

## 앱

| 앱 | 패키지명 | 설명 |
|---|--------|------|
| **Conformer STT** | com.t527.conformer_stt | 버튼 클릭 → 3초 녹음 → STT |
| **T527 Pipeline** | com.t527.pipeline | "하이원더" → 자동 녹음 → STT |

### 앱 구조

```
앱
├── assets/models/
│   ├── Conformer/          (102MB NB + vocab + meta)
│   └── Wakeword/           (229KB NB + meta)
├── jni/conformer/
│   ├── awconformersdk.c    (Conformer + Wakeword NPU JNI)
│   ├── conformer_mel.c     (Conformer mel: Slaney, C/KissFFT)
│   └── wakeword_mel.c      (Wakeword mel: HTK, C/KissFFT)
├── PipelineActivity.java   (VT → STT 파이프라인)
└── ConformerMicActivity.java (버튼 STT)
```

---

## 모델

### Conformer CTC (STT)

| 항목 | 값 |
|------|-----|
| 원본 | SungBeom/stt_kr_conformer_ctc_medium (HuggingFace) |
| 파라미터 | 122.5M (18 layers, d_model=512) |
| QAT | AIHub 95K (84hr), 10 epoch, margin 0.3 |
| 양자화 | uint8 asymmetric_affine KL, **calib 100개 (aihub)** |
| NB 크기 | **102MB** |
| 입력 | [1, 80, 301] uint8 mel (3초) |
| 출력 | [1, 76, 2049] uint8 logits |
| 추론 | **250ms/chunk**, RTF 0.10 |

### BCResNet-t2 (Wakeword)

| 항목 | 값 |
|------|-----|
| 원본 | BCResNet-t2-Focal-ep110.onnx |
| 양자화 | uint8 asymmetric_affine KL, **HTK mel calib 100개** |
| NB 크기 | **229KB** |
| 입력 | [1, 1, 40, 151] uint8 LogMel (1.5초) |
| 출력 | [1, 2] uint8 (non-wake, wake) |
| 추론 | **8ms** |
| Threshold | 0.40 |

---

## 해결한 문제들

### 1. NeMo → ONNX → NB 변환 실패

**증상:** `IndexError: list index out of range` (Acuity import)

**원인 2가지:**
1. 로컬 NeMo(pip)로 ONNX export → NeMo Docker(23.06) 사용 필수
2. Docker에서 Acuity 실행 시 `LD_LIBRARY_PATH` 미설정

**해결:** NeMo Docker export + Docker 내 Acuity 6.12 binary + LD_LIBRARY_PATH 설정

### 2. NB export 실패 (`create network 0 failed`)

**원인:** optimize 플래그 잘못됨

**해결:** `VIP9000NANOSI_PLUS_PID0X10000016` (PID 포함 필수)

### 3. NB export 실패 (`Fatal model compilation error: 512`)

**원인:** VivanteIDE 환경변수 미설정

**해결:** `REAL_GCC`, `VIVANTE_VIP_HOME`, `VIVANTE_SDK_DIR`, `EXTRALFLAGS` 전부 설정

### 4. Calibration 데이터 부족으로 QAT 모델 성능 저하

**증상:** QAT full (4350hr) 모델이 100k보다 나쁨

**원인:** calib 10개로는 양자화 range가 부정확

**해결:** calib 100개로 늘림. **calib 소스보다 개수가 중요** (aihub/real/mix 간 차이 < 개수 차이)

### 5. Wakeword uint8 양자화 recall 0.36%

**증상:** BCResNet uint8로 양자화하니 recall 거의 0%

**원인:** Calibration 데이터의 mel scale이 모델 학습 때와 다름 (Slaney vs HTK)

**해결:** 모델 학습 시 사용한 **HTK mel scale**로 calib 데이터 재생성 → recall 94.3%

### 6. vpm_run vs 앱 결과 불일치

**증상:** 동시에 vpm_run 테스트 + 앱 테스트 돌리면 결과 다름

**원인:** 같은 `/data/local/tmp/kr_conf_sb/chunk.dat` 파일을 동시에 쓰면서 충돌

**해결:** 동시 실행 금지. 검증: 단독 실행 시 30/30 완전 일치 확인

### 7. Pipeline 앱 wakeword 감지 느림

**증상:** "하이원더" 말하고 수 초 후에야 감지

**원인:** Java에서 DFT 계산 (512-point × 151 frames = 77,312번 삼각함수)

**해결:** C/JNI의 KissFFT로 교체 → mel 36ms + NPU 8ms = **44ms**

### 8. "하이원더" 한 번 말했는데 여러 번 감지

**원인:** Ring buffer에 이전 음성 잔여

**해결:** STT 시작 시 ring buffer 초기화 + 500ms cooldown

### 9. USB adb 연결 불가 (공장 초기화 후에도)

**증상:** Settings → Connected Device → This device 누르면 프리징

**상태:** 미해결. 이더넷 + 수동 IP로 우회 가능

### 10. 끝에 "음" 간투어 붙는 문제

**증상:** 모든 인식 결과 끝에 "음" 추가

**해결:** CTC 디코더에 후처리 추가 — 끝이 " 음"이면 제거

---

## 실험 결과 요약

### QAT 모델 비교 (자체 데이터 368개)

| 모델 | 학습 데이터 | calib | avg CER |
|------|---------|-------|---------|
| PTQ (baseline) | - | 10개 | 16.44% |
| **QAT 100k** | **AIHub 84hr** | **aihub 100개** | **7.24%** |
| QAT 100k | AIHub 84hr | mix 100개 | 8.32% |
| QAT 100k | AIHub 84hr | real 100개 | 15.0% |
| QAT full | AIHub 4356hr | aihub 100개 | 14.81% |
| QAT KD m0.5 | AIHub 84hr | aihub 100개 | 8.40% |

### Wakeword 양자화 비교 (1,897개)

| 양자화 | calib mel | Recall (th=0.55) | Recall (th=0.40) |
|-------|----------|-----------------|-----------------|
| uint8 Slaney | Slaney (잘못됨) | 0.36% | 42.7% |
| int16 DFP | Slaney | 33.81% | - |
| **uint8 HTK** | **HTK (정확)** | **78.6%** | **94.3%** |

---

## 파일 구조

```
t527-pipeline/
├── README.md
├── apps/
│   ├── conformer_stt/     → AndroidStudioProjects/conformer_stt
│   └── t527_pipeline/     → AndroidStudioProjects/t527_pipeline
├── models/
│   ├── conformer/         → t527-stt repo
│   └── wakeword/          → t527-wakeword repo
└── docs/
    ├── NEMO_TO_NB_CONVERSION_GUIDE.md
    ├── mel_preprocessing.yaml
    └── APP_VS_VPMRUN_VERIFICATION.md
```

---

## 관련 레포

| 레포 | 내용 |
|------|------|
| [nsbb/T527-STT](https://github.com/nsbb/T527-STT) | Conformer CTC 양자화 + 18k 테스트 + QAT |
| [nsbb/T527-wakeword](https://github.com/nsbb/T527-wakeword) | BCResNet wakeword T527 포팅 |
