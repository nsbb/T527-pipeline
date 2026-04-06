# 월패드 안드로이드 앱

월패드(IHN-1300D, Android 13) 탑재용 음성 AI 서비스 앱.

## 앱 목록

| 앱 | 패키지 | 설명 |
|---|--------|------|
| **t527_smart_service** | `com.t527.smart_service` | Kotlin v1: 모듈 분리 + JSON config + EMA/NofM 후처리 + NLU |
| **t527_smart_v2** | `com.t527.smart_v2` | v1 + ParameterExtractor + MP3 응답 + config reload |

백업 (AndroidStudioProjects에만, git 미포함):
| 앱 | 패키지 | 설명 |
|---|--------|------|
| `t527_vad_service_backup_20260403` | `com.t527.vad_service` | Java 안정 버전. VT+VAD+STT만 (NLU 없음). 월패드 동작 확인 |

---

## 파이프라인

```
"하이 원더" → [BCResNet] → [EMA+NofM] → flush → [Silero VAD] → [Conformer CTC] → [KoELECTRA] → overlay + MP3
         VT (30ms)                    (1000ms)  (발화+800ms)   STT (280ms)      NLU (80ms)
```

## 지연 시간 (실측, 월패드 IHN-1300D)

### 전체 파이프라인 (하이원더 ~ overlay+MP3)

| 구간 | 시간 | 비고 |
|------|------|------|
| VT (mel + NPU + EMA/NofM) | ~30ms | BCResNet 229KB, NPU uint8 |
| flush (wakeword 꼬리 제거) | 1,000ms | 설정 가능 (config.json) |
| VAD 수집 (발화 + 무음 대기) | ~2,000ms | 발화 길이 + silence timeout 800ms |
| STT mel 전처리 | ~30ms | C/KissFFT, 80 mel bins |
| STT NPU 추론 | ~255ms | Conformer CTC 102MB, 1 chunk |
| NLU (KoELECTRA CPU) | ~80ms | FP32 ONNX 36MB, 48 intents |
| **"하이 원더" ~ overlay** | **~3.4초** | |

### 명령 발화 ~ overlay (하이원더 이후)

| 구간 | 시간 |
|------|------|
| VAD 무음 대기 | 800ms |
| STT mel + NPU | ~285ms |
| NLU | ~80ms |
| **발화 종료 ~ overlay** | **~1.2초** |

### 실제 로그 (2026-04-06, 월패드)

```
09:05:56.175 WAKEWORD! prob=0.60 (29ms)
  ↓ flush 1000ms + VAD 수집 1730ms
09:05:59.955 VAD: speech 1.73s (27648 samples)
  ↓ STT mel 35ms + NPU 254ms
09:06:00.247 STT: [에어컨 켜 줘] (mel=35ms, npu=254ms, 1 chunks)
  ↓ NLU 96ms
09:06:00.344 NLU: [에어컨 켜 줘] → ac_control_on (96ms)
```

### 모델별 성능

| 모델 | 크기 | 런타임 | 추론 시간 | 용도 |
|------|------|--------|----------|------|
| BCResNet-t2 | 229KB NB | NPU uint8 | 8ms | Wakeword |
| Silero VAD v5 | 2.3MB ONNX | CPU | 0.05ms | 음성 구간 탐지 |
| Conformer CTC | 102MB NB | NPU uint8 | 250ms/chunk | 한국어 STT |
| KoELECTRA-Small | 36MB ONNX | CPU FP32 | 80ms | 의도 분류 (48 intents) |

### 메모리

| 항목 | 크기 |
|------|------|
| APK | 160MB |
| PSS (실제 점유) | ~90MB |
| RAM 3.96GB 대비 | ~2.2% |

---

## 모델 파일 (git 미포함)

모델은 용량 문제로 git에 없음. `app/src/main/assets/models/` 에 넣어야 함:

```
models/
├── Conformer/network_binary.nb    (102MB, NPU uint8)
├── Conformer/vocab_correct.json
├── Conformer/nbg_meta.json
├── Wakeword/network_binary.nb     (229KB, NPU uint8)
├── Wakeword/nbg_meta.json
├── VAD/silero_vad.onnx            (2.3MB, ONNX)
├── KoElectra/koelectra.onnx       (36MB, ONNX)
├── KoElectra/vocab.txt
└── KoElectra/label_map.json
```

v2 추가: `assets/tts/*.mp3` (98개, 2.3MB)

모델 원본 위치: `t527-stt/conformer/`, `t527-wakeword/`

---

## 구조 (세 프로젝트 장점 통합)

| 출처 | 가져온 것 |
|------|---------|
| ai_agent | Kotlin + coroutines, 모듈 분리, data class, try-catch |
| wake_wonder | JSON config, EMA + N-of-M + cooldown 후처리 |
| vad_service | Foreground Service, BR, overlay, C JNI + NB 모델 |

---

## 알려진 문제

| 문제 | 상태 | 상세 |
|------|------|------|
| wallpadcall 마이크 점유 | 미해결 | `kr.co.icontrols.wallpadcall`이 UNPROCESSED로 상시 점유, 간헐적 silenced |
| FM1388 wakeword prob 낮음 | 미해결 | 마이크 점유 문제의 부수 효과 |
| Toast 안 됨 | 해결 | Android 13 Service 제한 → overlay 사용 |
| "어"/"음" 간투어 | 해결 | flush 1.0초 + speech-only 0.5초 필터 |

상세: `docs/bestin/PROBLEMS.md` 참고

---

## 빌드 & 테스트

각 앱 폴더의 README.md 참고.
