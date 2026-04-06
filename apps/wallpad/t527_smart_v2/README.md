# t527_smart_v2

**패키지:** `com.t527.smart_v2`
**기능:** VT → VAD → STT → NLU + 파라미터 추출 → overlay + MP3 응답

## v1 대비 추가 기능
1. **ParameterExtractor** — "거실 조명 켜줘" → location=거실 → "거실 조명을 켜드릴게요"
2. **MP3 응답** — intent별 98개 음성 파일 재생 (assets/tts/)
3. **Config 리로드** — 서비스 재시작 없이 config 변경 가능

## 설치 & 테스트
```bash
ADB="/mnt/c/Users/nsbb/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="00f75c0572408721ed9"

# 빌드
powershell.exe -Command "cd 'C:\Users\nsbb\AndroidStudioProjects\t527_smart_v2'; .\gradlew.bat assembleDebug"

# 설치
$ADB -s $DEV install -r 'C:\Users\nsbb\AndroidStudioProjects\t527_smart_v2\app\build\outputs\apk\debug\app-debug.apk'
$ADB -s $DEV shell pm grant com.t527.smart_v2 android.permission.RECORD_AUDIO
$ADB -s $DEV shell appops set com.t527.smart_v2 SYSTEM_ALERT_WINDOW allow

# 시작
$ADB -s $DEV shell am start -n com.t527.smart_v2/com.t527.smart_service.LauncherActivity

# 로그
$ADB -s $DEV logcat -s VoiceAiService IntentClassifier TtsPlayer
```

## Config 변경 (빌드 없이)
```bash
# config 수정 후 push
$ADB -s $DEV push config.json /sdcard/t527_smart_service/config.json

# 리로드 (서비스 재시작 없이)
$ADB -s $DEV shell am startservice -n com.t527.smart_v2/com.t527.smart_service.VoiceAiService --es action com.t527.smart_service.ACTION_RELOAD_CONFIG
```

## overlay 출력 예시
```
거실 조명 켜줘
→ light_control_on (15ms)
거실 조명을 켜드릴게요.
```
+ MP3 음성 재생: "조명을 켜드릴게요."

## 파라미터 추출 예시
| 발화 | intent | params |
|------|--------|--------|
| "거실 조명 켜줘" | light_control_on | location=거실 |
| "난방 이십삼도로 해줘" | heat_temp | temperature=23 |
| "에어컨 냉방 모드" | ac_mode | acMode=냉방 |
| "내일 날씨 알려줘" | weather_tomorrow | timeRef=내일 |
