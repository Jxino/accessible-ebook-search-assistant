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

    internal fun analyzeRecognizedTextLines(recognizedLines: List<String>, targetPackage: String): String {
        val lines = recognizedLines
            .mapIndexedNotNull { index, rawText ->
                val text = rawText.cleanOcrText()
                if (text.isBlank()) null else SearchLine(text, index, 0)
            }

        return analyzeLines(lines, targetPackage)
    }

    private fun analyzeLines(lines: List<SearchLine>, targetPackage: String): String {
        val fullText = lines.joinToString("\n") { it.text }
        val serviceName = when {
            targetPackage == YES24_PACKAGE -> "YES24"
            targetPackage == KYOBO_PACKAGE -> "교보문고"
            targetPackage == ALADIN_PACKAGE -> "알라딘"
            else -> "지원하지 않는 앱"
        }

        if (targetPackage !in SUPPORTED_PACKAGES) {
            return "$serviceName 화면은 아직 책 후보 추출 대상이 아닙니다."
        }

        if (fullText.hasBlockingScreenText()) {
            return "$serviceName: 책 검색 화면이 아닙니다.\n검색 결과나 책 목록 화면으로 이동한 뒤 다시 실행하세요."
        }

        val candidates = lines.extractBookCandidates(targetPackage)
            .preferCompleteCandidates()
            .filter { it.isComplete() }
            .sortedBy { it.order }
            .distinctBy { it.title.normalizedForCompare() }
            .take(MAX_CANDIDATE_COUNT)

        if (candidates.isEmpty()) {
            return "$serviceName: 완전한 책 정보를 찾지 못했습니다.\n책 제목, 저자, 출판사, 가격이 모두 보이도록 스크롤한 뒤 다시 실행하세요."
        }

        return buildString {
            append("$serviceName 책 후보 ${candidates.size}개")
            candidates.forEachIndexed { index, candidate ->
                append('\n')
                append(index + 1)
                append(". ")
                append(candidate.title)
                append("\n   저자: ")
                append(candidate.author)
                append("\n   출판사: ")
                append(candidate.publisher)
                append("\n   가격: ")
                append(candidate.price)
            }
        }
    }

    private fun List<SearchLine>.extractBookCandidates(targetPackage: String): List<BookCandidate> {
        extractProductDetailCandidate()?.let { return listOf(it) }

        val candidates = extractBookCandidatesFromMetadata(targetPackage).toMutableList()
        candidates.addAll(extractBookCandidatesFromPrice(targetPackage))

        if (candidates.isNotEmpty()) {
            return candidates.distinctByStrongestSignal()
        }

        var index = 0

        while (index < size) {
            val line = this[index]
            if (!line.looksLikeBookTitle()) {
                index++
                continue
            }

            val nearbyLines = detailLinesAfterTitle(index)
            val metadataLine = nearbyLines.firstOrNull { it.text.looksLikeAuthorPublisherLine() }
            val priceLine = nearbyLines.firstOrNull { it.text.extractBookPrice() != null }

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
                    price = priceLine?.text?.extractBookPrice(),
                    order = line.top,
                    titleFromBookTypePrefix = line.text.hasBookTypePrefix()
                )
            )

            val consumedLine = listOfNotNull(metadataLine, priceLine).maxByOrNull { it.top } ?: line
            index = indexOf(consumedLine).takeIf { it >= index }?.plus(1) ?: index + 1
        }

        if (candidates.isNotEmpty()) {
            return candidates.distinctByStrongestSignal()
        }

        return filter { it.looksLikeBookTitle() }
            .map {
                BookCandidate(
                    title = it.text.removeBookTypePrefix(),
                    order = it.top,
                    titleFromBookTypePrefix = it.text.hasBookTypePrefix()
                )
            }
    }

    private fun List<SearchLine>.extractBookCandidatesFromPrice(targetPackage: String): List<BookCandidate> {
        return mapIndexedNotNull { index, line ->
            val price = line.text.extractBookPrice() ?: return@mapIndexedNotNull null
            val startIndex = (index - MAX_BOOK_DETAIL_LOOKAHEAD_COUNT).coerceAtLeast(0)
            val previousLines = subList(startIndex, index)
            val metadataIndexInWindow = previousLines.indexOfLast { it.text.looksLikeAuthorPublisherLine() }
            if (metadataIndexInWindow < 0) return@mapIndexedNotNull null

            val metadataIndex = startIndex + metadataIndexInWindow
            val metadata = this[metadataIndex].text.parseAuthorPublisher()
            val titleCandidate = findTitleBeforeMetadata(metadataIndex, targetPackage) ?: return@mapIndexedNotNull null
            val publisher = metadata.publisher ?: findPublisherAfterMetadata(metadataIndex)

            BookCandidate(
                title = titleCandidate.title,
                author = metadata.author,
                publisher = publisher,
                price = price,
                order = titleCandidate.order,
                titleFromBookTypePrefix = titleCandidate.fromBookTypePrefix
            )
        }
    }

    private fun List<BookCandidate>.preferCompleteCandidates(): List<BookCandidate> {
        val preferred = sortedWith(
            compareByDescending<BookCandidate> { it.metadataScore() }
                .thenByDescending { it.titleQualityScore() }
                .thenBy { it.order }
        ).fold(mutableListOf<BookCandidate>()) { kept, candidate ->
            val normalizedTitle = candidate.title.normalizedForCompare()
            val overlapsWithBetterCandidate = kept.any { keptCandidate ->
                val keptTitle = keptCandidate.title.normalizedForCompare()
                val sameMetadata = candidate.sameBookMetadataAs(keptCandidate)
                sameMetadata || normalizedTitle in keptTitle || keptTitle in normalizedTitle
            }
            if (!overlapsWithBetterCandidate) kept.add(candidate)
            kept
        }

        return preferred.sortedBy { it.order }
    }

    private fun List<BookCandidate>.distinctByStrongestSignal(): List<BookCandidate> {
        return sortedWith(
            compareByDescending<BookCandidate> { it.metadataScore() }
                .thenByDescending { it.titleQualityScore() }
                .thenBy { it.order }
        ).distinctBy { it.title.normalizedForCompare() }
            .sortedBy { it.order }
    }

    private fun List<SearchLine>.extractBookCandidatesFromMetadata(targetPackage: String): List<BookCandidate> {
        return mapIndexedNotNull { index, line ->
            if (!line.text.looksLikeAuthorPublisherLine()) return@mapIndexedNotNull null

            val titleCandidate = findTitleBeforeMetadata(index, targetPackage) ?: return@mapIndexedNotNull null
            val metadata = line.text.parseAuthorPublisher()
            val publisher = metadata.publisher ?: findPublisherAfterMetadata(index)
            val price = drop(index + 1)
                .take(MAX_BOOK_DETAIL_LOOKAHEAD_COUNT)
                .firstOrNull { it.text.extractBookPrice() != null }
                ?.text
                ?.extractBookPrice()

            if (metadata.author == null && metadata.publisher == null && price == null) {
                return@mapIndexedNotNull null
            }

            BookCandidate(
                title = titleCandidate.title,
                author = metadata.author,
                publisher = publisher,
                price = price,
                order = titleCandidate.order,
                titleFromBookTypePrefix = titleCandidate.fromBookTypePrefix
            )
        }
    }

    private fun List<SearchLine>.findTitleBeforeMetadata(
        metadataIndex: Int,
        targetPackage: String
    ): TitleCandidate? {
        val startIndex = (metadataIndex - MAX_TITLE_LOOKBEHIND_COUNT).coerceAtLeast(0)
        val previousLines = subList(startIndex, metadataIndex)
            .takeLastWhile { !it.text.looksLikePriceLine() }

        val anchorIndex = previousLines.indexOfLast { it.text.hasBookTypePrefix() }
        if (anchorIndex >= 0) {
            val anchor = previousLines[anchorIndex]
            val title = buildTitleFromAnchor(previousLines.drop(anchorIndex), targetPackage)
            return TitleCandidate(title, anchor.top, fromBookTypePrefix = true)
        }

        return previousLines
            .asReversed()
            .firstOrNull { it.looksLikeBookTitle() }
            ?.let { TitleCandidate(it.text.removeBookTypePrefix(), it.top, it.text.hasBookTypePrefix()) }
    }

    private fun buildTitleFromAnchor(linesFromAnchor: List<SearchLine>, targetPackage: String): String {
        val titleParts = mutableListOf(linesFromAnchor.first().text.removeBookTypePrefix())
        for (line in linesFromAnchor.drop(1)) {
            val text = line.text.trim()
            if (!text.looksLikeTitleContinuation(targetPackage, titleParts.last())) break
            titleParts.add(text)
        }
        return titleParts.joinToString(" ").compactBrokenTitle()
    }

    private fun List<SearchLine>.findPublisherAfterMetadata(metadataIndex: Int): String? {
        return drop(metadataIndex + 1)
            .takeWhile { !it.text.looksLikePriceLine() }
            .take(MAX_BOOK_DETAIL_LOOKAHEAD_COUNT)
            .firstNotNullOfOrNull { it.text.extractStandalonePublisher() }
    }

    private fun List<SearchLine>.detailLinesAfterTitle(titleIndex: Int): List<SearchLine> {
        val details = mutableListOf<SearchLine>()
        for (nextIndex in titleIndex + 1 until size) {
            val nextLine = this[nextIndex]
            if (nextLine.looksLikeBookTitle() && details.hasEnoughBookMetadata()) {
                break
            }
            details.add(nextLine)
            if (details.size >= MAX_BOOK_DETAIL_LOOKAHEAD_COUNT) break
        }
        return details
    }

    private fun List<SearchLine>.hasEnoughBookMetadata(): Boolean {
        return any { it.text.looksLikeAuthorPublisherLine() } ||
            any { it.text.looksLikePriceLine() }
    }

    private fun List<SearchLine>.extractProductDetailCandidate(): BookCandidate? {
        if (none { line -> line.text.contains(" | ") && line.text.contains("교보문고") }) {
            return null
        }

        val authorMarkerIndex = indexOfFirst { line ->
            line.text.contains("저자") ||
                line.text.contains("지음") ||
                line.text.contains("옮김")
        }
        if (authorMarkerIndex <= 0) return null
        if (this[authorMarkerIndex].text.contains(" | ") ||
            this[authorMarkerIndex].text.contains(" · ")
        ) {
            return null
        }

        val title = extractDetailTitle(authorMarkerIndex) ?: return null
        val author = extractDetailAuthor(authorMarkerIndex)
        val publisher = extractDetailPublisher(authorMarkerIndex)
        val price = firstOrNull { it.text.extractBookPrice() != null }?.text?.extractBookPrice()

        if (author == null && publisher == null && price == null) return null
        return BookCandidate(title, author, publisher, price, order = this[authorMarkerIndex].top)
    }

    private fun List<SearchLine>.extractDetailTitle(authorMarkerIndex: Int): String? {
        firstOrNull { line ->
            line.text.contains(" | ") && line.text.contains("교보문고")
        }?.text
            ?.substringBefore(" | ")
            ?.trim()
            ?.takeIf { it.looksLikeDetailTitle() }
            ?.let { return it }

        return take(authorMarkerIndex)
            .asReversed()
            .firstOrNull { it.text.looksLikeDetailTitle() }
            ?.text
    }

    private fun List<SearchLine>.extractDetailAuthor(authorMarkerIndex: Int): String? {
        val marker = this[authorMarkerIndex].text
        if (!marker.isAuthorMarkerOnly()) {
            return marker
                .replace("저자(글)", "")
                .replace("저자", "")
                .replace("지음", "")
                .replace("옮김", "")
                .trim()
                .takeIf { it.isNotBlank() }
        }

        return getOrNull(authorMarkerIndex - 1)
            ?.text
            ?.takeIf { it.looksLikePersonName() }
    }

    private fun List<SearchLine>.extractDetailPublisher(authorMarkerIndex: Int): String? {
        return drop(authorMarkerIndex + 1)
            .firstOrNull { line ->
                val text = line.text.trim()
                text.isNotBlank() &&
                    !text.contains("저자") &&
                    !text.contains("주간베스트") &&
                    !text.contains("국내도서") &&
                    !text.contains("리뷰") &&
                    !text.contains("평점") &&
                    !text.looksLikePriceLine() &&
                    !text.looksLikeDateLine()
            }
            ?.text
            ?.substringBefore(" · ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun SearchLine.looksLikeBookTitle(): Boolean {
        val text = text.trim()
        val titleText = text.removeBookTypePrefix()
        if (titleText.length < MIN_CANDIDATE_LENGTH) return false
        if (titleText.length > MAX_CANDIDATE_LENGTH) return false
        if (titleText.anyNoiseTerm()) return false
        if (titleText.isMostlyNumberOrSymbol()) return false
        if (titleText.matches(BOOK_COUNT_PATTERN)) return false
        if (titleText.matches(PERCENT_PATTERN)) return false
        if (titleText.looksLikePriceLine()) return false
        if (titleText.looksLikeDateLine()) return false
        if (titleText.contains(" • ")) return false
        if (titleText.looksLikeAuthorPublisherLine()) return false
        if (titleText.looksLikeDescriptionLine()) return false
        if (titleText.contains("지음") || titleText.contains("옮김") || titleText.contains("저자")) return false
        if (titleText.contains("리뷰") || titleText.contains("평점") || titleText.contains("다운로드")) return false
        if (titleText.contains("이미지") || titleText.contains("미리보기") || titleText.contains("바로구매")) return false
        if (titleText.contains("장바구니") || titleText.equals("cart", ignoreCase = true)) return false
        if (titleText.looksLikeDeliveryOrBenefitLine()) return false
        return true
    }

    private fun String.looksLikeAuthorPublisherLine(): Boolean {
        if (startsWithOverlayResultLabel()) return false
        return contains("저자") ||
            contains("지음") ||
            contains("지은이") ||
            contains("글)") ||
            contains("공저") ||
            contains("공역") ||
            endsWith(" 저") ||
            contains(" 저,") ||
            contains(" 역") ||
            contains("출판사")
    }

    private fun String.looksLikePriceLine(): Boolean {
        return PRICE_PATTERN.containsMatchIn(this)
    }

    private fun String.looksLikeDateLine(): Boolean {
        return DATE_PATTERN.containsMatchIn(this)
    }

    private fun String.extractBookPrice(): String? {
        if (looksLikePointOrRewardLine()) return null
        return PRICE_PATTERN.findAll(this).map { it.value }.firstOrNull()
    }

    private fun String.looksLikePointOrRewardLine(): Boolean {
        val normalized = replace(" ", "")
        return normalized.startsWith("P") ||
            contains("포인트") ||
            contains("적립") ||
            contains("마일리지") ||
            contains("point", ignoreCase = true)
    }

    private fun String.startsWithOverlayResultLabel(): Boolean {
        val normalized = trim()
        return normalized.startsWith("저자:") ||
            normalized.startsWith("출판사:") ||
            normalized.startsWith("가격:")
    }

    private fun String.removeBookTypePrefix(): String {
        return replace(BOOK_TYPE_PREFIX_PATTERN, "")
            .trim()
    }

    private fun String.hasBookTypePrefix(): Boolean {
        return BOOK_TYPE_PREFIX_PATTERN.containsMatchIn(trim())
    }

    private fun String.looksLikeDeliveryOrBenefitLine(): Boolean {
        return DELIVERY_AND_BENEFIT_TERMS.any { contains(it, ignoreCase = true) } ||
            Regex("""외\s*\d+건""").containsMatchIn(this)
    }

    private fun String.parseAuthorPublisher(): BookMetadata {
        parseCoauthorLine()?.let { return it }
        parseAuthorTranslatorLine()?.let { return it }

        val parts = split(" · ", " | ")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (parts.size < 2) {
            parseAuthorPublisherWithoutSeparator()?.let { return it }
        }

        val author = parts.firstOrNull()
            ?.replace("저자(글)", "")
            ?.replace("저자", "")
            ?.replace("(지은이)", "")
            ?.replace("지은이", "")
            ?.replace(Regex("""\s저$"""), "")
            ?.replace("지음", "")
            ?.replace("글)", "")
            ?.replace("(글)", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val publisher = parts.drop(1)
            .lastOrNull()
            ?.cleanPublisherText()
            ?.takeIf { it.isNotBlank() }

        return BookMetadata(author, publisher)
    }

    private fun String.parseAuthorPublisherWithoutSeparator(): BookMetadata? {
        val marker = AUTHOR_MARKER_PATTERN.find(this) ?: return null
        val author = substring(0, marker.range.first)
            .trim()
            .takeIf { it.isNotBlank() }
        val publisher = substring(marker.range.last + 1)
            .replace(TRANSLATOR_PATTERN, "")
            .cleanPublisherText()
            .takeIf { it.isNotBlank() }

        if (author == null && publisher == null) return null
        return BookMetadata(author, publisher)
    }

    private fun String.parseAuthorTranslatorLine(): BookMetadata? {
        if (!contains(" 저,") && !endsWith(" 저")) return null
        val author = replace(Regex("""\s저(?=,|$)"""), "")
            .trim()
            .takeIf { it.isNotBlank() }
        return BookMetadata(author, publisher = null)
    }

    private fun String.parseCoauthorLine(): BookMetadata? {
        if (!contains("공저") && !contains("공역")) return null
        val author = substringBefore("/")
            .replace("공저", "")
            .replace("공역", "")
            .replace("저자(글)", "")
            .replace("저자", "")
            .replace("(지은이)", "")
            .replace("지은이", "")
            .replace("지음", "")
            .trim()
            .takeIf { it.isNotBlank() }
        return author?.let { BookMetadata(it, publisher = null) }
    }

    private fun String.isAuthorMarkerOnly(): Boolean {
        val normalized = replace(" ", "")
        return normalized == "저자(글)" ||
            normalized == "저자" ||
            normalized == "(지은이)" ||
            normalized == "지은이" ||
            normalized == "저" ||
            normalized == "지음" ||
            normalized == "옮김"
    }

    private fun String.looksLikeTitleContinuation(targetPackage: String, previousPart: String): Boolean {
        if (isBlank()) return false
        if (looksLikeAuthorPublisherLine() || looksLikePriceLine()) return false
        if (anyNoiseTerm() || looksLikeDeliveryOrBenefitLine()) return false
        if (targetPackage == KYOBO_PACKAGE && looksLikeDescriptionLine()) return false
        if (length > MAX_TITLE_CONTINUATION_LENGTH) return false
        if (targetPackage == KYOBO_PACKAGE && matches(Regex("""^\d{1,2}$"""))) return true
        if (targetPackage == KYOBO_PACKAGE && previousPart.trimEnd().endsWith(",")) return true
        if (targetPackage == KYOBO_PACKAGE && previousPart.trimEnd().endsWith("+")) return true
        if (targetPackage == KYOBO_PACKAGE && previousPart.trimEnd().endsWith("자바스") && trim() == "크립트") return true
        if (targetPackage == KYOBO_PACKAGE && previousPart.contains("개발자가") && contains("wi")) return true
        if (isMostlyNumberOrSymbol()) return false
        if (targetPackage != KYOBO_PACKAGE && (previousPart.endsWith("...") || previousPart.endsWith("wi"))) return true
        if (targetPackage == KYOBO_PACKAGE) return false
        if (previousPart.lastOrNull()?.isLetterOrDigit() == true && firstOrNull()?.isLetterOrDigit() == true) {
            return length <= MAX_BROKEN_TITLE_LINE_LENGTH
        }
        return false
    }

    private fun String.compactBrokenTitle(): String {
        return replace("A I ", "AI ")
            .replace("wi th", "with")
            .replace("H BM", "HBM")
            .replace("코 드", "코드")
            .replace("자바스 크립트", "자바스크립트")
            .replace("설 계", "설계")
            .replace("업무까 지", "업무까지")
            .replace(Regex("""(\d{2})\s+(\d{2})"""), "$1$2")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.extractStandalonePublisher(): String? {
        val cleaned = cleanPublisherText()
        if (cleaned.isBlank()) return null
        if (cleaned.startsWithOverlayResultLabel()) return null
        if (cleaned.looksLikeDateLine() || cleaned.looksLikePriceLine()) return null
        if (cleaned.anyNoiseTerm() || cleaned.looksLikeDeliveryOrBenefitLine()) return null
        if (cleaned.contains("할인") || cleaned.contains("원)") || cleaned.contains("평점")) return null
        return cleaned.takeIf { it.length <= MAX_PUBLISHER_LENGTH }
    }

    private fun String.cleanPublisherText(): String {
        return substringBefore(" | ")
            .substringBefore(" • ")
            .substringBefore(" · ")
            .removePrefix("출판사:")
            .removePrefix("출판사")
            .trim()
    }

    private fun String.looksLikeDetailTitle(): Boolean {
        if (length < MIN_CANDIDATE_LENGTH) return false
        if (length > MAX_CANDIDATE_LENGTH) return false
        if (anyNoiseTerm()) return false
        if (isMostlyNumberOrSymbol()) return false
        if (matches(BOOK_COUNT_PATTERN)) return false
        if (matches(PERCENT_PATTERN)) return false
        if (looksLikePriceLine()) return false
        if (looksLikeAuthorPublisherLine()) return false
        if (contains("대표") || contains("이미지") || contains("사이즈")) return false
        if (contains("교보문고")) return false
        return true
    }

    private fun String.looksLikePersonName(): Boolean {
        if (isBlank()) return false
        if (length > MAX_PERSON_NAME_LENGTH) return false
        if (looksLikePriceLine() || looksLikeDateLine()) return false
        if (anyNoiseTerm()) return false
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

    private fun String.looksLikeDescriptionLine(): Boolean {
        return DESCRIPTION_LINE_TERMS.any { contains(it, ignoreCase = true) }
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

    private data class TitleCandidate(
        val title: String,
        val order: Int,
        val fromBookTypePrefix: Boolean
    )

    private data class BookCandidate(
        val title: String,
        val author: String? = null,
        val publisher: String? = null,
        val price: String? = null,
        val order: Int = Int.MAX_VALUE,
        val titleFromBookTypePrefix: Boolean = false
    ) {
        fun metadataScore(): Int {
            return listOf(author, publisher, price).count { !it.isNullOrBlank() }
        }

        fun isComplete(): Boolean {
            return title.isNotBlank() &&
                !title.contains("...") &&
                !title.startsWith(":") &&
                !author.isNullOrBlank() &&
                !publisher.isNullOrBlank() &&
                !price.isNullOrBlank()
        }

        fun titleQualityScore(): Int {
            val looksLikeDescription = DESCRIPTION_LINE_TERMS.any { title.contains(it, ignoreCase = true) }
            var score = title.length
            if (titleFromBookTypePrefix) score += 100
            if (looksLikeDescription) score -= 100
            if (title.startsWith(":")) score -= 100
            if (title.contains("...")) score -= 100
            return score
        }

        fun sameBookMetadataAs(other: BookCandidate): Boolean {
            return !author.isNullOrBlank() &&
                !publisher.isNullOrBlank() &&
                !price.isNullOrBlank() &&
                author == other.author &&
                publisher == other.publisher &&
                price == other.price
        }
    }

    private data class BookMetadata(
        val author: String?,
        val publisher: String?
    )

    companion object {
        private const val YES24_PACKAGE = "com.yes24.commerce"
        private const val KYOBO_PACKAGE = "mok.android"
        private const val ALADIN_PACKAGE = "kr.co.aladin.third_shop"
        private const val MIN_CANDIDATE_LENGTH = 2
        private const val MAX_CANDIDATE_LENGTH = 40
        private const val MAX_CANDIDATE_COUNT = 8
        private const val MAX_BOOK_DETAIL_LOOKAHEAD_COUNT = 14
        private const val MAX_TITLE_LOOKBEHIND_COUNT = 10
        private const val MAX_TITLE_CONTINUATION_LENGTH = 45
        private const val MAX_BROKEN_TITLE_LINE_LENGTH = 28
        private const val MAX_PUBLISHER_LENGTH = 30

        private val SUPPORTED_PACKAGES = setOf(
            YES24_PACKAGE,
            KYOBO_PACKAGE,
            ALADIN_PACKAGE
        )

        private val BOOK_COUNT_PATTERN = Regex(""".*\(\d+\).*""")
        private val PERCENT_PATTERN = Regex("""^\d{1,3}%$""")
        private val PRICE_PATTERN = Regex("""\d{1,3}(,\d{3})*원""")
        private val DATE_PATTERN = Regex("""(\d{4}년\s*\d{1,2}월\s*\d{1,2}일|\d{4}\.\d{1,2}\.\d{1,2}\.?)""")
        private val BOOK_TYPE_PREFIX_PATTERN = Regex("""^\[[^\]]+]\s*""")
        private val AUTHOR_MARKER_PATTERN = Regex("""\s*(저자\(글\)|저자|\(지은이\)|지은이|지음|글\)|\(글\)|저)\s*""")
        private val TRANSLATOR_PATTERN = Regex("""\s*[^·|]+\s*(번역|옮김)\s*""")
        private const val MAX_PERSON_NAME_LENGTH = 30

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
            "eBook",
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
            "재검색",
            "분야",
            "AI 활용",
            "상품태그",
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
            "드론",
            "IT/프로그래밍"
        )

        private val DELIVERY_AND_BENEFIT_TERMS = listOf(
            "내일",
            "도착",
            "출고",
            "배송",
            "무료배송",
            "사은품",
            "이벤트",
            "오늘의 선택",
            "MD의 선택",
            "추천해요",
            "도움돼요",
            "바로 이 책",
            "리딩 클럽",
            "퍼펙트 데이즈",
            "주간베스트",
            "쿠폰",
            "적용가"
        )

        private val DESCRIPTION_LINE_TERMS = listOf(
            "기초부터",
            "반응형",
            "자습서",
            "전문서",
            "설명",
            "업무까지",
            "실무 활용법",
            "인공지능, 에이전트",
            "커넥터",
            "플러그인",
            "아티팩트",
            "첫 코딩"
        )
    }
}
