# Book Search Assistant for Blind Readers

시각 장애인이 도서 앱에서 원하는 책을 더 빠르게 찾을 수 있도록 돕는 Android 보조 앱입니다.

현재 버전은 안정 버전 `1.0.1`입니다.

기존 독서 앱과 도서 앱에는 책 읽기 보조 기능이 있지만, 책을 찾는 과정에서는 메뉴, 권한 안내, 광고성 문구처럼 필요 없는 정보까지 음성으로 듣게 되는 문제가 있습니다. 이 앱은 사용자가 필요한 책 후보에 바로 접근할 수 있도록 도서 API 검색과 화면 OCR 기반 책 정보 추출 기능을 결합합니다.

## 주요 기능

- 알라딘 Open API 기반 도서 검색
- YES24, 교보문고, 알라딘 앱 감지
- 화면 캡처 OCR 기반 텍스트 추출
- 검색/목록 화면에서 책 제목, 저자, 출판사, 가격이 모두 보이는 책만 추출하는 앱별 필터
- 접근성 서비스와 오버레이를 이용한 보조 UI

## 개발 환경

- Android Studio
- Kotlin
- Jetpack Compose
- ML Kit Korean Text Recognition
- Gradle Kotlin DSL

## 내려받기

GitHub 저장소 전체를 내려받아야 합니다. 일부 파일만 내려받으면 Gradle 설정이나 Android 리소스가 빠져 실행되지 않습니다.

```bash
git clone https://github.com/Jxino/accessible-ebook-search-assistant.git
cd accessible-ebook-search-assistant
```

Git을 쓰기 어렵다면 GitHub 페이지에서 `Code > Download ZIP`으로 전체 압축 파일을 내려받은 뒤 압축을 풀어도 됩니다.

## 로컬 설정

`local.properties`는 Git에 올리지 않습니다. 팀원은 `local.properties.example`을 참고해 각자 로컬에 `local.properties`를 만들어야 합니다.

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
ALADIN_TTB_KEY=your_aladin_ttb_key_here
```

Android Studio에서 프로젝트를 열면 `sdk.dir`은 자동으로 생성될 수 있습니다. 직접 만드는 경우 `local.properties.example`을 복사해서 `local.properties`로 이름을 바꾼 뒤 자신의 Android SDK 경로와 알라딘 API 키를 입력합니다.

```powershell
Copy-Item local.properties.example local.properties
```

`ALADIN_TTB_KEY`가 없으면 알라딘 도서 검색 기능이 동작하지 않습니다.

## 실행 방법

1. Android Studio 실행
2. `Open` 선택
3. 저장소 루트 폴더 선택
4. Gradle Sync 완료 대기
5. 에뮬레이터 또는 Android 기기 연결
6. `Run app` 실행

명령어로 빌드만 확인하려면 다음을 실행합니다.

```powershell
.\gradlew.bat assembleDebug
```

빌드된 APK 위치는 다음과 같습니다.

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 실행 전 권한

앱에서 다음 권한을 켜야 검색 보조 기능을 사용할 수 있습니다.

1. 다른 앱 위에 표시 권한
2. 접근성 서비스 권한
3. 화면 캡처 권한

앱 첫 화면의 버튼을 위에서부터 눌러 권한을 켜면 됩니다. 책 정보 추출은 화면 캡처 OCR만 사용합니다. 접근성 서비스는 대상 앱을 감지하고 오버레이 버튼을 띄우는 용도로 사용합니다.

## 테스트 방법

1. 앱을 실행해 알라딘 도서 검색이 되는지 확인합니다.
2. YES24, 교보문고, 알라딘 앱 중 하나를 실행합니다.
3. 대상 앱의 검색 결과 또는 책 목록 화면으로 이동합니다.
4. 화면 위에 뜨는 `책 후보 찾기` 버튼을 누릅니다.
5. 오버레이 결과 앞의 `[OCR]` 라벨을 확인합니다.
6. 책 제목, 저자, 출판사, 가격 4개 정보가 모두 있는 책만 표시되는지 확인합니다.
7. 4개 정보가 모두 보이지 않는 화면에서는 스크롤 후 다시 실행하라는 안내가 나오는지 확인합니다.

## 현재 지원 범위

- YES24: OCR 기반 책 후보 필터 지원
- 교보문고: OCR 기반 책 후보 필터 지원
- 알라딘: API 검색 및 OCR 기반 책 후보 필터 지원

OCR 결과는 정확도를 우선합니다. 책 제목, 저자, 출판사, 가격 중 하나라도 빠지거나 말줄임표로 잘린 제목만 보이면 해당 항목은 표시하지 않습니다. 완전한 후보가 하나도 없으면 `책 제목, 저자, 출판사, 가격이 모두 보이도록 스크롤한 뒤 다시 실행하세요.`라는 안내를 표시합니다.

알라딘 앱은 일부 에뮬레이터/API 조합에서 메인 화면을 유지하지 못할 수 있어 실제 기기 테스트가 필요합니다.
