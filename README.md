# BBCPlayer · MP3 Player

Android용 반복 학습 MP3 플레이어입니다. 현재 버전은 **1.1.0**이며 Android 8.0 이상을 지원합니다.

## 1.1.0 변경사항

- Android 시스템 파일 선택창에서 Google Drive의 MP3를 선택할 수 있습니다.
- 기기 폴더 목록에서 파일을 누르면 확인 버튼 없이 바로 재생합니다.
- BBC 최신 6 Minute English 다운로드 기능과 인터넷 권한을 제거했습니다.
- 홈 화면에 추가할 수 있는 MP3 Player 위젯을 제공합니다.
- 긴 화면과 접이식 화면에서 위아래 여백이 균형을 이루도록 전체 화면을 가운데에 배치했습니다.
- Google Drive와 기기 오디오 선택 버튼을 분리했습니다.

## Google Drive MP3 재생

1. 앱에서 **Google Drive에서 MP3 선택**을 누릅니다.
2. Android 파일 선택창 왼쪽 메뉴에서 **Drive**를 선택합니다.
3. MP3 파일을 누르면 바로 재생됩니다.

휴대폰에 Google Drive 앱이 설치되어 있고 사용할 계정으로 로그인되어 있어야 합니다. 앱은 Google 계정 비밀번호나 Drive 전체 권한을 요구하지 않으며, 사용자가 고른 파일의 읽기 권한만 저장합니다.

## 홈 화면 위젯 추가

1. 홈 화면의 빈 곳을 길게 누릅니다.
2. **위젯**을 선택합니다.
3. **MP3 Player** 위젯을 찾아 홈 화면으로 끌어 놓습니다.
4. **이어듣기**는 최근 MP3를 마지막 위치부터 재생하고, **Drive에서 선택**은 파일 선택창을 엽니다.

## 빌드와 설치

Android Studio에서 프로젝트를 연 뒤 실행하거나 PowerShell에서 다음 명령을 사용합니다.

```powershell
.\gradlew.bat assembleDebug
```

APK는 다음 위치에 만들어집니다.

```text
app\build\outputs\apk\debug\app-debug.apk
```

USB 디버깅이 연결된 기기에 기존 데이터를 유지하면서 설치하려면:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

기기에 APK를 복사해 직접 눌러 설치할 수도 있습니다. 처음 설치할 때 Android가 해당 파일 앱의 ‘알 수 없는 앱 설치’ 허용을 요청할 수 있습니다.

## 파일 사용 안내

- 기기 오디오 폴더를 읽을 때만 음악 접근 권한을 요청합니다.
- Google Drive 선택은 Android 시스템 파일 선택 권한을 사용합니다.
- A/B 구간 반복, 속도 조절, 이동 간격, 즐겨찾기 3개, 파일 반복을 지원합니다.
- 선택한 파일 URI, 마지막 위치와 학습 설정은 앱의 로컬 설정에 저장됩니다.
