package com.example.mytest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AladinBookSearchClient(
    private val ttbKey: String = BuildConfig.ALADIN_TTB_KEY
) {
    suspend fun searchEbooks(query: String): Result<List<AladinBook>> = withContext(Dispatchers.IO) {
        runCatching {
            val keyword = query.trim()
            require(keyword.isNotBlank()) { "검색어를 입력해 주세요." }
            require(ttbKey.isNotBlank()) { "알라딘 API 키가 설정되지 않았습니다." }

            val json = requestSearch(keyword, searchTarget = "eBook")
            parseBooks(json)
        }
    }

    private fun requestSearch(query: String, searchTarget: String): JSONObject {
        val url = URL(
            "$BASE_URL?ttbkey=${ttbKey.urlEncode()}" +
                "&Query=${query.urlEncode()}" +
                "&QueryType=Keyword" +
                "&MaxResults=20" +
                "&start=1" +
                "&SearchTarget=$searchTarget" +
                "&output=js" +
                "&Version=20131101"
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
        }

        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (connection.responseCode !in 200..299) {
                error("알라딘 API 오류: HTTP ${connection.responseCode}")
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBooks(json: JSONObject): List<AladinBook> {
        val items = json.optJSONArray("item") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(
                    AladinBook(
                        title = item.optString("title").cleanHtmlText(),
                        author = item.optString("author").cleanHtmlText(),
                        publisher = item.optString("publisher").cleanHtmlText(),
                        pubDate = item.optString("pubDate").cleanHtmlText(),
                        isbn13 = item.optString("isbn13").cleanHtmlText(),
                        coverUrl = item.optString("cover").cleanHtmlText(),
                        link = item.optString("link").cleanHtmlText()
                    )
                )
            }
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun String.cleanHtmlText(): String {
        return replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    companion object {
        private const val BASE_URL = "https://www.aladin.co.kr/ttb/api/ItemSearch.aspx"
        private const val TIMEOUT_MS = 10_000
    }
}
