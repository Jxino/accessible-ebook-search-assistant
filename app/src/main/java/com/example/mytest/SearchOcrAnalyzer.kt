package com.example.mytest

import com.google.mlkit.vision.text.Text

class SearchOcrAnalyzer {
    fun analyze(result: Text, targetPackage: String): String {
        val lines = result.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val text = line.text.cleanOcrText()
                val bounds = line.boundingBox
                if (text.isBlank() || bounds == null) null else SearchLine(text, bounds.top, bounds.left)
            }
            .sortedWith(compareBy<SearchLine> { it.top }.thenBy { it.left })

        return analyzeLines(lines, targetPackage)
    }

    fun analyze(accessibilityLines: List<String>, targetPackage: String): String {
        val lines = accessibilityLines
            .mapIndexedNotNull { index, rawText ->
                val text = rawText.cleanOcrText()
                if (text.isBlank()) null else SearchLine(text, index, 0)
            }
            .distinctBy { it.text.normalizedForCompare() }

        return analyzeLines(lines, targetPackage)
    }

    private fun analyzeLines(lines: List<SearchLine>, targetPackage: String): String {
        val fullText = lines.joinToString("\n") { it.text }
        val serviceName = when {
            targetPackage == YES24_PACKAGE -> "YES24"
            targetPackage == KYOBO_PACKAGE -> "교보문고"
            targetPackage == ALADIN_PACKAGE -> "알라딘 eBook"
            else -> "지원하지 않는 앱"
        }

        if (targetPackage !in SUPPORTED_PACKAGES) {
            return "$serviceName 화면은 아직 책 후보 추출 대상이 아닙니다."
        }

        if (fullText.hasBlockingScreenText()) {
            return "$serviceName: 책 검색 화면이 아닙니다.\n검색 결과나 책 목록 화면으로 이동한 뒤 다시 실행하세요."
        }

        val candidates = lines.extractBookCandidates()
            .distinctBy { it.title.normalizedForCompare() }
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
                append(candidate.title)
                candidate.author?.let {
                    append("\n   저자: ")
                    append(it)
                }
                candidate.publisher?.let {
                    append("\n   출판사: ")
                    append(it)
                }
                candidate.price?.let {
                    append("\n   가격: ")
                    append(it)
                }
            }
        }
    }

    private fun List<SearchLine>.extractBookCandidates(): List<BookCandidate> {
        val candidates = mutableListOf<BookCandidate>()
        var index = 0

        while (index < size) {
            val line = this[index]
            if (!line.looksLikeBookTitle()) {
                index++
                continue
            }

            val nearbyLines = drop(index + 1).take(BOOK_DETAIL_LOOKAHEAD_COUNT)
            val metadataLine = nearbyLines.firstOrNull { it.text.looksLikeAuthorPublisherLine() }
            val priceLine = nearbyLines.firstOrNull { it.text.looksLikePriceLine() }

            if (metadataLine == null && priceLine == null) {
                index++
                continue
            }

            val metadata = metadataLine?.text?.parseAuthorPublisher()
            candidates.add(
                BookCandidate(
                    title = line.text.removeBookTypePrefix(),
                    author = metadata?.author,
                    publisher = metadata?.publisher,
                    price = priceLine?.text?.extractPrice()
                )
            )

            val consumedLine = listOfNotNull(metadataLine, priceLine).maxByOrNull { it.top } ?: line
            index = indexOf(consumedLine).takeIf { it >= index }?.plus(1) ?: index + 1
        }

        if (candidates.isNotEmpty()) return candidates

        return filter { it.looksLikeBookTitle() }
            .map { BookCandidate(title = it.text.removeBookTypePrefix()) }
    }

    private fun SearchLine.looksLikeBookTitle(): Boolean {
        val text = text.trim()
        if (text.length < MIN_CANDIDATE_LENGTH) return false
        if (text.length > MAX_CANDIDATE_LENGTH) return false
        if (text.anyNoiseTerm()) return false
        if (text.isMostlyNumberOrSymbol()) return false
        if (text.matches(BOOK_COUNT_PATTERN)) return false
        if (text.matches(PERCENT_PATTERN)) return false
        if (text.looksLikePriceLine()) return false
        if (text.looksLikeAuthorPublisherLine()) return false
        if (text.contains("지음") || text.contains("옮김") || text.contains("저자")) return false
        if (text.contains("리뷰") || text.contains("평점") || text.contains("다운로드")) return false
        return true
    }

    private fun String.looksLikeAuthorPublisherLine(): Boolean {
        return contains("저자") ||
            contains("지음") ||
            contains("글)") ||
            contains("출판사") ||
            contains(" · ") ||
            contains(" | ")
    }

    private fun String.looksLikePriceLine(): Boolean {
        return PRICE_PATTERN.containsMatchIn(this)
    }

    private fun String.extractPrice(): String? {
        return PRICE_PATTERN.find(this)?.value
    }

    private fun String.removeBookTypePrefix(): String {
        return replace(BOOK_TYPE_PREFIX_PATTERN, "")
            .trim()
    }

    private fun String.parseAuthorPublisher(): BookMetadata {
        val parts = split(" · ", " | ")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val author = parts.firstOrNull()
            ?.replace("저자(글)", "")
            ?.replace("저자", "")
            ?.replace("지음", "")
            ?.replace("글)", "")
            ?.replace("(글)", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val publisher = parts.drop(1)
            .firstOrNull()
            ?.replace("출판사", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return BookMetadata(author, publisher)
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

    private data class SearchLine(
        val text: String,
        val top: Int,
        val left: Int
    )

    private data class BookCandidate(
        val title: String,
        val author: String? = null,
        val publisher: String? = null,
        val price: String? = null
    )

    private data class BookMetadata(
        val author: String?,
        val publisher: String?
    )

    companion object {
        private const val YES24_PACKAGE = "com.yes24.ebook.fourth"
        private const val KYOBO_PACKAGE = "mok.android"
        private const val ALADIN_PACKAGE = "kr.co.aladin.ebook"
        private const val MIN_CANDIDATE_LENGTH = 2
        private const val MAX_CANDIDATE_LENGTH = 40
        private const val MAX_CANDIDATE_COUNT = 8
        private const val BOOK_DETAIL_LOOKAHEAD_COUNT = 6

        private val SUPPORTED_PACKAGES = setOf(
            YES24_PACKAGE,
            KYOBO_PACKAGE,
            ALADIN_PACKAGE
        )

        private val BOOK_COUNT_PATTERN = Regex(""".*\(\d+\).*""")
        private val PERCENT_PATTERN = Regex("""^\d{1,3}%$""")
        private val PRICE_PATTERN = Regex("""\d{1,3}(,\d{3})*원""")
        private val BOOK_TYPE_PREFIX_PATTERN = Regex("""^\[[^\]]+]\s*""")

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
            "나의 평생 e서재",
            "책을 보다",
            "책을 라이프하다"
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
            "교보문고",
            "KYOBoeBook",
            "건강을 위한 도서",
            "해외여행",
            "심리학 도서",
            "자기 개발",
            "검색",
            "search button",
            "cart",
            "메뉴",
            "탭바",
            "화면",
            "기능 제공",
            "사용하는 모든 단말",
            "연관",
            "인기순",
            "필터",
            "추천",
            "교보문고",
            "국내도서",
            "컴퓨터/IT",
            "IT/프로그래밍",
            "JAVA",
            "C++",
            "C#",
            "드론",
            "Visual Basic",
            "파이썬",
            "언리얼"
        )
    }
}
