# T527 온디바이스 음성 AI 파이프라인

T527 Vivante NPU에서 **서버 없이** 동작하는 음성 AI 파이프라인.

```
"하이 원더" → [BCResNet 7ms] → 삐! → VAD+STT → 한국어 텍스트 → 단지서버 전송
                                                                    ↑
                                            단지서버 TTS 수신 ← HTTPS 8030
```

---

## 앱 버전

| 앱 | 위치 | 설명 |
|---|------|------|
| **t527_pipeline** | `apps/t527_pipeline/` | VT → 고정 3초 STT (기본) |
| **vad_service_with_server** | `apps/vad_service_with_server/` | **VT → VAD → STT → 단지서버 전송 + TTS 수신 (현재 주력)** |

---

## wake_wonder_onnx 분석 및 적용

### wake_wonder_onnx가 뭔가

다른 팀(Gemini Code Assist + Claude Code)이 만든 wakeword+STT 앱. ONNX Runtime(CPU) 기반.
`HANDOFF.md` ~ `HANDOFF_4.1.md`에 개발 과정이 문서화되어 있음.

### wake_wonder_onnx의 핵심 아키텍처

```
AudioRecord → PCM 워커(10ms 프레임) → 앱 레벨 deque 버퍼
                                          ↓
                              WAKE MODE: 항상 streaming → wakeword 추론
                                          ↓ (감지!)
                              state.audio_buffer.clear()  ← deque 즉시 클리어
                                          ↓
                              STT MODE: VAD로 새 발화 감지 → 녹음
```

**핵심 설계:** 오디오를 앱 레벨 deque에 복사해서 관리. wakeword 감지 시 `buffer.clear()` 한 줄로 즉시 클리어. flush(고정 시간 오디오 버리기) 없음.

### wake_wonder_onnx가 해결한 문제들

| 문제 | HANDOFF | 해결 |
|------|---------|------|
| wakeword 후처리 | HANDOFF.md | EMA + N-of-M + Refractory 4단계 체인 |
| 감지 후 불필요한 추론 제거 | HANDOFF_2.md | 큐 정리 로직 |
| "하이 빅스비" 잔여음 0.3초 WAV | HANDOFF_3.md | 플래그 설정 순서 교정 + silence_count 리셋 |
| GetState()가 매 호출마다 덮어씀 | HANDOFF_4.md | 멤버 초기값 + GetState() 내 store 제거 |
| WAKE→STT 모드 분리 | HANDOFF_4.1.md | WAKE: VAD 없이 streaming, STT: VAD로 발화 감지 |

### 우리 환경(VadPipelineService)에 적용 시도

**차이점:** 우리는 ONNX Runtime이 아닌 **T527 NPU (VIPLite)**로 추론. AudioRecord에서 직접 read()하는 구조.

#### 시도 1: buffer.clear() 방식 적용

wake_wonder_onnx처럼 flush 없이 AudioRecord 버퍼만 비우려 함.

```java
// READ_NON_BLOCKING으로 버퍼 비우기 시도
short[] discard = new short[Math.min(staleSize, SR_MIC)];
recorder.read(discard, 0, discard.length, AudioRecord.READ_NON_BLOCKING);
```

**결과:** 실패. "하이 원더" 1.5초 오디오가 그대로 VAD에 진입. `speech 3.04s` → STT "에어" (garbage)

**원인:** AudioRecord 내부 버퍼는 `read()`로 읽어야만 비워짐. 한 번 호출로 전부 안 비워짐.

#### 시도 2: 반복 drain

```java
short[] drain = new short[4800];
while (recorder.read(drain, 0, drain.length, AudioRecord.READ_NON_BLOCKING) > 0) {}
```

**결과:** 실패. wakeword 감지 자체 불가.

#### 근본 원인

| | wake_wonder_onnx | vad_service_with_server |
|---|---|---|
| 오디오 버퍼 | **앱 레벨 deque** | **AudioRecord 내부 버퍼** |
| 클리어 | `buffer.clear()` — 즉시 0ms | `recorder.read()` 반복 — 시간 소요 |

**wake_wonder_onnx의 buffer.clear()는 앱 레벨 deque라서 가능.** AudioRecord 내부 버퍼는 읽어서 버려야 함 = flush. 구조가 다르므로 직접 적용 불가.

상세 분석: `docs/bestin/BUFFER_CLEAR_VS_FLUSH.md`

### 대신 어떻게 해결했는가

wake_wonder_onnx의 buffer-clear를 쓸 수 없으므로, flush 기반에서 개선:

#### 해결 1: flush 1.0초 → 0.3초

**문제:** flush 1.0초가 명령어 앞부분까지 삼킴. "엘리베이터 불러줘" → "어"

wakeword 감지 지연(~0.5초) + flush 1.0초 = ~1.5초 손실. 명령어가 거의 다 잘림.

**해결:** 0.3초로 축소. wakeword 꼬리("더")는 제거되고, 명령어는 보존.

#### 해결 2: flush 후 삐 소리

**문제:** 사용자가 "하이 원더" 직후 바로 명령어를 말하면 flush에 잘림.

**해결:** flush 완료 후 ToneGenerator beep. 사용자가 삐 소리 듣고 말하면 안 잘림.

```
wakeword 감지 → flush 0.3초 → 삐! → VAD 시작 (여기서부터 녹음)
```

#### 해결 3: trailing silence 제거

**문제:** VAD silence 구간이 STT에 포함 → "엘리베이터 켜줘 **오**"

**해결:** VAD 루프 종료 후 마지막 silence 프레임 제거.

```java
int removeCount = Math.min(silenceFrames, speechChunks.size());
for (int i = 0; i < removeCount; i++) {
    speechChunks.remove(speechChunks.size() - 1);
}
```

#### 해결 4: STT 후 wakeword NPU reinit

**문제:** Conformer NPU 추론 후 wakeword NPU 출력이 상수 고정 (prob 0.10).

**해결:** STT 실행 후 wakeword 모델을 매번 reinit.

```java
runStt(recorder);
mJni.releaseWakeword();
mJni.initWakeword(mWkNbPath);
```

#### 해결 5: 부팅 자동시작 (AWBMS + Activity 경유)

**문제 1:** Allwinner AWBMS가 서비스 시작 차단.
**해결:** `/vendor/etc/awbms_config` 화이트리스트에 패키지 추가.

**문제 2:** BootReceiver → Service 직접 시작 시 마이크 silenced.
**해결:** BootReceiver → **Activity 경유** → Service 시작. Activity foreground에서 시작해야 silenced:false.

---

## 현재 설정값

| 파라미터 | 값 | 비고 |
|---------|-----|------|
| VT threshold | 0.40 | FM1388 마이크 기준 |
| **Flush** | **0.3초** | 1.0초 → 명령어 잘림 방지 |
| **Trailing silence** | **제거** | "오" 붙는 현상 방지 |
| **Wakeword reinit** | **STT 후 매번** | NPU 깨짐 방지 |
| **Beep** | **flush 후** | 사용자에게 말해도 된다는 신호 |
| VAD threshold | 0.50 | |
| Silence timeout | 800ms | 1500ms → 2-chunk 방지 |
| Speech-only 최소 | 0.5초 | 간투어("어") 필터 |
| 단지서버 URL | https://10.0.1.1/ai/text/send | multipart/form-data |
| TTS 수신 포트 | 8030 (HTTPS) | 자체서명 SSL |

---

## 성능

| 단계 | 모델 | NB 크기 | NPU 추론 |
|------|------|--------|---------|
| Wakeword (VT) | BCResNet-t2 uint8 | **229KB** | **8ms** |
| STT | Conformer CTC uint8 | **102MB** | **250ms** |

### STT 정확도

| | FP32 (서버) | uint8 (T527 NPU) |
|---|-----------|-----------------|
| avg_real (자체 368개) | 6.03% | **7.24%** |

---

## 월패드 배포 주의사항

1. **AWBMS 화이트리스트:** `/vendor/etc/awbms_config`에 `com.t527.vad_service` 추가 필수
2. **앱 업데이트:** 반드시 `adb install -r` 사용. ⚠️ `pm uninstall` 금지 (NPU 깨짐)
3. **시간 동기화:** 단지서버가 timestamp 검증하므로 시간 맞춰야 함
4. **마이크 권한:** 첫 설치 시 Activity에서 RECORD_AUDIO 권한 획득 필요

---

## 파일 구조

```
t527-pipeline/
├── README.md
├── apps/
│   ├── t527_pipeline/                → 기본 VT+STT
│   └── vad_service_with_server/      → 단지서버 연동 (현재 주력)
├── docs/
│   ├── bestin/
│   │   ├── BUFFER_CLEAR_VS_FLUSH.md  → wake_wonder_onnx 분석
│   │   ├── PROBLEMS.md               → 월패드 연동 문제 기록
│   │   └── SERVICE_ARCHITECTURE.md
│   └── NEMO_TO_NB_CONVERSION_GUIDE.md
└── models/
```

---

## 관련 레포

| 레포 | 내용 |
|------|------|
| [nsbb/T527-STT](https://github.com/nsbb/T527-STT) | Conformer CTC 양자화 + QAT |
| [nsbb/T527-wakeword](https://github.com/nsbb/T527-wakeword) | BCResNet wakeword T527 포팅 |
