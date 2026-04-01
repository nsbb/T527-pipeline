# Silero VAD NB 변환 시도 및 결과

**날짜:** 2026-04-01
**결론:** NB 변환 불가 → ONNX Runtime CPU 사용 (0.05ms, 문제 없음)

---

## 1. 모델 정보

| 항목 | 값 |
|------|-----|
| 모델 | Silero VAD v5 |
| 파일 | `silero_vad.onnx` (2.3MB) |
| 입력 | audio `[1, 512]` float32 (32ms @ 16kHz) + state `[2, 1, 128]` float32 + sr `int64` |
| 출력 | probability `[1, 1]` float32 + stateN `[2, 1, 128]` float32 |
| 구조 | STFT → Conv encoder (4 blocks) → LSTM decoder → Sigmoid |
| 특징 | **stateful** (LSTM hidden state 전달) |

---

## 2. NB 변환이 안 되는 이유

### 2-1. If op (조건 분기)

ONNX 최상위에 `If` op이 있음 — `sr == 16000`이면 16kHz 모델, 아니면 8kHz 모델 실행.

```
Constant(16000) → Equal(sr, 16000) → If
                                       ├── then_branch (113 nodes, 16kHz)
                                       └── else_branch (113 nodes, 8kHz)
```

then_branch 내부에도 **If 3개 더 중첩** (LSTM state 초기화, context 체크 등).

**Acuity는 If op 미지원** → import 실패.

### 2-2. 서브그래프 추출 시도

sr=16000 고정이니까 then_branch만 추출하여 flat ONNX 만들기 시도.

**시도 1: 재귀적 If 제거**
- then_branch에서 모든 If를 재귀적으로 풀어 225개 flat 노드 생성
- If 제거 성공, LSTM 1개 + Conv + Relu + Sigmoid 구조
- **문제:** LSTM 입력 rank이 안 맞음 (rank 3 필요한데 서브그래프 Unsqueeze 체인 깨짐)
- `ONNX Runtime: LSTM First input tensor must have rank 3`

**시도 2: onnxsim으로 If 제거**
- `onnxsim(model, input_shapes={"input": [1,512], "state": [2,1,128]})` 시도
- If가 constant fold 안 됨 (sr이 runtime input이라서)

**시도 3: torch.jit.trace로 re-export**
- PyTorch Hub에서 JIT model 로드 → trace → ONNX export
- **문제:** 내부에 `if not len(self._context)` 동적 코드 → `torch.__not__` ONNX 미지원
- `SymbolicValueError: ONNX export does NOT support exporting bitwise Not for non-boolean input`

**시도 4: torch.onnx.export wrapper**
- VAD16k wrapper 만들어서 sr=16000 고정 forward → trace
- 동일한 `__not__` 에러

### 2-3. LSTM (Acuity 지원 여부)

Acuity 6.12는 LSTM을 지원하지만, Silero VAD의 LSTM은 **stateful** (매 호출마다 state 전달):
- 입력: audio + h_state + c_state
- 출력: prob + h_state_new + c_state_new

NPU에서 stateful LSTM을 돌리려면 **매 프레임마다 state를 CPU↔NPU 전달**해야 하는데, 32ms마다 이러면 오버헤드가 추론 시간보다 클 수 있음.

### 2-4. Dynamic shape

모든 입력/출력에 dynamic dimension 있음:
- input: `[None, None]`
- state: `[2, None, 128]`
- output: `[None, 1]`
- stateN: `[None, None, None]`

고정 shape로 바꿔도 내부 Shape/Size/Gather op이 동적 계산.

---

## 3. ONNX Runtime CPU 성능

NB 변환 대신 ONNX Runtime CPU로 실행.

| 항목 | 값 |
|------|-----|
| 추론 시간 | **0.05ms** (avg, 100회) |
| 최대 | 0.10ms |
| 입력 오디오 | 32ms (512 samples) |
| CPU 사용률 | **0.2%** |

**0.05ms면 NB 변환 필요 없음.**
- BCResNet NPU: 8ms (VAD보다 160배 느림)
- Conformer NPU: 250ms (VAD보다 5000배 느림)
- VAD가 병목이 될 수 없음

---

## 4. 파이프라인 구성

```
VT:  BCResNet NPU (8ms)    ← NB (229KB)
VAD: Silero CPU (0.05ms)   ← ONNX Runtime (2.3MB)
STT: Conformer NPU (250ms) ← NB (102MB)
```

VAD만 CPU, 나머지 NPU. 성능 영향 없음.

---

## 5. 향후 NB 변환 가능 조건

1. **Silero VAD가 If 없는 ONNX를 공식 제공**하면 시도 가능
2. **다른 VAD 모델** (WebRTC VAD 등) 사용 — 규칙 기반이라 NPU 불필요
3. **Acuity가 If/LSTM 지원 개선**하면 재시도

현재로서는 ONNX Runtime CPU가 최적 선택.
