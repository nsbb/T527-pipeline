# wake_wonder_onnx → T527 NPU 포팅 사례

**날짜:** 2026-04-17
**원본:** `C:\Users\nsbb\AndroidStudioProjects\wake_wonder_onnx\`
**포팅 결과:** `C:\Users\nsbb\AndroidStudioProjects\t527_vad_service_v2\` (실패) → `t527_vad_service_with_server\` (우회 해결)

---

## 1. 목표

wake_wonder_onnx 앱의 VT→STT 전환 방식을 T527 NPU 기반 VadPipelineService에 반영.

특히 wakeword("하이 원더") 감지 후 **꼬리음 "더"가 STT에 들어가는 문제**를 wake_wonder_onnx가 해결한 방식(buffer clear)으로 적용하려 함.

---

## 2. wake_wonder_onnx 분석

### 오디오 파이프라인

```
AudioRecord (48kHz)
    ↓
PCM 워커 스레드 (10ms 프레임 단위로 읽기)
    ↓
std::deque<int16_t> audio_buffer   ← 앱이 직접 관리하는 메모리
    ↓                                   ↓
WAKE MODE (streaming 추론)          STT MODE (VAD 녹음)
```

핵심: AudioRecord에서 읽은 오디오를 **앱 메모리(C++ deque)**에 복사. 모든 처리는 이 deque에서 수행.

### wakeword 감지 후 처리 (HANDOFF_4.1.md)

```cpp
// wakeword 감지 확정 시:
state.audio_buffer.clear();          // ① deque 즉시 클리어 (0ms)
state.recording = false;             // ② 녹음 상태 리셋
state.silence_count = 0;             // ③ silence 카운터 리셋
state.max_silence_frames = 300;      // ④ STT 모드 무음 임계값 (3초)
state.clear_buffer_for_stt = true;   // ⑤ PCM 워커에 신호

// 이후 PCM 워커:
// - VAD가 새 음성을 감지할 때까지 대기 (non-speech 프레임 자동 스킵)
// - 음성 감지 시 pre-roll 버퍼로 앞 컨텍스트 포함하여 녹음 시작
```

**flush(고정 시간 오디오 버리기)가 없음.** deque를 clear()하면 "하이 원더" 오디오가 즉시 사라지고, VAD가 새 발화만 잡음.

### 추론 엔진

- ONNX Runtime (CPU)
- wakeword: BCResNet ONNX 모델
- STT: 외부 처리 (WAV 저장)

---

## 3. 우리 환경 (VadPipelineService)

### 오디오 파이프라인

```
AudioRecord (48kHz)
    ↓
pipelineLoop()에서 직접 recorder.read()   ← AudioRecord 내부 버퍼에서 직접 읽기
    ↓
WAKE: 0.5초 chunk → mel → Wakeword NPU
STT:  VAD 프레임 → mel → Conformer NPU
```

핵심: 앱 레벨 버퍼 없음. **AudioRecord 내부 버퍼에서 직접 read()**.

### 추론 엔진

- T527 Vivante NPU (VIPLite)
- wakeword: BCResNet NB (uint8, 229KB)
- STT: Conformer CTC NB (uint8, 102MB)

---

## 4. 포팅 시도

### 시도: AudioRecord 버퍼를 wake_wonder_onnx처럼 clear

wake_wonder_onnx의 `buffer.clear()`에 해당하는 것을 AudioRecord에서 구현하려 함.

#### 시도 A: READ_NON_BLOCKING 단일 호출

```java
int staleSize = recorder.getBufferSizeInFrames();
short[] discard = new short[Math.min(staleSize, SR_MIC)];
recorder.read(discard, 0, discard.length, AudioRecord.READ_NON_BLOCKING);
```

**결과:** 실패.
- "하이 원더" 1.5초 오디오가 그대로 VAD에 진입
- VAD가 3.04초 speech로 잡음 → 2 chunk 처리 → STT "에어" (garbage)
- **원인:** 한 번 read로 최대 1초분만 읽힘. 버퍼에 1.5초+ 남아있으면 미제거.

#### 시도 B: READ_NON_BLOCKING 반복 drain

```java
short[] drain = new short[4800]; // 0.1초씩
while (recorder.read(drain, 0, drain.length, AudioRecord.READ_NON_BLOCKING) > 0) {
    // 다 비울 때까지 반복
}
```

**결과:** 실패.
- wakeword 감지 자체가 안 됨 (prob 0.10 고정)
- 오디오는 들어오는데 (AudioLevel max=5478) wakeword 모델 출력이 상수
- drain 루프가 AudioRecord 스트림 상태에 영향을 준 것으로 추정

---

## 5. 안 되는 근본 원인

| | wake_wonder_onnx | VadPipelineService |
|---|---|---|
| 오디오 저장 위치 | **앱 메모리 (C++ deque)** | **OS 커널 (AudioRecord 내부 버퍼)** |
| 클리어 방법 | `deque.clear()` — O(1), 즉시 | `recorder.read()` 반복 — 읽어서 버려야 함 |
| 클리어 소요 시간 | **0ms** | 버퍼 크기에 비례 (수백 ms) |
| 클리어 부작용 | 없음 | AudioRecord 스트림 상태 영향 가능 |

**AudioRecord 내부 버퍼는 앱이 직접 제어할 수 없음.** 데이터를 꺼내려면 반드시 `read()`로 읽어야 함. 이것이 flush.

wake_wonder_onnx의 buffer.clear() 방식을 적용하려면 오디오 파이프라인 자체를 재설계해야 함:
1. AudioRecord → 별도 스레드에서 프레임 단위 read → 앱 레벨 ring buffer
2. wakeword/STT 모두 앱 레벨 버퍼에서 읽기
3. wakeword 감지 시 ring buffer clear

이는 VadPipelineService 전면 재작성에 해당하므로 현 단계에서 비실용적.

---

## 6. 대신 어떻게 해결했는가

wake_wonder_onnx의 buffer.clear()를 못 쓰므로, flush 기반에서 5가지 개선으로 동일한 효과 달성.

### 해결 1: flush 시간 축소 (1.0초 → 0.3초)

**문제:**
기존 flush 1.0초가 명령어 앞부분까지 삼김.

```
사용자: "하이 원더 엘리베이터 불러줘"
wakeword 감지 지연: ~0.5초
flush 1.0초: "엘리베이터 불러" 삼킴
남는 것: "줘" → STT "어"
```

**해결:**

```java
// 변경 전
short[] flush = new short[SR_MIC];           // 48000 = 1.0초

// 변경 후
short[] flush = new short[SR_MIC * 3 / 10];  // 14400 = 0.3초
```

0.3초면 "하이 원더"의 꼬리("더")는 제거되고, 명령어 앞부분은 보존.

**왜 0.3초인가:**
- 0.5초: PROBLEMS.md 기록상 FM1388 반향이 여전히 잡힘
- 1.0초: 명령어 잘림
- 0.3초: 꼬리 제거 + 명령어 보존 균형점

### 해결 2: flush 후 삐 소리

**문제:**
사용자가 "하이 원더" 직후 바로 명령어를 말하면 0.3초 flush에도 잘림.

**해결:**
flush 완료 직후 beep 재생. 사용자가 삐 소리를 듣고 말하면 flush에 안 잘림.

```java
// flush 완료
short[] flush = new short[SR_MIC * 3 / 10];
recorder.read(flush, 0, flush.length);

// 삐! (녹음 준비 완료 신호)
if (mTone != null) mTone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);

// VAD 시작 (여기서부터 녹음)
mVad.resetState();
```

```
시간축: ─── wakeword 감지 ─── flush 0.3초 ─── 삐! ─── VAD 시작 ──→
                                                       ↑ 사용자 여기서 말함
```

### 해결 3: trailing silence 제거

**문제:**
VAD가 silence 구간도 speechChunks에 추가 → STT에 잡음 포함 → "엘리베이터 켜줘 **오**"

```java
// VAD 루프 내부
} else if (speechStarted) {
    silenceFrames++;
    speechChunks.add(vadAudio);    // silence도 추가됨!
    if (silenceFrames >= silenceLimit) break;
}
```

**해결:**
VAD 루프 종료 후 마지막 silence 프레임 제거.

```java
// VAD 루프 종료 후
int removeCount = Math.min(silenceFrames, speechChunks.size());
for (int i = 0; i < removeCount; i++) {
    speechChunks.remove(speechChunks.size() - 1);
}
```

### 해결 4: STT 후 wakeword NPU reinit

**문제:**
Conformer NPU 추론 후 wakeword NPU 모델 출력이 상수 고정 (prob 0.10). 두 번째 "하이 원더"부터 감지 불가.

**원인:**
T527 NPU에서 Conformer(102MB) 추론 후 wakeword(229KB) 모델 상태가 깨짐.
wake_wonder_onnx는 ONNX Runtime(CPU)이라 이 문제 없음 — **NPU 특유의 문제**.

**해결:**
STT 실행 후 wakeword 모델을 매번 release + reinit.

```java
runStt(recorder);                          // Conformer NPU 추론
mJni.releaseWakeword();                    // wakeword 모델 해제
mJni.initWakeword(mWkNbPath);              // wakeword 모델 재로드
Log.d(TAG, "Wakeword model reinitialized");
```

### 해결 5: 부팅 자동시작 (AWBMS + Activity 경유)

**문제 A:** Allwinner AWBMS가 부팅 시 서비스 시작 차단.
**해결:** `/vendor/etc/awbms_config`의 whitelist에 `com.t527.vad_service` 추가.

**문제 B:** BootReceiver → Service 직접 시작 시 마이크 silenced 처리.
**해결:** BootReceiver → Activity 경유 → Service 시작. Activity가 foreground에 있어야 silenced:false.

---

## 7. 테스트 결과

### flush 1.0초 (원본, 해결 전)

| 입력 | STT 결과 | 판정 |
|------|----------|------|
| "엘리베이터 불러줘" | "어" | ✗ |
| "보일러 켜줘" | "어" | ✗ |
| "에어컨 켜줘" | "오" | ✗ |

### flush 0.3초 + 삐 + trailing silence 제거 + reinit (해결 후)

| 입력 | STT 결과 | 판정 |
|------|----------|------|
| "엘리베이터 켜줘" | "엘리베이터 켜 줘" | ✓ |
| "보일러 켜줘" | "보일러 켜 줘" | ✓ |
| "오늘 날씨 어때" | "오늘 날씨 어때" | ✓ |
| "엘리베이터 불러줘" | "엘리베이터 불러 줘" | ✓ |
| "보일러 켜줘" (2회차) | "보일러 켜줘" | ✓ (reinit 동작 확인) |

### 단지서버 전송 (전체 파이프라인)

모든 STT 결과 HTTP 200 성공. 서버 응답: `{"code":"S0000","message":"정상적으로 처리 되었습니다."}`

---

## 8. 결론

| 항목 | wake_wonder_onnx | 우리 (NPU 버전) |
|------|---|---|
| wakeword 꼬리 제거 | buffer.clear() | **flush 0.3초 + 삐 소리** |
| 명령어 보존 | pre-roll 버퍼 | **flush 축소 + 삐 후 발화** |
| 뒤 잡음 방지 | max_silence_frames 3초 | **trailing silence 제거** |
| 모델 전환 | ONNX CPU (독립) | **NPU reinit 필요** |
| 자동시작 | BootReceiver → Service | **Activity 경유 + AWBMS 화이트리스트** |

wake_wonder_onnx의 buffer.clear() 방식은 앱 레벨 오디오 버퍼 구조에서만 가능.
AudioRecord 직접 read 구조인 VadPipelineService에서는 **flush 기반 우회**로 동일한 효과 달성.
추가로 T527 NPU 특유의 모델 전환 문제(reinit)도 해결 필요.
