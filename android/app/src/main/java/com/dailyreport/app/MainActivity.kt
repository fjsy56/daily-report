package com.dailyreport.app

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    private var isPageLoaded = false

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
                    onPageLoaded = { isPageLoaded = true },
                    onShareScreenshot = { shareScreenshot() },
                    onExportWord = { exportToWord() }
                )
            }
        }
    }

    private fun shareScreenshot() {
        val wv = webView ?: return
        if (!isPageLoaded) {
            Toast.makeText(this, "页面正在加载，请稍候再试…", Toast.LENGTH_SHORT).show()
            return
        }

        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            try {
                Toast.makeText(this@MainActivity, "正在生成完整页面截图…", Toast.LENGTH_SHORT).show()

                val bitmap = withContext(Dispatchers.Main) {
                    val totalHeight = wv.contentHeight
                    val width = wv.measuredWidth
                    val bmp = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    wv.draw(canvas)
                    bmp
                }

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
        val wv = webView ?: return
        if (!isPageLoaded) {
            Toast.makeText(this, "页面正在加载，请稍候再试…", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在提取页面内容…", Toast.LENGTH_SHORT).show()

        wv.evaluateJavascript("""
            (function() {
                var d = window.__NEWS_DATA__;
                if (!d) return JSON.stringify({error:'no data'});
                var result = {date:d.date, news:[]};
                var catMap = {tech:'前沿科技', app:'科技应用', enterprise:'科技企业'};
                ['tech','app','enterprise'].forEach(function(cat) {
                    (d[cat]||[]).forEach(function(item) {
                        result.news.push({
                            cat: catMap[cat],
                            title: item.title,
                            tag: item.tag,
                            src: item.src,
                            excerpt: item.excerpt||'',
                            url: item.url
                        });
                    });
                });
                return JSON.stringify(result);
            })();
        """.trimIndent()) { jsonStr ->
            if (jsonStr == null || jsonStr == "null") {
                Toast.makeText(this, "无法提取页面数据", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            try {
                val clean = jsonStr.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                generateAndShareDocx(clean)
            } catch (e: Exception) {
                Toast.makeText(this, "生成Word失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateAndShareDocx(jsonStr: String) {
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            try {
                val docxFile = withContext(Dispatchers.IO) {
                    generateDocx(jsonStr)
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

    private fun generateDocx(jsonStr: String): File {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val fileName = "每日一报_$dateStr.docx"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, fileName)
        downloadsDir.mkdirs()

        // Parse news data
        val orgJson = org.json.JSONObject(jsonStr)
        val date = orgJson.optString("date", dateStr)
        val newsArr = orgJson.optJSONArray("news") ?: org.json.JSONArray()

        // Build document body
        val bodyBuilder = StringBuilder()
        bodyBuilder.append("""<w:body>""")

        // Title
        bodyBuilder.append(wp("center", wrb("每日一报 · 科技速览", sz=56)))
        bodyBuilder.append(wp("center", wr("$dateStr", sz=24, color="808080")))
        bodyBuilder.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")

        // Section: 来源
        bodyBuilder.append(wp("left", wrb("今日新闻快览", sz=32)))
        bodyBuilder.append(wp("left", wr("数据来源: 虎嗅 · 36氪 · 机器之心 · 极客公园 · 量子位 · 新智元 · IT之家", sz=20, color="808080")))
        bodyBuilder.append(wp("left", wr("共计 ${newsArr.length()} 条新闻", sz=20, color="808080")))
        bodyBuilder.append(spacer())

        // News list
        val catMap = mapOf("前沿科技" to "1572E8", "科技应用" to "2E8B57", "科技企业" to "CD853F")
        var currentCat = ""
        for (i in 0 until newsArr.length()) {
            val item = newsArr.getJSONObject(i)
            val cat = item.optString("cat", "")
            val title = xmlSafe(item.optString("title", ""))
            val tag = xmlSafe(item.optString("tag", ""))
            val src = xmlSafe(item.optString("src", ""))
            val excerpt = xmlSafe(item.optString("excerpt", ""))
            val url = xmlSafe(item.optString("url", ""))

            // Section header when category changes
            if (cat != currentCat) {
                currentCat = cat
                val catColor = catMap[cat] ?: "333333"
                bodyBuilder.append(wp("left", wrb(cat, sz=28, color=catColor)))
                bodyBuilder.append(spacer())
            }

            // News item: tag + title (bold) + excerpt + source
            bodyBuilder.append(wp("left",
                wr("[$tag] ", sz=20, color="666666", bold=true) +
                wrb(title, sz=22)
            ))
            if (excerpt.isNotEmpty()) {
                bodyBuilder.append(wp("left", wr(excerpt, sz=20, color="444444")))
            }
            bodyBuilder.append(wp("left", wr("来源: $src  |  $url", sz=18, color="AAAAAA")))
            bodyBuilder.append(spacer())
        }

        // Footer
        bodyBuilder.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
        bodyBuilder.append(wp("left", wrb("导出说明", sz=28)))
        bodyBuilder.append(wp("left", wr("本文档由「每日一报」Android 应用自动生成。", sz=20, color="666666")))
        bodyBuilder.append(wp("left", wr("完整实时版请访问: https://meiriyibao.netlify.app", sz=20, color="666666")))
        bodyBuilder.append(wp("left", wr("A股数据: 东方财富", sz=20, color="666666")))

        bodyBuilder.append("""</w:body>""")

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
${bodyBuilder}
</w:document>"""

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putEntry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray())

            zip.putEntry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".toByteArray())

            zip.putEntry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>""".toByteArray())

            zip.putEntry("word/document.xml", documentXml.toByteArray(Charsets.UTF_8))
        }

        return file
    }

    // ---- Docx XML helpers ----

    private fun xmlSafe(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
               .replace("\"", "&quot;").replace("'", "&apos;")
    }

    private fun wr(text: String, sz: Int = 20, color: String = "000000", bold: Boolean = false): String {
        val bTag = if (bold) "<w:b/>" else ""
        return "<w:r><w:rPr>$bTag<w:sz w:val=\"$sz\"/><w:color w:val=\"$color\"/></w:rPr><w:t xml:space=\"preserve\">$text</w:t></w:r>"
    }

    private fun wrb(text: String, sz: Int = 20, color: String = "000000"): String {
        return wr(text, sz, color, bold = true)
    }

    private fun wp(align: String, runs: String): String {
        val jc = when (align) { "center" -> """<w:jc w:val="center"/>""" else -> "" }
        return "<w:p><w:pPr>$jc</w:pPr>$runs</w:p>"
    }

    private fun spacer(): String {
        return "<w:p><w:r><w:br/></w:r></w:p>"
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
    onPageLoaded: () -> Unit = {},
    onShareScreenshot: () -> Unit = {},
    onExportWord: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp),
                drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "每日一报",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "meiriyibao.netlify.app",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(24.dp))

                    // Share button
                    SidebarButton(
                        icon = Icons.Default.Share,
                        label = "分享截图",
                        description = "生成网页完整长截图，发送到微信/QQ等应用",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onShareScreenshot()
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Download button
                    SidebarButton(
                        icon = Icons.Default.FileDownload,
                        label = "导出Word",
                        description = "将新闻标题、摘要、来源导出为Word文档",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onExportWord()
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "从左侧边缘右划打开",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
                    Text(
                        text = "v1.1",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    ) {
        // Main content: WebView + left edge hint
        Box(modifier = Modifier.fillMaxSize()) {
            // WebView
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

                        // Enable full-document drawing for long screenshot capture
                        enableSlowWholeDocumentDraw()

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                onPageLoaded()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith("https://meiriyibao.netlify.app") ||
                                    url.startsWith("http://meiriyibao.netlify.app") ||
                                    url.startsWith("http://localhost") ||
                                    url.startsWith("https://localhost")) {
                                    return false
                                }
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

            // Left edge drag indicator (thin strip, subtle)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(16.dp)
                    .background(Color.Transparent)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (drawerState.isClosed) {
                                    scope.launch { drawerState.open() }
                                }
                            }
                        ) { _, dragAmount ->
                            if (dragAmount > 30 && drawerState.isClosed) {
                                scope.launch { drawerState.open() }
                            }
                        }
                    }
            )

            // Left edge hint label (auto-hide after first use)
            var showHint by remember { mutableStateOf(true) }
            if (showHint && drawerState.isClosed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(y = (-60).dp)
                        .width(28.dp)
                        .height(120.dp)
                        .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        .clickable {
                            scope.launch { drawerState.open() }
                            showHint = false
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "←",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
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
