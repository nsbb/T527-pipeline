# t527_smart_service (v1)

**패키지:** `com.t527.smart_service`
**기능:** VT → VAD → STT → NLU → overlay

## 특징
- Kotlin + coroutines (ai_agent 구조)
- JSON config 시스템 (wake_wonder)
- EMA + N-of-M + cooldown VT 후처리 (wake_wonder)
- KoELECTRA 의도 분류 48 intents
- 모듈 분리: audio/ wakeword/ vad/ stt/ nlu/ ui/

## 설치 & 테스트
```bash
ADB="/mnt/c/Users/nsbb/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="00f75c0572408721ed9"

# 빌드
powershell.exe -Command "cd 'C:\Users\nsbb\AndroidStudioProjects\t527_smart_service'; .\gradlew.bat assembleDebug"

# 설치
$ADB -s $DEV install -r 'C:\Users\nsbb\AndroidStudioProjects\t527_smart_service\app\build\outputs\apk\debug\app-debug.apk'
$ADB -s $DEV shell pm grant com.t527.smart_service android.permission.RECORD_AUDIO
$ADB -s $DEV shell appops set com.t527.smart_service SYSTEM_ALERT_WINDOW allow

# 시작
$ADB -s $DEV shell am start -n com.t527.smart_service/com.t527.smart_service.LauncherActivity

# 로그
$ADB -s $DEV logcat -s VoiceAiService
```

## overlay 출력 예시
```
엘리베이터 불러줘
→ lobby_open (12ms)
로비 출입문을 열겠습니다.
```
