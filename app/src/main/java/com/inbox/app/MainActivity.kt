package com.inbox.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * 主 Activity — WebView + 拍照直传 + 后台服务
 */
@SuppressLint("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var cameraPhotoUri: Uri? = null

    // 拍照回调
    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraPhotoUri != null) {
            uploadPhoto(cameraPhotoUri!!)
        }
    }

    // 权限回调
    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) dispatchTakePicture() else
            Toast.makeText(this, "需要相机权限才能拍照直传", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动前台服务（常驻后台）
        startForegroundService()

        // 创建 WebView
        webView = WebView(this)
        setupWebView()

        // 拍照按钮
        val cameraBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setBackgroundColor(0xff58a6ff.toInt())
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(140, 140).apply {
                setMargins(0, 0, 20, 20)
            }
            setOnClickListener { checkCameraAndShoot() }
        }

        // 记录按钮
        val historyBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setBackgroundColor(0xff58a6ff.toInt())
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(140, 140).apply {
                setMargins(0, 0, 20, 20)
            }
            setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
        }

        // 底部按钮栏
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(cameraBtn)
            addView(historyBtn)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBar)
        }

        setContentView(root)
    }

    private fun startForegroundService() {
        val intent = Intent(this, InboxService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = settings.userAgentString + " InboxApp/2.0"

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
        }
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl(getString(R.string.inbox_url))
    }

    // ── 拍照直传 ──

    private fun checkCameraAndShoot() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> dispatchTakePicture()
            else -> requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun dispatchTakePicture() {
        val photoFile = createPhotoFile()
        val uri = FileProvider.getUriForFile(this,
            "${packageName}.fileprovider", photoFile)
        cameraPhotoUri = uri
        takePhoto.launch(uri)
    }

    private fun createPhotoFile(): File {
        val dir = File(cacheDir, "camera")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        return File(dir, "IMG_$ts.jpg")
    }

    private fun uploadPhoto(uri: Uri) {
        Thread {
            try {
                val stream = contentResolver.openInputStream(uri) ?: return@Thread
                val bytes = stream.readBytes()
                stream.close()

                val fileName = "photo_${System.currentTimeMillis()}.jpg"
                val boundary = "----InboxUpload"
                val data = (
                    "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n"
                ).toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()

                val url = URL("https://inbox.oolool.com/api/inbox/share")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.setRequestProperty("Connection", "close")
                val dos = DataOutputStream(conn.outputStream)
                dos.write(data)
                dos.flush()
                dos.close()

                val responseCode = conn.responseCode
                // 读取响应体以释放连接
                conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val ok = responseCode == 200

                // 写记录
                InboxDatabase(this).insert(HistoryEntry(
                    fileName = fileName,
                    fileType = "image/jpeg",
                    fileSize = bytes.size.toLong(),
                    success = ok
                ))

                runOnUiThread {
                    Toast.makeText(this,
                        if (ok) "✅ 照片已存入收件箱" else "❌ 上传失败",
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "❌ 拍照上传失败: ${e.message}",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
