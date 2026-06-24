package com.example.mytest

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            val aladinBookSearchClient = remember { AladinBookSearchClient() }
            var query by remember { mutableStateOf("") }
            var isSearching by remember { mutableStateOf(false) }
            var searchMessage by remember { mutableStateOf("") }
            var books by remember { mutableStateOf<List<AladinBook>>(emptyList()) }
            val screenCaptureLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val data = result.data ?: return@rememberLauncherForActivityResult
                val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 오류가 났던 글자 크기(fontSize) 속성을 아예 빼버려서 에러 소지를 없앴습니다.
                Text(text = "이북 오버레이 설정 앱", modifier = Modifier.padding(16.dp))

                // 1번 버튼: 다른 앱 위에 표시 권한
                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
                ) {
                    Text("1. 다른 앱 위에 표시 권한 켜기")
                }

                // 2번 버튼: 접근성 권한
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
                ) {
                    Text("2. 접근성 권한 켜기 (MyTest 활성화)")
                }

                Button(
                    onClick = {
                        val projectionManager = context.getSystemService(
                            android.media.projection.MediaProjectionManager::class.java
                        )
                        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
                ) {
                    Text("3. 화면 캡처 OCR 권한 켜기")
                }

                Text(
                    text = "알라딘 eBook 검색",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 16.dp)
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("책 제목, 저자, 키워드") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 4.dp)
                )

                Button(
                    onClick = {
                        isSearching = true
                        searchMessage = ""
                        books = emptyList()
                        coroutineScope.launch {
                            val result = aladinBookSearchClient.searchEbooks(query)
                            result
                                .onSuccess { foundBooks ->
                                    books = foundBooks
                                    searchMessage = if (foundBooks.isEmpty()) {
                                        "검색 결과가 없습니다."
                                    } else {
                                        "알라딘 eBook ${foundBooks.size}개를 찾았습니다."
                                    }
                                }
                                .onFailure { error ->
                                    searchMessage = error.message ?: "알라딘 검색에 실패했습니다."
                                }
                            isSearching = false
                        }
                    },
                    enabled = !isSearching,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
                ) {
                    Text(if (isSearching) "검색 중..." else "알라딘 eBook 검색")
                }

                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                }

                if (searchMessage.isNotBlank()) {
                    Text(
                        text = searchMessage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 8.dp)
                    )
                }

                books.forEach { book ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = book.title, style = MaterialTheme.typography.titleSmall)
                            if (book.author.isNotBlank()) {
                                Text(text = book.author)
                            }
                            val meta = listOf(book.publisher, book.pubDate)
                                .filter { it.isNotBlank() }
                                .joinToString(" | ")
                            if (meta.isNotBlank()) {
                                Text(text = meta)
                            }
                            if (book.isbn13.isNotBlank()) {
                                Text(text = "ISBN ${book.isbn13}")
                            }
                        }
                    }
                }
            }
        }
    }
}
