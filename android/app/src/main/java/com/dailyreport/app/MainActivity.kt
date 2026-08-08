package com.dailyreport.app

import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var isPageLoaded = false

    // 热更新：定时自动刷新网页内容（对应网页端每小时数据更新）
    private var hotUpdateJob: kotlinx.coroutines.Job? = null
    private var lastPageLoadedAt = 0L

    // 定位权限请求（每次启动检查，未授权则弹出系统权限弹窗）
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "未授予定位权限，天气将无法显示位置", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 每次启动检查定位权限，未授权则主动索要
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // 热更新任务：每小时自动刷新一次，拉取网页端最新部署的内容与功能
        hotUpdateJob = kotlinx.coroutines.MainScope().launch {
            while (true) {
                delay(60 * 60 * 1000L)
                val wv = webView ?: continue
                // 页面已加载完成且距上次加载超过 1 小时才刷新（避免打断正在阅读）
                if (isPageLoaded && System.currentTimeMillis() - lastPageLoadedAt > 60 * 60 * 1000L) {
                    wv.post { wv.reload() }
                }
            }
        }

        // 自动升级：每次启动连接云端检查最新版本
        checkForUpdate()

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
                    onPageLoaded = {
                        isPageLoaded = true
                        lastPageLoadedAt = System.currentTimeMillis()
                    },
                    onShareScreenshot = { shareScreenshot() },
                    onExportWord = { exportToWord() },
                    onCheckUpdate = { checkForUpdate(manual = true) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台回到前台：若距上次加载超过 30 分钟，自动刷新获取最新内容（热更新）
        val wv = webView
        if (wv != null && isPageLoaded && lastPageLoadedAt > 0 &&
            System.currentTimeMillis() - lastPageLoadedAt > 30 * 60 * 1000L
        ) {
            wv.post { wv.reload() }
        }
    }

    override fun onDestroy() {
        hotUpdateJob?.cancel()
        super.onDestroy()
    }

    /* ===== 自动升级：蓝奏云为更新主通道（国内网络可达），WebView 内核过反爬 ===== */
    // 蓝奏云公开分享链接（固定不变，文件名带版本号，更新文件后链接不变）
    private val LANZOU_SHARE_URL = "https://wwbjt.lanzoum.com/iABcG4196ylg"
    private var lanzouChecking = false
    private var lanzouWv: WebView? = null

    private fun checkForUpdate(manual: Boolean = false) {
        if (manual) showLanZouUpdateDialog() else silentLanZouCheck()
    }

    /** 手动检查：弹出可见的蓝奏云分享页（可见状态下反爬挑战可通过），用户点下载按钮即自动安装 */
    private fun showLanZouUpdateDialog() {
        if (lanzouChecking) return
        lanzouChecking = true
        try {
            val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            val wv = WebView(this)
            lanzouWv = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.setDownloadListener { url, _, _, _, _ ->
                runOnUiThread {
                    lanzouChecking = false
                    dialog.dismiss()
                    downloadAndInstall(url)
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 可见状态下标题应很快变为 "v1.3.1.apk - 蓝奏云"
                    view?.postDelayed({
                        view.evaluateJavascript("document.title") { v ->
                            val title = (v ?: "").trim('"')
                            val ver = parseLanzouVersion(title)
                            if (ver.isNotEmpty() && lanzouChecking) {
                                lanzouChecking = false
                                runOnUiThread {
                                    if (versionNewer(ver, BuildConfig.VERSION_NAME)) {
                                        Toast.makeText(this@MainActivity,
                                            "发现新版本 v$ver，正在自动下载…", Toast.LENGTH_LONG).show()
                                        triggerLanzouDownload(wv)
                                    } else {
                                        Toast.makeText(this@MainActivity,
                                            "暂无可用更新（当前已是最新版本）", Toast.LENGTH_LONG).show()
                                        dialog.dismiss()
                                    }
                                }
                            }
                        }
                    }, 2500)
                }
            }
            dialog.setContentView(wv,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dialog.setOnDismissListener { lanzouChecking = false }
            dialog.show()
            wv.loadUrl(LANZOU_SHARE_URL)
        } catch (_: Exception) {
            lanzouChecking = false
            Toast.makeText(this, "无法打开蓝奏云更新页", Toast.LENGTH_SHORT).show()
        }
    }

    /** 启动自动检查：隐藏 WebView 尽力尝试（反爬可能拦截，失败静默不影响使用） */
    private fun silentLanZouCheck() {
        if (lanzouChecking) return
        lanzouChecking = true
        try {
            val wv = WebView(this)
            lanzouWv = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            // 不能用 GONE（不渲染会导致蓝奏云 JS 反爬挑战无法完成），用 1x1 透明可见
            wv.visibility = android.view.View.VISIBLE
            wv.alpha = 0.01f
            wv.layoutParams = ViewGroup.LayoutParams(1, 1)
            (window.decorView as ViewGroup).addView(wv)
            // 捕获蓝奏云下载（挑战通过后触发）→ 保存并安装
            wv.setDownloadListener { url, _, _, _, _ ->
                runOnUiThread {
                    lanzouWv?.let { (window.decorView as ViewGroup).removeView(it) }
                    lanzouWv = null
                    downloadAndInstall(url)
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 蓝奏云文件名标题由 JS 动态设置（反爬挑战通过后），轮询等待（失败静默）
                    view?.let { pollLanzouReady(it, false, 8, false) }
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    lanzouChecking = false
                    cleanupLanzouWv()
                }
            }
            wv.loadUrl(LANZOU_SHARE_URL)
        } catch (_: Exception) {
            lanzouChecking = false
        }
    }

    private fun cleanupLanzouWv() {
        lanzouWv?.let {
            runOnUiThread {
                try { (window.decorView as ViewGroup).removeView(it) } catch (_: Exception) {}
            }
        }
        lanzouWv = null
    }

    /** 轮询蓝奏云页面：等待 iframe 下载按钮出现（挑战完成标志），随后读取文件名/版本 */
    private fun pollLanzouReady(wv: WebView, manual: Boolean, attempts: Int, reloaded: Boolean) {
        wv.postDelayed({
            val js = """(function(){
                try{
                    var fs=document.querySelectorAll('iframe');
                    for(var i=0;i<fs.length;i++){
                        var d=fs[i].contentDocument;
                        if(!d) continue;
                        var a=d.querySelector('a[href*="dmpdmp"]');
                        if(a) return a.href;
                    }
                }catch(e){}
                return '';
            })()"""
            wv.evaluateJavascript(js) { href ->
                val h = (href ?: "").trim('"')
                if (h.isNotEmpty()) {
                    // 挑战完成，下载按钮就绪 → 读标题拿文件名/版本
                    wv.evaluateJavascript("document.title") { v ->
                        val title = (v ?: "").trim('"')
                        val ver = parseLanzouVersion(title)
                        android.util.Log.d("LanZouCheck", "ready! title=$title ver=$ver")
                        if (ver.isNotEmpty()) {
                            lanzouChecking = false
                            handleLanzouVersion(ver, manual, wv)
                        } else {
                            // 标题异常时从 iframe 按钮文本找文件名
                            wv.evaluateJavascript("""(function(){
                                var fs=document.querySelectorAll('iframe');
                                for(var i=0;i<fs.length;i++){
                                    var d=fs[i].contentDocument;
                                    if(!d) continue;
                                    var a=d.querySelector('a[href*="dmpdmp"]');
                                    if(a){ var t=(a.innerText||a.title||'').trim(); if(t) return t; }
                                }
                                return '';
                            })()""") { v2 ->
                                val fn = (v2 ?: "").trim('"')
                                val ver2 = parseLanzouVersion(fn)
                                if (ver2.isNotEmpty()) {
                                    lanzouChecking = false
                                    handleLanzouVersion(ver2, manual, wv)
                                } else {
                                    lanzouChecking = false
                                    if (manual) runOnUiThread { showNoUpdateDialog(LANZOU_SHARE_URL) }
                                    cleanupLanzouWv()
                                }
                            }
                        }
                    }
                } else if (attempts > 1) {
                    if (attempts <= 5 && !reloaded) {
                        // 挑战疑似需刷新完成：主动 reload 一次
                        android.util.Log.d("LanZouCheck", "reload to finish challenge (attempts=$attempts)")
                        wv.reload()
                        pollLanzouReady(wv, manual, attempts - 1, true)
                    } else {
                        pollLanzouReady(wv, manual, attempts - 1, reloaded)
                    }
                } else {
                    lanzouChecking = false
                    if (manual) {
                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("检查更新")
                                .setMessage("网络异常，暂时无法检查更新，请稍后再试")
                                .setPositiveButton("确定", null)
                                .show()
                        }
                    }
                    cleanupLanzouWv()
                }
            }
        }, 1500)
    }

    /** 从蓝奏云标题解析版本号，如 "v1.3.1.apk - 蓝奏云" → "1.3.1" */
    private fun parseLanzouVersion(title: String): String {
        val m = Regex("v?(\\d+\\.\\d+(?:\\.\\d+)?)").find(title)
        return m?.groupValues?.get(1) ?: ""
    }

    /** 版本号比较：remote > local 返回 true */
    private fun versionNewer(remote: String, local: String): Boolean {
        if (remote.isEmpty()) return false
        val a = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val b = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun handleLanzouVersion(remoteVer: String, manual: Boolean, wv: WebView) {
        if (versionNewer(remoteVer, BuildConfig.VERSION_NAME)) {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("发现新版本 v$remoteVer")
                    .setMessage("蓝奏云有新版本可用，是否立即下载更新？\n（下载完成后按系统提示确认安装）")
                    .setPositiveButton("立即更新") { _, _ -> triggerLanzouDownload(wv) }
                    .setNegativeButton("稍后再说") { _, _ -> cleanupLanzouWv() }
                    .show()
            }
        } else {
            if (manual) {
                runOnUiThread { showNoUpdateDialog(LANZOU_SHARE_URL) }
            }
            cleanupLanzouWv()
        }
    }

    /** 在蓝奏云页面内自动点击下载按钮（iframe 中 dmpdmp 链接），触发 DownloadListener */
    private fun triggerLanzouDownload(wv: WebView) {
        wv.evaluateJavascript(
            """(function(){
                try{
                    var fs=document.querySelectorAll('iframe');
                    for(var i=0;i<fs.length;i++){
                        var d=fs[i].contentDocument;
                        if(!d) continue;
                        var a=d.querySelector('a[href*="dmpdmp"]');
                        if(a){ location.href=a.href; return 'ok'; }
                    }
                    return 'nobtn';
                }catch(e){ return 'err'; }
            })()"""
        ) { result ->
            if (result != "\"ok\"") {
                // 自动下载受限时兜底：系统浏览器打开蓝奏云让用户手动下载
                runOnUiThread {
                    Toast.makeText(this, "自动下载受限，已打开蓝奏云页面，请手动下载", Toast.LENGTH_LONG).show()
                    cleanupLanzouWv()
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LANZOU_SHARE_URL))
                    startActivity(intent)
                }
            }
        }
    }

    private fun showNoUpdateDialog(downloadUrl: String) {
        val msg = if (downloadUrl.isNotBlank()) {
            "暂无可用更新，您已是最新版本。\n\n如需手动下载最新版，可访问蓝奏云更新链接：\n$downloadUrl"
        } else {
            "暂无可用更新，您已是最新版本。"
        }
        val dialog = AlertDialog.Builder(this@MainActivity)
            .setTitle("检查更新")
            .setMessage(msg)
            .setNegativeButton("关闭", null)
        if (downloadUrl.isNotBlank()) {
            dialog.setPositiveButton("复制链接") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("蓝奏云更新链接", downloadUrl))
                Toast.makeText(this, "更新链接已复制", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun downloadAndInstall(apkUrl: String) {
        val progress = ProgressDialog(this).apply {
            setTitle("下载更新")
            setMessage("正在下载最新版本…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMax(100)
            setCancelable(false)
            show()
        }
        kotlinx.coroutines.MainScope().launch {
            val apkFile = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "apk").apply { mkdirs() }
                val out = File(dir, "latest.apk")
                try {
                    val conn = URL(apkUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                    // 蓝奏云下载需要携带会话 Cookie（否则 403）
                    val cookie = android.webkit.CookieManager.getInstance().getCookie(apkUrl)
                    if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.connect()
                    val total = conn.contentLength.toLong()
                    var downloaded = 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                                downloaded += n
                                val p = if (total > 0) (downloaded * 100 / total).toInt() else 0
                                runOnUiThread { progress.progress = p }
                            }
                        }
                    }
                    out
                } catch (e: Exception) {
                    runOnUiThread {
                        progress.dismiss()
                        Toast.makeText(this@MainActivity, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    null
                }
            }
            progress.dismiss()
            apkFile?.let { installApk(it) }
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开安装器：${e.message}", Toast.LENGTH_SHORT).show()
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
        bodyBuilder.append(wp("left", wr("完整实时版请访问: https://fjsy56.github.io/daily-report", sz=20, color="666666")))
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
    onExportWord: () -> Unit = {},
    onCheckUpdate: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 更新日志：版本变化（含首次安装）时自动弹出
    val currentVersion = BuildConfig.VERSION_NAME
    val prefs = remember { context.getSharedPreferences("daily_report_prefs", Context.MODE_PRIVATE) }
    var showChangelog by remember { mutableStateOf(prefs.getString("last_seen_version", null) != currentVersion) }
    val dismissChangelog: () -> Unit = {
        showChangelog = false
        prefs.edit().putString("last_seen_version", currentVersion).apply()
    }

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 动态手势：抽屉关闭时禁用全屏手势（不干扰网页滚动）；
        // 抽屉打开时启用全屏手势（可右向左滑关闭，此时网页被遮罩盖住无冲突）
        gesturesEnabled = drawerState.isOpen,
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
                        text = "fjsy56.github.io/daily-report",
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // Changelog button
                    SidebarButton(
                        icon = Icons.Default.History,
                        label = "更新日志",
                        description = "查看各版本更新内容",
                        onClick = {
                            scope.launch { drawerState.close() }
                            showChangelog = true
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Check update button（手动检查更新，暂无更新时附蓝奏云链接+复制）
                    SidebarButton(
                        icon = Icons.Default.SystemUpdate,
                        label = "检查更新",
                        description = "手动检查是否有新版本",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onCheckUpdate()
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "从左侧边缘右划打开",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
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
                            // 允许网页使用定位（天气功能）
                            setGeolocationEnabled(true)
                        }

                        // Enable full-document drawing for long screenshot capture
                        // (hidden SystemApi, must use reflection; silent fallback if blocked)
                        try {
                            WebView::class.java.getMethod("enableSlowWholeDocumentDraw").invoke(this)
                        } catch (_: Exception) { }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                onPageLoaded()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith("https://fjsy56.github.io/daily-report") ||
                                    url.startsWith("http://fjsy56.github.io/daily-report") ||
                                    url.startsWith("http://localhost") ||
                                    url.startsWith("https://localhost")) {
                                    return false
                                }
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                ctx.startActivity(intent)
                                return true
                            }
                        }

                        // 处理网页定位授权：App 已获权限则直接放行给网页
                        webChromeClient = object : WebChromeClient() {
                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: GeolocationPermissions.Callback?
                            ) {
                                callback?.invoke(origin, true, true)
                            }
                        }

                        loadUrl("https://fjsy56.github.io/daily-report/")
                        onWebViewReady(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 左侧"←"提示按钮：常显，点击打开侧栏（关闭侧栏用全屏左滑手势）
            if (drawerState.isClosed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(y = (-60).dp)
                        .width(32.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                        .clickable { openDrawer() },
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

            // 更新日志弹窗（版本变化时自动弹出，也可从侧边栏随时打开）
            if (showChangelog) {
                ChangelogDialog(
                    currentVersion = currentVersion,
                    onDismiss = dismissChangelog
                )
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

/* ===== 更新日志 ===== */
private data class ChangelogEntry(
    val version: String,
    val title: String,
    val notes: List<String>
)

private val CHANGELOG = listOf(
    ChangelogEntry(
        version = "1.3.1",
        title = "蓝奏云更新通道",
        notes = listOf(
            "更新检查切换至蓝奏云为主通道（国内网络稳定可达）：每次打开应用自动检查蓝奏云最新版本，发现新版自动下载安装",
            "侧边栏「检查更新」：手动检查蓝奏云，暂无更新时附蓝奏云链接与一键复制"
        )
    ),
    ChangelogEntry(
        version = "1.3",
        title = "平台迁移与体验升级",
        notes = listOf(
            "网站与 App 迁移至 GitHub Pages 新地址（fjsy56.github.io/daily-report），部署免费稳定、更新不受限",
            "自动升级与热更新已切换到新地址，更新通道全面恢复",
            "修复网页「···」按钮旋转状态在关闭设置面板后不归位的问题",
            "今日速览改为关键词总结，随每日新闻自动更新（不再照搬原文）",
            "热词统计随每日新闻自动更新（词云与新闻数据同步变化）",
            "侧边栏新增「检查更新」：一键检测最新版本，无更新时提示并附蓝奏云下载链接与一键复制",
            "应用更新新增蓝奏云下载入口（适配国内网络，GitHub 访问不畅时也能稳定更新）"
        )
    ),
    ChangelogEntry(
        version = "1.2",
        title = "更新日志功能上线",
        notes = listOf(
            "侧边栏新增「更新日志」入口，可随时查看每个版本的更新内容",
            "每次版本更新完成后自动弹出更新日志，新功能一目了然",
            "支持回看历史版本（v1.0 / v1.1）的更新记录",
            "支持热更新：自动获取网页端最新内容与功能，无需重新安装"
        )
    ),
    ChangelogEntry(
        version = "1.1",
        title = "体验优化与功能修复",
        notes = listOf(
            "侧边栏改为从屏幕左侧边缘滑出，不再与网页按钮冲突",
            "分享截图升级为完整网页长截图，内容不再只截首屏",
            "Word 导出包含新闻真实内容：标题、摘要、来源，按分类组织",
            "修复网页滚动卡顿与误触：关闭全屏手势，仅保留左边缘触发",
            "优化交互：点击左侧「←」打开侧栏，抽屉打开时全屏左滑关闭",
            "新增定位权限申请，修复天气城市定位读取失败的问题"
        )
    ),
    ChangelogEntry(
        version = "1.0",
        title = "每日一报 App 首发",
        notes = listOf(
            "内置浏览每日一报网页版（fjsy56.github.io/daily-report）",
            "侧边栏提供「分享截图」「导出Word」功能",
            "支持暗色模式自动适配与字号调节"
        )
    )
)

@Composable
fun ChangelogDialog(
    currentVersion: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // 标题行 + 右上角关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "更新日志",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "每日一报 v$currentVersion",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                // 版本列表（当前版本置顶，可滚动）
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    CHANGELOG.forEach { entry ->
                        val isCurrent = entry.version == currentVersion

                        // 版本标题行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "v${entry.version}",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = entry.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                            if (isCurrent) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "当前",
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 更新条目
                        entry.notes.forEach { note ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            ) {
                                Text(
                                    text = "•",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = note,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}
