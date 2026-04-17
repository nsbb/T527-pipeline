# wake_wonder_onnx buffer-clear 방식 → VadPipelineService 적용 실패 분석

**날짜:** 2026-04-17
**시도 프로젝트:** `t527_vad_service_v2`
**참조:** `wake_wonder_onnx/HANDOFF_4.1.md`

---

## 배경

wake_wonder_onnx 프로젝트에서 wakeword("하이 원더") 감지 후 STT 전환 시 잔여음("더") 문제를 해결한 방식:

```
WAKE MODE: VAD 없이 항상 streaming → wakeword 감지
    ↓ (감지!)
buffer.clear()  ← 앱 레벨 버퍼 즉시 클리어
    ↓
STT MODE: VAD가 새 음성 감지할 때까지 대기 → 녹음
```

핵심: **flush(고정 시간 오디오 버리기) 없이**, 앱 레벨 오디오 버퍼를 `clear()` 하고 VAD가 새 발화를 감지할 때까지 기다림.

---

## 적용 시도

### 시도 1: `READ_NON_BLOCKING` 단일 호출

```java
int staleSize = recorder.getBufferSizeInFrames();
short[] discard = new short[Math.min(staleSize, SR_MIC)];
recorder.read(discard, 0, discard.length, AudioRecord.READ_NON_BLOCKING);
```

**결과:** "하이 원더" 오디오가 그대로 VAD에 들어감. `speech 3.04s, 2 chunks` → STT "에어" (garbage)

**원인:** `READ_NON_BLOCKING` 한 번 호출로 최대 48000 샘플(1초)만 읽음. AudioRecord 내부 버퍼에 1.5초 이상 쌓여있으면 나머지가 남음.

### 시도 2: `READ_NON_BLOCKING` 반복 drain

```java
short[] drain = new short[4800];
while (recorder.read(drain, 0, drain.length, AudioRecord.READ_NON_BLOCKING) > 0) {}
```

**결과:** wakeword 자체가 감지 안 됨. 오디오 레벨은 정상(max=5478)이나 wakeword prob 0.10~0.13 (threshold 0.40).

**원인:** 미확인. drain 루프가 AudioRecord의 recording 스트림에 영향을 줬거나, 앱 재설치 시 NPU 모델 상태가 꼬였을 가능성. v1으로 복원 후 정상 동작 확인 필요.

---

## 근본적 구조 차이

| | wake_wonder_onnx | VadPipelineService |
|---|---|---|
| 오디오 소스 | AudioRecord → 자체 PCM 워커 → **앱 레벨 deque** | **AudioRecord 내부 버퍼** 직접 read |
| 버퍼 클리어 | `state.audio_buffer.clear()` (O(1), 즉시) | `recorder.read()` 반복 호출 필요 (시간 소요) |
| wakeword 처리 | PCM 워커가 프레임 단위로 버퍼에 쌓고 inference 워커가 읽음 | pipelineLoop에서 0.5초 chunk 읽고 직접 mel+NPU 호출 |
| STT 전환 | 플래그 설정 → PCM 워커가 다음 프레임부터 VAD 적용 | runStt() 함수 호출 → 새로운 VAD 루프 시작 |

**핵심:** wake_wonder_onnx는 오디오를 앱 레벨 버퍼(deque)에 복사해서 관리하므로 `clear()` 한 줄로 즉시 비울 수 있음. VadPipelineService는 AudioRecord의 내부 버퍼에서 직접 `read()`하는 구조라서, 버퍼를 비우려면 반드시 데이터를 읽어서 버려야 함 = **flush**.

---

## 결론

**VadPipelineService 구조에서는 flush가 buffer clear와 동치.** wake_wonder_onnx의 "flush 없는 buffer clear" 방식을 적용하려면 오디오 파이프라인 구조 자체를 변경해야 함:

1. AudioRecord → 별도 스레드에서 프레임 단위로 읽기 → 앱 레벨 ring buffer에 저장
2. wakeword 체크와 STT 모두 앱 레벨 버퍼에서 읽기
3. wakeword 감지 시 `buffer.clear()` → VAD 재시작

이는 VadPipelineService의 전면 재작성에 해당하며, 현 단계에서는 **flush 0.3초 + trailing silence 제거 + speech-only 0.5초 필터** 조합이 실용적.

---

## 현재 최선 설정 (t527_vad_service_with_server)

| 파라미터 | 값 | 근거 |
|---------|-----|------|
| flush | 0.3초 | 1.0초는 명령어 잘림, 0.3초에서 대부분 정상 |
| VAD threshold | 0.5 | |
| silence timeout | 800ms | 1500ms → 2-chunk 문제 |
| speech-only 최소 | 0.5초 | 간투어("어") 필터 |
| trailing silence | 제거 | "오" 붙는 현상 방지 |

### 0.3초 flush 테스트 결과 (2026-04-17)

| STT 결과 | 판정 |
|----------|------|
| "엘리베이터 켜 줘" | ✓ |
| "보일러 켜 줘" | ✓ |
| "오늘 날씨 어때" | ✓ |
| "보일러 켜줘" | ✓ |
| "엘리베이터 불러 줘" | ✓ |
| "리베이터 불러줘" | 앞 "엘" 잘림 (빠르게 말한 경우) |
| "엘리베이터 켜줘 오" | 뒤 "오" 붙음 → trailing silence 제거로 해결 |
