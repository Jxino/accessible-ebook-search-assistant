package com.example.mytest

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

class SearchOcrAnalyzer {
    fun analyze(result: Text, targetPackage: String): String {
        val lines = result.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val text = line.text.cleanOcrText()
                val bounds = line.boundingBox
                if (text.isBlank() || bounds == null) null else OcrLine(text, bounds)
            }
            .sortedWith(compareBy<OcrLine> { it.bounds.top }.thenBy { it.bounds.left })

        val fullText = lines.joinToString("\n") { it.text }
        val serviceName = when {
            targetPackage == YES24_PACKAGE -> "YES24"
            targetPackage == KYOBO_PACKAGE -> "교보eBook"
            targetPackage == ALADIN_PACKAGE -> "알라딘 eBook"
            else -> "지원하지 않는 앱"
        }

        if (targetPackage !in SUPPORTED_PACKAGES) {
            return "$serviceName 화면은 아직 책 후보 추출 대상이 아닙니다."
        }

        if (fullText.hasBlockingScreenText()) {
            return "$serviceName: 책 검색 화면이 아닙니다.\n검색 결과나 책 목록 화면으로 이동한 뒤 다시 실행하세요."
        }

        val candidates = lines
            .filter { it.looksLikeBookCandidate() }
            .distinctBy { it.text.normalizedForCompare() }
            .take(MAX_CANDIDATE_COUNT)

        if (candidates.isEmpty()) {
            return "$serviceName: 책 후보를 찾지 못했습니다.\n검색 결과나 책 목록 화면에서 다시 실행하세요."
        }

        return buildString {
            append("$serviceName 책 후보 ${candidates.size}개")
            candidates.forEachIndexed { index, candidate ->
                append('\n')
                append(index + 1)
                append(". ")
                append(candidate.text)
            }
        }
    }

    private fun OcrLine.looksLikeBookCandidate(): Boolean {
        val text = text.trim()
        if (text.length < MIN_CANDIDATE_LENGTH) return false
        if (text.length > MAX_CANDIDATE_LENGTH) return false
        if (text.anyNoiseTerm()) return false
        if (text.isMostlyNumberOrSymbol()) return false
        if (text.matches(BOOK_COUNT_PATTERN)) return false
        if (text.matches(PERCENT_PATTERN)) return false
        if (text.contains("지음") || text.contains("옮김") || text.contains("저자")) return false
        if (text.contains("리뷰") || text.contains("평점") || text.contains("다운로드")) return false
        return true
    }

    private fun String.hasBlockingScreenText(): Boolean {
        val normalized = normalizedForCompare()
        val isKyoboEmptyLibrary = normalized.contains("나의") &&
            normalized.contains("평생") &&
            normalized.contains("e서재")
        return isKyoboEmptyLibrary || BLOCKING_SCREEN_TERMS.any { contains(it, ignoreCase = true) }
    }

    private fun String.anyNoiseTerm(): Boolean {
        return COMMON_NOISE_TERMS.any { contains(it, ignoreCase = true) }
    }

    private fun String.isMostlyNumberOrSymbol(): Boolean {
        val meaningfulChars = count { it.isLetterOrDigit() }
        val digitChars = count { it.isDigit() }
        return meaningfulChars == 0 || digitChars >= meaningfulChars - 1
    }

    private fun String.cleanOcrText(): String {
        return replace(Regex("\\s+"), " ")
            .replace("…", "...")
            .trim()
    }

    private fun String.normalizedForCompare(): String {
        return lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
            .trim()
    }

    private data class OcrLine(
        val text: String,
        val bounds: Rect
    )

    companion object {
        private const val YES24_PACKAGE = "com.yes24.ebook.fourth"
        private const val KYOBO_PACKAGE = "com.kyobo.ebook.common.b2c"
        private const val ALADIN_PACKAGE = "kr.co.aladin.ebook"
        private const val MIN_CANDIDATE_LENGTH = 2
        private const val MAX_CANDIDATE_LENGTH = 40
        private const val MAX_CANDIDATE_COUNT = 8

        private val SUPPORTED_PACKAGES = setOf(
            YES24_PACKAGE,
            KYOBO_PACKAGE,
            ALADIN_PACKAGE
        )

        private val BOOK_COUNT_PATTERN = Regex(""".*\(\d+\).*""")
        private val PERCENT_PATTERN = Regex("""^\d{1,3}%$""")

        private val BLOCKING_SCREEN_TERMS = listOf(
            "Viewing full screen",
            "To exit",
            "Got it",
            "선택적 접근 권한",
            "선택 접근 권한",
            "사진/미디어/파일",
            "마이크",
            "PDF 음성 녹음",
            "권한은 해당 기능",
            "동의하지 않아도",
            "어디서나 늘 같은 책장",
            "동기화되어",
            "건너뛰기",
            "로그인",
            "회원가입",
            "전자책 뷰어",
            "깔끔하게 정리한 화면",
            "자주 찾는 메뉴는 탭바에서",
            "어디서나 늘 같은 책장",
            "웹 뷰어로 즉시 감상",
            "접근 권한 동의가 필요합니다",
            "필수 접근권한",
            "선택 접근권한",
            "필수 권한",
            "권한이 허용되지",
            "앱을 종료합니다",
            "권한을 변경하시겠습니까",
            "권한 다시 설정",
            "앱종료",
            "나의 평생 e서재"
        )

        private val COMMON_NOISE_TERMS = listOf(
            "OCR",
            "책 후보",
            "책장",
            "구매목록",
            "구매 목록",
            "설정",
            "고객센터",
            "이용안내",
            "독서 캘린더",
            "동기화",
            "건너뛰기",
            "확인",
            "취소",
            "닫기",
            "편집",
            "필터",
            "책추가",
            "책 추가",
            "책읽기",
            "책 읽기",
            "읽은순",
            "구매순",
            "최근순",
            "제목순",
            "기본 책장",
            "별다섯책장",
            "만화방",
            "다시읽을책",
            "모든 책",
            "전체 책",
            "다운로드한 책",
            "미다운로드",
            "보관함",
            "내서재",
            "내 서재",
            "전자책",
            "알라딘 eBook",
            "알라딘 ebook",
            "알라딘",
            "KYOBoeBook",
            "건강을 위한 도서",
            "해외여행",
            "심리학 도서",
            "자기 개발",
            "검색",
            "메뉴",
            "탭바",
            "화면",
            "기능 제공",
            "사용하는 모든 단말"
        )
    }
}
