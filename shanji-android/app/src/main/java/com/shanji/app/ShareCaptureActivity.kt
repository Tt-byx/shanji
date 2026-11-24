package com.shanji.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AiClient
import com.shanji.app.data.AiResult
import com.shanji.app.ui.RecordSheet
import com.shanji.app.ui.theme.Bg
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.ShanJiTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * 分享入口：在微信/支付宝等 App 截图后，点系统分享 → 闪记。
 * 有截图时：压缩存档 → （若配置了 AI）多模态识别预填金额/商户/时间 → 确认表单。
 * 未配置 AI 或识别失败：直接进手动表单。
 */
class ShareCaptureActivity : ComponentActivity() {

    private enum class AiState { OFF, LOADING, DONE, FAILED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.shanji.app.data.AppStore.init(this)

        val (textPreset, savedShot) = extractFromIntent(intent)

        setContent {
            ShanJiTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    ShareFlow(textPreset, savedShot) { finish() }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ShareFlow(textPreset: String, savedShot: File?, onClose: () -> Unit) {
        var aiState by remember { mutableStateOf(AiState.OFF) }
        var aiResult by remember { mutableStateOf<AiResult?>(null) }
        var manualOverride by remember { mutableStateOf(false) }

        // 带截图 → 走服务器 /api/ai/vision 识别（key 在服务端，端上零配置）
        val useAi = savedShot != null && !manualOverride
        LaunchedEffect(savedShot, manualOverride) {
            if (!useAi || aiState == AiState.DONE || aiState == AiState.LOADING) return@LaunchedEffect
            aiState = AiState.LOADING
            aiResult = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val shot = savedShot!!
                    val jpeg = compressForAi(shot)
                    AiClient.vision(this@ShareCaptureActivity, jpeg)
                }
            }.getOrNull()
            aiState = if (aiResult != null) AiState.DONE else AiState.FAILED
            if (aiState == AiState.FAILED) {
                Toast.makeText(this@ShareCaptureActivity, "AI 识别失败，请手动填写", Toast.LENGTH_SHORT).show()
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "闪记 · 分享记账",
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(20.dp)
            )

            savedShot?.let { file ->
                decodeScaled(file, 720)?.let { bmp ->
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                            .height(200.dp).background(
                                androidx.compose.ui.graphics.Color(0xFFF2F2F7),
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bmp.asImageBitmap(), "付款截图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        )
                    }
                }
            }

            when {
                aiState == AiState.LOADING -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(Modifier.height(16.dp))
                        Text("AI 正在识别截图中的金额、商户、时间…", fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { manualOverride = true }) { Text("跳过，手动填写") }
                    }
                }
                aiState == AiState.DONE && aiResult != null -> {
                    Text(
                        "AI 已识别并预填，请核对后保存",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    RecordSheet(
                        editing = null,
                        onDismiss = onClose,
                        source = "share",
                        presetMerchant = aiResult?.merchant ?: textPreset,
                        presetAmount = aiResult?.amount,
                        presetDateTime = aiResult?.time?.let {
                            runCatching {
                                LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            }.getOrNull()
                        }
                    )
                }
                else -> {
                    if (aiState == AiState.FAILED) {
                        Text(
                            "AI 识别失败，已切换为手动填写",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    RecordSheet(
                        editing = null,
                        onDismiss = onClose,
                        source = "share",
                        presetMerchant = textPreset
                    )
                }
            }
        }

        if (savedShot == null && textPreset.isEmpty()) {
            Toast.makeText(this, "未收到可识别的内容", Toast.LENGTH_SHORT).show()
        }
    }

    // Compose 里跨重组保持状态直接用 remember；此处无需额外封装

    /** 从分享 Intent 提取内容：图片存私有目录，文本作商户预填 */
    private fun extractFromIntent(intent: Intent?): Pair<String, File?> {
        if (intent == null) return Pair("", null)
        val type = intent.type ?: return Pair("", null)

        if (type.startsWith("image/")) {
            val uri: Uri = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()).firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } ?: return Pair("", null)
            return Pair("", saveImage(uri))
        }
        if (type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(30) ?: ""
            return Pair(line, null)
        }
        return Pair("", null)
    }

    private fun saveImage(uri: Uri): File? {
        return runCatching {
            val dir = File(getFilesDir(), "shots").apply { mkdirs() }
            val name = "shot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val out = File(dir, name)
            val bmp = decodeScaled(contentResolver, uri, 720) ?: return null
            out.outputStream().use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 60, fos)
            }
            out
        }.getOrNull()
    }

    /** AI 识别用的再压缩：最长边 480，质量 55，控制 base64 体积 */
    private fun compressForAi(file: File): ByteArray {
        val bmp = decodeScaled(file, 480) ?: return ByteArray(0)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 55, bos)
        return bos.toByteArray()
    }

    private fun decodeScaled(file: File, maxW: Int): Bitmap? = runCatching {
        android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let { b ->
            scaleDown(b, maxW)
        }
    }.getOrNull()

    private fun decodeScaled(resolver: android.content.ContentResolver, uri: Uri, maxW: Int): Bitmap? =
        runCatching {
            android.graphics.BitmapFactory.decodeStream(resolver.openInputStream(uri))?.let { b ->
                scaleDown(b, maxW)
            }
        }.getOrNull()

    private fun scaleDown(b: Bitmap, maxW: Int): Bitmap {
        if (b.width <= maxW) return b
        val ratio = maxW.toFloat() / b.width
        val scaled = Bitmap.createScaledBitmap(b, maxW, (b.height * ratio).toInt().coerceAtLeast(1), true)
        if (scaled != b) b.recycle()
        return scaled
    }
}
