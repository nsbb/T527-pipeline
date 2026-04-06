# 월패드 안드로이드 앱

월패드(IHN-1300D, Android 13) 탑재용 음성 AI 서비스 앱.

## 앱 목록

| 앱 | 패키지 | 설명 |
|---|--------|------|
| **t527_smart_service** | `com.t527.smart_service` | Kotlin v1: 모듈 분리 + JSON config + EMA/NofM 후처리 + NLU |
| **t527_smart_v2** | `com.t527.smart_v2` | v1 + ParameterExtractor + MP3 응답 + config reload |

## 파이프라인

```
"하이 원더" → [BCResNet 8ms] → [EMA+NofM] → VAD → [Conformer 250ms] → [KoELECTRA 12ms] → overlay + MP3
```

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

모델 원본 위치: `t527-stt/conformer/`, `t527-wakeword/`

## 빌드 & 테스트

각 앱 폴더의 README.md 참고.

## 구조 (세 프로젝트 장점 통합)

| 출처 | 가져온 것 |
|------|---------|
| ai_agent | Kotlin + coroutines, 모듈 분리, data class, try-catch |
| wake_wonder | JSON config, EMA + N-of-M + cooldown 후처리 |
| vad_service | Foreground Service, BR, overlay, C JNI + NB 모델 |
