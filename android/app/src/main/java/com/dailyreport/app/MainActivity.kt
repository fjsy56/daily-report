package com.dailyreport.app

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1A1A2E),
                    secondary = Color(0xFF16213E),
                    surface = Color(0xFFF5F0E8),
                    background = Color(0xFFF5F0E8),
                )
            ) {
                DailyReportApp(
                    onWebViewReady = { wv -> webView = wv },
                    onShareScreenshot = { shareScreenshot() },
                    onExportWord = { exportToWord() }
                )
            }
        }
    }

    private fun shareScreenshot() {
        val wv = webView ?: return
        val scope = kotlinx.coroutines.MainScope()

        scope.launch {
            try {
                // Capture WebView content as bitmap
                val bitmap = withContext(Dispatchers.Main) {
                    val bmp = Bitmap.createBitmap(wv.width, wv.contentHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    wv.draw(canvas)
                    bmp
                }

                // Save to cache for sharing
                val cacheDir = File(cacheDir, "screenshots")
                cacheDir.mkdirs()
                val file = File(cacheDir, "meiriyibao_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    file
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享每日一报"))
                bitmap.recycle()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "截图失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportToWord() {
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            try {
                val docxFile = withContext(Dispatchers.IO) {
                    generateDocx()
                }

                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        docxFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "导出每日一报"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun generateDocx(): File {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val fileName = "每日一报_$dateStr.docx"

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, fileName)
        downloadsDir.mkdirs()

        // Minimal valid .docx structure
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            // [Content_Types].xml
            zip.putEntry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray())

            // _rels/.rels
            zip.putEntry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".toByteArray())

            // word/_rels/document.xml.rels
            zip.putEntry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>""".toByteArray())

            // word/document.xml - the actual content
            val title = "每日一报 · 科技速览"
            val content = """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="56"/></w:rPr><w:t>$title</w:t></w:r></w:p>
                    <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:sz w:val="24"/><w:color w:val="808080"/></w:rPr><w:t>$dateStr</w:t></w:r></w:p>
                    <w:p><w:r><w:br w:type="page"/></w:r></w:p>
                    <w:p><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t>来源网站</w:t></w:r></w:p>
                    <w:p><w:r><w:t>虎嗅 · 36氪 · 机器之心 · 极客公园 · 量子位 · 新智元 · IT之家</w:t></w:r></w:p>
                    <w:p><w:r><w:rPr><w:sz w:val="20"/><w:color w:val="808080"/></w:rPr><w:t>访问 meiriyibao.netlify.app 查看完整内容</w:t></w:r></w:p>
                    <w:p><w:r><w:br w:type="page"/></w:r></w:p>
                    <w:p><w:r><w:rPr><w:b/><w:sz w:val="28"/></w:rPr><w:t>导出说明</w:t></w:r></w:p>
                    <w:p><w:r><w:t>本文档由「每日一报」Android 应用自动生成。</w:t></w:r></w:p>
                    <w:p><w:r><w:t>完整新闻内容请访问: https://meiriyibao.netlify.app</w:t></w:r></w:p>
                    <w:p><w:r><w:t>数据来源：虎嗅、36氪、机器之心、极客公园、量子位、新智元、IT之家</w:t></w:r></w:p>
                    <w:p><w:r><w:t>A股数据：东方财富</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent().toByteArray()

            zip.putEntry("word/document.xml", content)
            zip.closeEntry()
        }

        return file
    }

    private fun ZipOutputStream.putEntry(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReportApp(
    onWebViewReady: (WebView) -> Unit = {},
    onShareScreenshot: () -> Unit = {},
    onExportWord: () -> Unit = {}
) {
    var isSidebarOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        // Main WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        allowFileAccess = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // Open external links in browser
                            if (url.startsWith("https://meiriyibao.netlify.app") ||
                                url.startsWith("http://meiriyibao.netlify.app") ||
                                url.startsWith("http://localhost") ||
                                url.startsWith("https://localhost")) {
                                return false
                            }
                            // External links open in browser
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            ctx.startActivity(intent)
                            return true
                        }
                    }

                    webChromeClient = WebChromeClient()

                    loadUrl("https://meiriyibao.netlify.app")
                    onWebViewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Sidebar toggle button (fixed top-right)
        FloatingActionButton(
            onClick = { isSidebarOpen = !isSidebarOpen },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(40.dp),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(50)
        ) {
            Icon(
                imageVector = if (isSidebarOpen) Icons.Default.Close else Icons.Default.MoreHoriz,
                contentDescription = if (isSidebarOpen) "关闭侧栏" else "打开侧栏",
                modifier = Modifier.size(24.dp)
            )
        }

        // Slide-in sidebar
        AnimatedVisibility(
            visible = isSidebarOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(220.dp)
        ) {
            SidebarContent(
                onShare = {
                    isSidebarOpen = false
                    onShareScreenshot()
                },
                onDownload = {
                    isSidebarOpen = false
                    onExportWord()
                }
            )
        }
    }
}

@Composable
fun SidebarContent(
    onShare: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "每日一报",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(32.dp))

            // Share button
            SidebarButton(
                icon = Icons.Default.Share,
                label = "分享截图",
                description = "将网页内容输出为图片发送到其他应用",
                onClick = onShare
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Download button
            SidebarButton(
                icon = Icons.Default.FileDownload,
                label = "导出Word",
                description = "将内容导出为Word文档保存至下载文件夹",
                onClick = onDownload
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "v1.0",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SidebarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

// Separate function for the screenshot-based share
// (called from sidebar or via deep integration with WebView JS)
private var cachedScreenshot: Bitmap? = null
