# Wakeword 꼬리음 문제: wake_wonder_onnx 분석 및 VadPipelineService 해결

**날짜:** 2026-04-17
**관련 프로젝트:** `wake_wonder_onnx`, `t527_vad_service_with_server`, `t527_vad_service_v2`

---

## 1. 문제

Wakeword("하이 원더") 감지 후 STT 실행 시 세 가지 증상 발생:

| 증상 | 예시 | 원인 |
|------|------|------|
| 명령어 앞부분 잘림 | "엘리베이터 불러줘" → "리베이터 불러줘" | flush가 명령어까지 삼킴 |
| 뒤에 잡음 글자 붙음 | "엘리베이터 켜줘" → "엘리베이터 켜줘 오" | VAD silence 구간이 STT에 포함 |
| 한 글자만 인식 | "보일러 켜줘" → "어" | flush가 명령어 대부분 삼기고 잔향만 남음 |

---

## 2. wake_wonder_onnx 앱 분석

(`C:\Users\nsbb\AndroidStudioProjects\wake_wonder_onnx\HANDOFF_4.1.md` 참조)

### 아키텍처

```
AudioRecord (48kHz)
    ↓
PCM 워커 스레드 (10ms 프레임 단위)
    ↓
앱 레벨 오디오 버퍼 (std::deque<int16_t>)
    ↓                          ↓
WAKE MODE                    STT MODE
(VAD 없이 streaming)         (VAD로 발화 감지)
```

### wakeword 꼬리 해결 방식

```
1. wakeword 감지!
2. state.audio_buffer.clear()     ← deque 즉시 클리어 (O(1))
3. state.recording = false        ← 녹음 리셋
4. state.silence_count = 0        ← silence 카운터 리셋
5. VAD가 새 음성 감지할 때까지 대기
6. 음성 감지 → pre-roll로 앞 컨텍스트 포함하여 녹음 시작
```

**핵심:** flush(고정 시간 오디오 버리기)를 하지 않음. 앱 레벨 버퍼를 `clear()` 하면 "하이 원더" 오디오가 즉시 제거되고, VAD가 새 발화를 감지할 때까지 자동 대기.

### 추가로 해결한 버그들 (HANDOFF_3, HANDOFF_4)

- `GetState()`가 매 호출마다 `max_silence_frames`를 wake 모드 값(20)으로 덮어쓰는 버그 → STT 모드의 3초 무음 임계값이 유지 불가 → 멤버 초기값으로 변경, `GetState()` 내 store 제거
- `clear_buffer_for_stt`와 `max_silence_frames` 설정 사이 race condition → 설정 순서 교정
- 첫 프레임 이중 삽입 버그 → `return` 추가

---

## 3. VadPipelineService에 적용 시도 및 실패

### 구조적 차이 (적용 불가 원인)

| | wake_wonder_onnx | VadPipelineService |
|---|---|---|
| 오디오 버퍼 | **앱 레벨 deque** (자체 관리) | **AudioRecord 내부 버퍼** (OS 레벨) |
| 클리어 방법 | `buffer.clear()` — 즉시, 0ms | `recorder.read()` 반복 — 읽어서 버려야 함 |
| 오디오 읽기 | PCM 워커가 10ms 프레임 단위로 deque에 복사 | pipelineLoop에서 0.5초 chunk 직접 read |
| STT 전환 | 플래그 설정 → PCM 워커가 VAD 적용 시작 | `runStt()` 호출 → 새 VAD 루프 진입 |

**VadPipelineService는 AudioRecord에서 직접 `read()`하는 구조.** AudioRecord의 내부 버퍼를 비우려면 데이터를 읽어서 버려야 함. 즉 flush = buffer clear.

### 시도 1: `READ_NON_BLOCKING` 단일 호출

```java
short[] discard = new short[Math.min(staleSize, SR_MIC)]; // max 48000(1초)
recorder.read(discard, 0, discard.length, AudioRecord.READ_NON_BLOCKING);
```

**결과:** "하이 원더" 1.5초 오디오가 그대로 VAD에 진입. `speech 3.04s, 2 chunks` → STT "에어" (garbage)
**원인:** 1회 호출로 최대 1초만 읽음. 버퍼에 1.5초+ 남아있으면 미제거.

### 시도 2: `READ_NON_BLOCKING` 반복 drain

```java
short[] drain = new short[4800];
while (recorder.read(drain, 0, drain.length, AudioRecord.READ_NON_BLOCKING) > 0) {}
```

**결과:** wakeword 감지 자체가 안 됨. prob 0.10~0.13 (threshold 0.40)
**원인:** drain 루프가 AudioRecord 스트림 상태에 영향. 앱 재설치 과정에서 NPU 모델 또는 AudioRecord 세션 상태 불일치 발생 추정.

### 결론

wake_wonder_onnx의 buffer-clear 방식은 **앱 레벨 버퍼(deque) 구조**에서만 동작.
VadPipelineService의 **AudioRecord 직접 read 구조**에서는 적용 불가.
적용하려면 오디오 파이프라인을 앱 레벨 ring buffer 구조로 전면 재작성해야 함.

---

## 4. 최종 해결: flush 0.3초 + trailing silence 제거

wake_wonder_onnx 방식 대신, 기존 flush 기반에서 두 가지 개선으로 해결:

### 개선 1: flush 1.0초 → 0.3초

**원인:** flush 1.0초가 명령어 앞부분까지 삼킴.
사용자가 "하이 원더" 직후 바로 명령어를 말하면 wakeword 감지 지연(~0.5초) + flush 1.0초 = 약 1.5초 손실.
"엘리베이터 켜줘"(~1.5초)가 거의 다 잘려서 "줘" → "어"만 남음.

**해결:** flush를 0.3초로 축소.
0.3초면 wakeword 꼬리("더")는 제거되고, 명령어 앞부분은 보존됨.

```java
// 변경 전
short[] flush = new short[SR_MIC];           // 48000 = 1.0초

// 변경 후
short[] flush = new short[SR_MIC * 3 / 10];  // 14400 = 0.3초
```

### 개선 2: trailing silence 제거

**원인:** VAD가 silence 구간도 `speechChunks`에 추가하여 STT에 포함.
명령어 끝나고 800ms silence timeout 동안의 잡음/잔향이 Conformer에서 "오" 등으로 디코딩됨.

**해결:** VAD 루프 break 후 마지막 silence 프레임을 speechChunks에서 제거.

```java
// VAD 루프 종료 후
int removeCount = Math.min(silenceFrames, speechChunks.size());
for (int i = 0; i < removeCount; i++) {
    speechChunks.remove(speechChunks.size() - 1);
}
```

### 기존 유지: speech-only 0.5초 필터

이전에 이미 적용된 필터 (PROBLEMS.md 3번 참조).
wakeword 꼬리("더")가 VAD에 잡혀도 speech-only duration이 0.13초 → 0.5초 미만으로 필터됨.

---

## 5. 테스트 결과 비교

### flush 1.0초 (원본)

| 입력 | STT 결과 | 판정 |
|------|----------|------|
| "엘리베이터 불러줘" | "어" | ✗ |
| "보일러 켜줘" | "어" | ✗ |
| 여러 명령어 | "어", "오" 반복 | ✗ |

### flush 0.3초 + trailing silence 제거 (최종)

| 입력 | STT 결과 | 판정 |
|------|----------|------|
| "엘리베이터 켜줘" | "엘리베이터 켜 줘" | ✓ |
| "보일러 켜줘" | "보일러 켜 줘" | ✓ |
| "오늘 날씨 어때" | "오늘 날씨 어때" | ✓ |
| "보일러 켜줘" | "보일러 켜줘" | ✓ |
| "엘리베이터 불러줘" | "엘리베이터 불러 줘" | ✓ |
| "엘리베이터 불러줘" (빠르게) | "리베이터 불러줘" | △ 앞 "엘" 잘림 |

대부분 정상 인식. 빠르게 말하면 앞 1음절 잘리는 경우 간헐적 발생.

---

## 6. 현재 설정값 요약

| 파라미터 | 값 | 변경 전 | 변경 이유 |
|---------|-----|---------|-----------|
| **flush** | **0.3초** | 1.0초 | 명령어 잘림 방지 |
| **trailing silence** | **제거** | 포함 | "오" 붙는 현상 방지 |
| VAD threshold | 0.5 | (동일) | |
| silence timeout | 800ms | (동일) | |
| speech-only 최소 | 0.5초 | (동일) | |
| WK threshold | 0.40 | (동일) | |
