package com.example.mytest

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchOcrAnalyzerTest {
    private val analyzer = SearchOcrAnalyzer()

    @Test
    fun recognizedOcrLines_extractBookTitleAuthorPublisherAndPrice() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "검색",
                "혼자 공부하는 파이썬",
                "윤인성 지음 | 한빛미디어",
                "24,000원",
                "장바구니"
            ),
            YES24_PACKAGE
        )

        assertTrue(result, result.startsWith("YES24 책 후보 1개"))
        assertTrue(result, result.contains("혼자 공부하는 파이썬"))
        assertTrue(result, result.contains("저자: 윤인성"))
        assertTrue(result, result.contains("출판사: 한빛미디어"))
        assertTrue(result, result.contains("가격: 24,000원"))
    }

    @Test
    fun unsupportedPackage_doesNotPretendToExtractCandidates() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "혼자 공부하는 자바",
                "신용권 지음 | 한빛미디어",
                "30,000원"
            ),
            "com.example.unsupported"
        )

        assertTrue(result, result.contains("아직 책 후보 추출 대상이 아닙니다"))
    }

    @Test
    fun kyoboDetailPage_prioritizesVisibleBookMetadataOverImagesAndRankings() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "부의 갈림길 | 오건영 - 교보문고",
                "부의 갈림길 대표 배경 이미지",
                "국내도서",
                "24,300원",
                "eBook",
                "18,000원",
                "부의 갈림길",
                "대전환의 시작, 다시 쓰는 투자 포트폴리오",
                "오건영",
                " 저자(글)",
                "포레스트북스",
                " · 2026년 06월 10일",
                "주간베스트",
                "국내도서 3위 · 경제/경영 2위"
            ),
            KYOBO_PACKAGE
        )

        assertTrue(result, result.startsWith("교보문고 책 후보 1개"))
        assertTrue(result, result.contains("1. 부의 갈림길"))
        assertTrue(result, result.contains("저자: 오건영"))
        assertTrue(result, result.contains("출판사: 포레스트북스"))
        assertTrue(result, result.contains("가격: 24,300원"))
    }

    @Test
    fun kyoboListPage_extractsMultipleVisibleBooksDespiteInterleavedUiText() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "초보자를 위한 파이썬 200제",
                "대표 이미지",
                "미리보기",
                "장바구니",
                "박응용 지음 | 이지스퍼블리싱",
                "22,000원",
                "Do it! 점프 투 파이썬",
                "eBook",
                "바로구매",
                "박응용 지음 | 이지스퍼블리싱",
                "19,800원",
                "혼자 공부하는 파이썬",
                "윤인성 지음 | 한빛미디어",
                "26,000원"
            ),
            KYOBO_PACKAGE
        )

        assertTrue(result, result.startsWith("교보문고 책 후보 3개"))
        assertTrue(result, result.contains("1. 초보자를 위한 파이썬 200제"))
        assertTrue(result, result.contains("저자: 박응용"))
        assertTrue(result, result.contains("출판사: 이지스퍼블리싱"))
        assertTrue(result, result.contains("가격: 22,000원"))
        assertTrue(result, result.contains("2. Do it! 점프 투 파이썬"))
        assertTrue(result, result.contains("가격: 19,800원"))
        assertTrue(result, result.contains("3. 혼자 공부하는 파이썬"))
        assertTrue(result, result.contains("저자: 윤인성"))
        assertTrue(result, result.contains("출판사: 한빛미디어"))
    }

    @Test
    fun kyoboListPage_doesNotTreatAuthorMarkerAsSingleDetailPage() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "78,086건",
                "인기순",
                "필터",
                "[국내도서] 김미경의 딥마인드",
                "포스트 AI 시대를 사는 보통 인간의 인생을 바꾸는 AI 전환 전략",
                "김미경 저자(글) · 어웨이크(AWAKE)",
                "17,550원",
                "9.9",
                "추천해요",
                "[국내도서] 미국 성장주는 멈추지 않는다",
                "AI부터 로봇까지 세상을 뒤집을 폭발적 성장 기업",
                "김영웅 저자(글) · 경이로움",
                "23,400원",
                "10.0",
                "도움돼요"
            ),
            KYOBO_PACKAGE
        )

        assertTrue(result, result.startsWith("교보문고 책 후보 2개"))
        assertTrue(result, result.contains("1. 김미경의 딥마인드"))
        assertTrue(result, result.contains("저자: 김미경"))
        assertTrue(result, result.contains("출판사: 어웨이크(AWAKE)"))
        assertTrue(result, result.contains("가격: 17,550원"))
        assertTrue(result, result.contains("2. 미국 성장주는 멈추지 않는다"))
        assertTrue(result, result.contains("저자: 김영웅"))
        assertTrue(result, result.contains("출판사: 경이로움"))
        assertTrue(result, result.contains("가격: 23,400원"))
    }

    @Test
    fun kyoboAiSearchPage_ignoresDeliveryAndBenefitTextWhenTwoBooksAreVisible() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "78,086건",
                "인기순",
                "필터",
                "추천",
                "[국내도서] AI 이후의 미래 어떻게 될 것인가",
                "제이슨 생커 저자(글) · 김익성 번역 · 더페이지",
                "16,920원",
                "9.6 (121)",
                "추천해요",
                "내일(토) 도착",
                "6월의 바로 이 책 외 9건",
                "오늘의 선택",
                "MD의 선택",
                "이벤트",
                "[국내도서] 김미경의 플러스 휴먼",
                "보통 인간의 한계를 깨부수는 AI 진화 전략",
                "김미경 저자(글) · 어웨이크(AWAKE)",
                "17,550원",
                "9.9 (81)",
                "추천해요",
                "내일(토) 도착",
                "시네마 리딩 클럽 #2. 퍼펙트 데이즈 외 9건"
            ),
            KYOBO_PACKAGE
        )

        assertTrue(result, result.startsWith("교보문고 책 후보 2개"))
        assertTrue(result, result.contains("1. AI 이후의 미래 어떻게 될 것인가"))
        assertTrue(result, result.contains("저자: 제이슨 생커"))
        assertTrue(result, result.contains("출판사: 더페이지"))
        assertTrue(result, result.contains("가격: 16,920원"))
        assertTrue(result, result.contains("2. 김미경의 플러스 휴먼"))
        assertTrue(result, result.contains("저자: 김미경"))
        assertTrue(result, result.contains("출판사: 어웨이크(AWAKE)"))
        assertTrue(result, result.contains("가격: 17,550원"))
        assertTrue(result, !result.contains("내일(토) 도착"))
        assertTrue(result, !result.contains("보통 인간의 한계를 깨부수는 AI 진화 전략"))
    }

    @Test
    fun kyoboPythonSearchPage_mergesSplitTitleAndIgnoresSearchBoxText() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "Python",
                "관련",
                "앱",
                "JAVA",
                "인기순",
                "필터",
                "초보자를 위한",
                "초보자를 위한 파이썬(Python) 200제",
                "장삼용 저자(글) 정보문화사",
                "18,000원",
                "9.3 (28)",
                "도움돼요",
                "[eBook] 파이썬 Python 기초 가이드 - 이 책 한 권이면 끝!",
                "박빈 저자(글) · 와이웨이브이퍼블리싱",
                "쿠폰적용가 10%",
                "8,010원"
            ),
            KYOBO_PACKAGE
        )

        assertTrue(result, result.startsWith("교보문고 책 후보 2개"))
        assertTrue(result, result.contains("1. 초보자를 위한 파이썬(Python) 200제"))
        assertTrue(result, result.contains("저자: 장삼용"))
        assertTrue(result, result.contains("출판사: 정보문화사"))
        assertTrue(result, result.contains("가격: 18,000원"))
        assertTrue(result, result.contains("2. 파이썬 Python 기초 가이드 - 이 책 한 권이면 끝!"))
        assertTrue(result, result.contains("저자: 박빈"))
        assertTrue(result, result.contains("출판사: 와이웨이브이퍼블리싱"))
        assertTrue(result, result.contains("가격: 8,010원"))
        assertTrue(result, !result.contains("3. Python"))
        assertTrue(result, !result.contains("1. 초보자를 위한\n"))
    }

    @Test
    fun aladinBookSearchPage_extractsTwoVisibleBooks() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "말 페이퍼 클립 + 틴 케이스 세트 (대상도서 포함 국내도서 3만원 이상)",
                "[국내도서] 한눈에 보는 A",
                "I 반도체 산업 - GPU부터 H",
                "BM, 파운드리, 패키징, 데이터",
                "센터까지 하나의 흐름으로 읽는",
                "AI 반도체 생태계",
                "MrTrigger (지은이)",
                "한빛미디어 | 2026년 03월",
                "23,400원 (10% 할인 / 1,300원)",
                "양탄자배송 밤 11시 잠들기전 배송 변경",
                "[국내도서] 바쁜 교사를",
                "위한 바로 쓰는 AI - 수업 설",
                "계부터 학생 평가, 행정 업무까",
                "지! AI로 완성하는 실무 활용법",
                "전소영, 이태화, 조두연 (지은이)",
                "사회평론아카데미 | 2026년 04월",
                "21,600원 (10% 할인 / 1,200원)"
            ),
            ALADIN_PACKAGE
        )

        assertTrue(result, result.startsWith("알라딘 책 후보 2개"))
        assertTrue(result, result.contains("1. 한눈에 보는 AI 반도체 산업 - GPU부터 HBM, 파운드리, 패키징, 데이터 센터까지 하나의 흐름으로 읽는 AI 반도체 생태계"))
        assertTrue(result, result.contains("저자: MrTrigger"))
        assertTrue(result, result.contains("출판사: 한빛미디어"))
        assertTrue(result, result.contains("가격: 23,400원"))
        assertTrue(result, result.contains("2. 바쁜 교사를 위한 바로 쓰는 AI - 수업 설계부터 학생 평가, 행정 업무까지! AI로 완성하는 실무 활용법"))
        assertTrue(result, result.contains("저자: 전소영, 이태화, 조두연"))
        assertTrue(result, result.contains("출판사: 사회평론아카데미"))
        assertTrue(result, result.contains("가격: 21,600원"))
    }

    @Test
    fun yes24BookSearchPage_extractsTwoVisibleBooks() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "재검색",
                "분야",
                "AI 활용",
                "상품태그",
                "[도서] 바로바로 클로드 wi",
                "th 코워크, 스킬, 클로드 코",
                "드, 디자인",
                "차진우 저",
                "골든래빗 • 2026.5.20.",
                "스탠딩 펜 파우치(포인트 차감)",
                "10% 25,200원",
                "1,400원",
                "10.0 (6)",
                "6/29(월) 도착예정",
                "사은품 [대학생X취준생] 미래를 그리는 여름방학",
                "[도서] AI 이후의 미래 어떻게 될 것인가",
                "제이슨 솅커 저, 김익성 역",
                "더페이지 • 2026.5.10.",
                "[엔비디아 기획전] 쿠션 방석 (포인트 차감)",
                "10% 16,920원"
            ),
            YES24_PACKAGE
        )

        assertTrue(result, result.startsWith("YES24 책 후보 2개"))
        assertTrue(result, result.contains("1. 바로바로 클로드 with 코워크, 스킬, 클로드 코드, 디자인"))
        assertTrue(result, result.contains("저자: 차진우"))
        assertTrue(result, result.contains("출판사: 골든래빗"))
        assertTrue(result, result.contains("가격: 25,200원"))
        assertTrue(result, result.contains("2. AI 이후의 미래 어떻게 될 것인가"))
        assertTrue(result, result.contains("저자: 제이슨 솅커, 김익성 역"))
        assertTrue(result, result.contains("출판사: 더페이지"))
        assertTrue(result, result.contains("가격: 16,920원"))
    }

    @Test
    fun kyoboBookSearchPage_excludesEllipsizedTitlesInStrictMode() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "78,096건",
                "인기순",
                "필터",
                "추천",
                "[국내도서] 박태웅의 AI 강의 20",
                "26",
                "박태웅 저자(글) · 한빛비즈",
                "20,700원 10% (1,150p)",
                "9.7 (220)",
                "도움돼요",
                "당일배송 오늘(토) 도착",
                "2026 교보문고 상반기 종합 베스트셀러 외 9건",
                "[국내도서] AI 시대에 개발자가",
                "알아야 할 인프라 구성 배포 wi...",
                "자연어로 구축하고 운영하는 AI ...",
                "조훈 저자(글) · 길벗",
                "21,600원 10% (1,200p)"
            ),
            KYOBO_PACKAGE
        )

        assertTrue(result, result.startsWith("교보문고 책 후보 1개"))
        assertTrue(result, result.contains("1. 박태웅의 AI 강의 2026"))
        assertTrue(result, result.contains("저자: 박태웅"))
        assertTrue(result, result.contains("출판사: 한빛비즈"))
        assertTrue(result, result.contains("가격: 20,700원"))
        assertTrue(result, !result.contains("2. AI 시대에 개발자가"))
    }

    @Test
    fun yes24MixedVisibleBookInfo_doesNotShowUnsafeCandidates() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "41,669개",
                "품절포함",
                "인기도순",
                "재검색",
                "분야",
                "AI 활용",
                "[도서] AI, 신의 탄생 인간의 종말",
                "노벨장편희상수상자 벤버닝거주전",
                "저자: 음별책 부록",
                "출판사: 직강 유튜브 15개 프로젝트 파일 제",
                "6/29(월) 도착예정",
                "사은품 예스24 X JTBC 콜라보",
                "[도서] 혼자 공부하는 바이브 코딩 with 클로드 코드",
                "조태호 저",
                "한빛미디어 • 2025.12.16.",
                "스탠딩 펜 파우치(포인트 차감)",
                "10% 27,000원"
            ),
            YES24_PACKAGE
        )

        assertTrue(result, result.contains("완전한 책 정보를 찾지 못했습니다"))
        assertTrue(result, !result.contains("AI, 신의 탄생"))
        assertTrue(result, !result.contains("출판사: 직강 유튜브"))
    }

    @Test
    fun kyoboMixedVisibleBookInfo_onlyShowsCompleteBooks() {
        val result = analyzer.analyzeRecognizedTextLines(
            listOf(
                "78,097건",
                "인기순",
                "필터",
                "추천",
                "혼자.",
                "저자: 조태호",
                "출판사: 한빛미디어",
                "AI와 1:1 대화하며 배우는 첫 코딩 자습서 IT 전문서...",
                "조태호 저자(글) · 한빛미디어",
                "가격: 27,000원",
                "도움돼요",
                "[국내도서] 혼자 공부하는 바이브 코딩 with 클로드 코드",
                "AI와 1:1 대화하며 배우는 첫 코딩 자습서 IT 전문서...",
                "조태호 저자(글) · 한빛미디어",
                "27,000원 10% (1,500p)"
            ),
            KYOBO_PACKAGE
        )

        assertTrue(result, result.startsWith("교보문고 책 후보 1개"))
        assertTrue(result, result.contains("1. 혼자 공부하는 바이브 코딩 with 클로드 코드"))
        assertTrue(result, result.contains("저자: 조태호"))
        assertTrue(result, result.contains("출판사: 한빛미디어"))
        assertTrue(result, result.contains("가격: 27,000원"))
        assertTrue(result, !result.contains("1. 혼자."))
        assertTrue(result, !result.contains("3. AI"))
    }

    private companion object {
        private const val YES24_PACKAGE = "com.yes24.commerce"
        private const val KYOBO_PACKAGE = "mok.android"
        private const val ALADIN_PACKAGE = "kr.co.aladin.third_shop"
    }
}
