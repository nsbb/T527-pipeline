# 시연 Q&A 준비

## 1. 모델/아키텍처

**Q: 왜 Conformer?**
- Conformer = CNN + Transformer 결합. CNN이 로컬 패턴, Transformer가 글로벌 컨텍스트 캡처
- CTC 디코딩이라 autoregressive 모델(Whisper 등)보다 추론이 빠름
- NeMo 프레임워크에서 한국어 pre-trained 모델 사용 가능 (SungBeom/stt_kr_conformer_ctc_medium)

**Q: 다른 모델은?**

| 모델 | 시도 결과 |
|------|---------|
| Conformer CTC | ✅ 채택. CER 8.86%, 250ms/chunk |
| Wav2Vec2 | △ 영어 CER 17.5%, 한국어 fine-tune 성공했으나 Conformer보다 성능 낮음 |
| Zipformer | ✗ 양자화 실패. 모든 방식 CER 100% |
| DeepSpeech2 | ✗ 오래된 아키텍처, 정확도 낮음 |
| Whisper | ✗ autoregressive라 추론 느림, NPU 변환 어려움 |

**Q: 102MB 큰 거 아닌가?**
- 원본 FP32: ~490MB → uint8 양자화 후 102MB (약 5배 압축)
- T527 NPU NB 크기 제한 ~120MB. 102MB는 한계 내
- 더 작은 모델(CitriNet 62MB)도 있으나 정확도 낮음

**Q: Wakeword는?**
- BCResNet-t2: 229KB. 경량 CNN으로 "하이 원더" 2클래스 분류
- NPU 추론 8ms, mel 36ms → 총 44ms
- Recall 94.3% (threshold 0.40)

---

## 2. 양자화

**Q: PTQ vs QAT 차이?**

| 방식 | avg CER | 설명 |
|------|---------|------|
| FP32 (원본) | 6.03% | 서버 추론 기준 |
| PTQ uint8 | 16.44% | Post-Training Quantization. 양자화 손실 큼 |
| **QAT uint8 (1M best)** | **8.86%** | Quantization-Aware Training. 18,368샘플 전체 평균 |

- PTQ → QAT로 CER 16.44% → 8.86% (7.6%p 개선)
- QAT가 양자화 손실을 크게 복구

**Q: QAT 어떻게 했나?**
- AIHub 한국어 음성 데이터 1M utterances
- NeMo 프레임워크에서 fake quantization으로 fine-tuning
- margin 0.3, 1 epoch
- 핵심 발견: 데이터 다양성 > 반복 학습 (1M 1epoch > 100K 10epoch)

**Q: Calibration은?**
- AIHub 데이터에서 100개 샘플 선정
- 개수가 소스보다 중요 (aihub100 > real100, mix50)
- asymmetric_affine uint8 + KL divergence

**Q: int16은?**
- int16 DFP는 uint8 AA보다 오히려 나쁨 (CER 330%)
- T527 NPU에서 int16 지원이 불완전

---

## 3. 성능 수치

**Q: 정확도?**

| 평가셋 | FP32 | QAT uint8 (1M best) | 양자화 열화 |
|--------|------|-----------|----------|
| 전체 18,368개 | 7.49% | 8.86% | +1.37%p |

**Q: 지연 시간?**

| 구간 | 시간 |
|------|------|
| VT (wakeword) | ~30ms |
| VAD 수집 | 발화 길이에 따라 유동적 (3초 말하면 3초, 5초 말하면 5초) |
| STT mel | ~30ms |
| STT NPU | ~250ms (3초 음성, RTF: 0.083) |

**Q: 메모리?**

| 항목 | 크기 |
|------|------|
| 모델 (Conformer 102MB + Wakeword 229KB) | 102MB |
| 앱 실제 점유 (PSS) | 90MB |
| RAM 3.96GB 대비 | 2.2% |

---

## 4. NPU

**Q: CPU 대비 NPU?**
- Conformer: NPU 250ms vs CPU 예상 1~2초 (4~8배 빠름)
- Wakeword: NPU 8ms (실시간 가능)
- T527 NPU: VIP9000NANOSI_PLUS, 546/696MHz

**Q: NPU 제한?**
- NB 크기 ~120MB 제한 (그 이상 status=-1)
- uint8/int8 asymmetric_affine만 실용적
- 일부 연산자 미지원 (Zipformer의 특정 op → 변환 실패)
- Silero VAD는 ONNX → NB 변환 불가 (CPU ONNX Runtime 사용)

**Q: 양자화 도구?**
- Acuity Toolkit 6.12 (VeriSilicon)
- import ONNX → quantize uint8 → export NB
- VivanteIDE 5.7.2로 NB 컴파일

---

## 5. 파이프라인

**Q: VT→VAD→STT 순서인 이유?**
1. **VT (상시)**: 저전력. 229KB 모델로 0.5초마다 "하이 원더" 체크. CPU ~9%
2. **VAD (감지 후)**: 음성 구간만 정확히 잘라서 STT에 전달. 불필요한 STT 추론 방지
3. **STT (VAD 후)**: 250ms/chunk. VAD 없으면 무음도 추론해서 낭비

→ 항상 STT를 돌리면 전력/리소스 낭비. VT→VAD→STT가 효율적

**Q: VAD 없이 바로 STT?**
- 가능하지만 녹음 길이를 고정해야 함 (3초 등)
- 짧은 명령이 잘리거나 긴 명령이 끊김
- VAD로 가변 길이 녹음이 더 좋음

**Q: 실시간 스트리밍 STT?**
- 현재는 VAD로 발화 끝나면 일괄 추론 (batch)
- 슬라이딩 윈도우로 청크 단위 추론은 하고 있음 (3초 윈도우, 2.5초 stride)
- 진정한 스트리밍(partial result)은 미구현

---

## 6. 어려웠던 점

**Q: 가장 어려웠던 부분?**
1. **NeMo → ONNX → NB 변환**: NeMo Docker 버전, Acuity LD_LIBRARY_PATH, VivanteIDE 환경변수 등 6개 이상의 함정. 반복 실패 후 해결
2. **양자화 정확도**: PTQ CER 16% → QAT로 8.86%까지 줄이는 과정. Calibration 데이터 선정이 핵심
3. **Wakeword mel scale 불일치**: BCResNet 학습 때 HTK mel → 양자화 때 Slaney mel 사용해서 recall 0.36% → HTK로 재생성 후 94.3%

**Q: 어떤 환경에서 안 되나?**
- FM1388 디지털 마이크 특성상 USB 마이크보다 wakeword prob 낮음
- 마이크 점유 충돌 (wallpadcall 앱이 동시 사용 시)
- 새 소프트웨어에서 마이크 설정 변경 필요

---

## 7. 향후 계획

**Q: NLU(의도 분류)?**
- KoELECTRA-Small ONNX (36MB)로 48개 intent 분류 구현 완료
- STT → "에어컨 켜줘" → NLU → `ac_control_on` → "에어컨을 켜드릴게요"
- 추론 ~80ms (CPU ONNX Runtime)
- 파라미터 추출(거실, 23도 등) regex 기반 구현

**Q: 실제 기기 제어?**
- iCMD 프로토콜로 월패드 기기 제어 가능 (구조 설계 완료)
- 실제 연동은 베스틴 협조 필요

**Q: 모델 경량화?**
- Conformer 102MB → 더 작은 아키텍처 탐색 가능
- 현재 122.5M 파라미터 → small 버전으로 줄일 수 있으나 정확도 trade-off

**Q: 다국어?**
- 현재 한국어 전용 (BPE vocab 2049)
- 영어는 Wav2Vec2로 별도 구현 가능 (CER 17.5%)
- 다국어 모델(Whisper 등)은 NPU 변환 어려움

---

## 8. 시연 시 주의사항

- 데브킷(USB 마이크)에서 시연 → FM1388 문제 없음
- "하이 원더" → 2~3초 후 명령 → overlay에 결과 표시
- 조용한 환경에서 1~2m 거리 권장
- threshold 0.40이라 가끔 한 번에 안 잡힐 수 있음 → 다시 시도
