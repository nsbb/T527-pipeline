# FM1388 디지털 마이크 설정 가이드

월패드(IHN-1300D)에서 FM1388 디지털 마이크를 사용하기 위한 설정 절차.

**⚠️ 재부팅할 때마다 수행 필요 (새 소프트웨어에서 기본값이 AMIC으로 변경됨)**

---

## 1. 디지털 마이크 활성화

```bash
adb shell setprop vendor.audio.output.active.mic DMIC
```

확인:
```bash
adb shell getprop vendor.audio.output.active.mic
# 출력: DMIC
```

---

## 2. FM1388 디바이스 초기화 (root 필요)

```bash
adb root
adb shell fm_fm1388
```

정상 출력:
```
lib_enum_device: find device /dev/fm1388_front.
Using Front FM1388 device /dev/fm1388_front

Supported modes:
 - The current dsp_mode = 1
     1...VRMode (current used)
```

`q`로 종료.

**root 없이 실행하면:**
```
can not find FM1388 device
```

---

## 3. 앱 서비스에서 자동 DMIC 설정

서비스 `onCreate`에서 `setprop` 자동 실행:

**Java:**
```java
try {
    Runtime.getRuntime().exec(new String[]{"setprop", "vendor.audio.output.active.mic", "DMIC"}).waitFor();
} catch (Exception e) {}
```

**Kotlin:**
```kotlin
try { Runtime.getRuntime().exec(arrayOf("setprop", "vendor.audio.output.active.mic", "DMIC")).waitFor() } catch (_: Exception) {}
```

- `su` 없이 `setprop` 직접 호출 가능 (월패드 환경)
- `fm_fm1388` 초기화는 앱에서 실행 불가 (root 필요) → adb로 수동 실행

---

## 4. 전체 설정 순서 (재부팅 후)

```bash
# 1. DMIC 활성화
adb shell setprop vendor.audio.output.active.mic DMIC

# 2. FM1388 초기화
adb root
adb shell fm_fm1388
# → 정상 출력 확인 후 q로 종료

# 3. 서비스 시작
adb shell am start -n com.t527.vad_service/com.t527.wav2vecdemo.VadPipelineActivity
```

서비스에 DMIC 자동 설정이 들어있으면 1번은 생략 가능. 2번은 수동 필요.

---

## 5. 배경

- 이전 소프트웨어: `vendor.audio.output.active.mic` 기본값 `DMIC`
- 새 소프트웨어 (2026-04 업데이트): 기본값 `AMIC`으로 변경됨
- 원인: `/system/bin/hdc_start_launcher`에서 `setprop DMIC` 줄이 주석 처리됨
- 월패드 adb ID도 변경: `00f75c...` → `01775c...`

---

## 6. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| prob 0.16 고정, 하이원더 안 잡힘 | AMIC 모드 | `setprop DMIC` + `fm_fm1388` |
| `can not find FM1388 device` | root 아님 | `adb root` 후 재실행 |
| 재부팅 후 안 됨 | 설정 리셋 | 위 전체 절차 다시 수행 |
| `setprop` 앱에서 실패 | su 필요한 경우 | `su` 없이 직접 호출 시도 |
