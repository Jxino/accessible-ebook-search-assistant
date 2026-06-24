# E-book Search Assistant for Blind Readers

시각 장애인이 e-book 앱에서 원하는 책을 더 빠르게 찾을 수 있도록 돕는 Android 보조 앱입니다.

기존 e-book 앱에는 책 읽어주기 기능이 이미 있지만, 책을 찾는 과정에서는 메뉴, 권한 안내, 책장 정보, 광고성 문구처럼 필요 없는 정보까지 음성으로 듣게 되는 문제가 있습니다. 이 앱은 사용자가 필요한 책 후보에 바로 접근할 수 있도록 도서 API 검색과 화면 OCR 필터링을 결합합니다.

## 주요 기능

- 알라딘 Open API 기반 eBook 검색
- YES24, 교보문고, 알라딘 eBook 앱 감지
- 화면 캡처 기반 OCR
- 검색/목록 화면에서 책 제목 후보만 추출하는 앱별 OCR 필터
- 접근성 서비스와 오버레이를 이용한 보조 UI

## 개발 환경

- Android Studio
- Kotlin
- Jetpack Compose
- ML Kit Korean Text Recognition
- Gradle Kotlin DSL

## 로컬 설정

`local.properties`는 Git에 올리지 않습니다. 팀원은 `local.properties.example`을 참고해 각자 로컬에 `local.properties`를 만들어야 합니다.

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
ALADIN_TTB_KEY=your_aladin_ttb_key_here
```

## 실행 전 권한

앱에서 다음 권한을 켜야 OCR 보조 기능을 사용할 수 있습니다.

1. 다른 앱 위에 표시 권한
2. 접근성 서비스 권한
3. 화면 캡처 권한

## 현재 지원 범위

- YES24 eBook: OCR 필터 지원
- 교보 eBook: OCR 필터 지원
- 알라딘 eBook: API 검색 및 OCR 필터 지원

알라딘 앱은 일부 에뮬레이터/API 조합에서 메인 화면을 유지하지 못할 수 있어 실제 기기 테스트가 필요합니다.
