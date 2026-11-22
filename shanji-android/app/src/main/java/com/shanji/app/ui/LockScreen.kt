package com.shanji.app.ui

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.shanji.app.data.LockManager
import com.shanji.app.ui.theme.Primary

/* 全屏锁页：优先弹生物识别，失败/取消/不可用时落到 PIN 键盘 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }

    fun tryUnlock(candidate: String) {
        if (LockManager.verify(context, candidate)) {
            LockManager.unlocked = true
            onUnlocked()
        } else {
            err = true
            pin = ""
        }
    }

    LaunchedEffect(Unit) {
        if (activity == null || !LockManager.hasBiometric(context)) return@LaunchedEffect
        val strong = androidx.biometric.BiometricManager.from(context)
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                LockManager.unlocked = true
                onUnlocked()
            }
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), callback)
        val info = if (strong) {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("闪记解锁")
                .setSubtitle("使用指纹或人脸验证")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("使用 PIN 码")
                .build()
        } else {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("闪记解锁")
                .setSubtitle("使用锁屏验证解锁")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
        }
        runCatching { prompt.authenticate(info) }
    }

    // PIN 输入满位自动校验
    LaunchedEffect(pin) {
        if (pin.length == LockManager.pinLength(context)) tryUnlock(pin)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(90.dp))
        Text("闪记", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Primary)
        Spacer(Modifier.height(8.dp))
        Text(
            if (err) "PIN 码不正确" else "输入 PIN 码解锁",
            fontSize = 14.sp,
            color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        // PIN 圆点
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(LockManager.pinLength(context)) { i ->
                Box(
                    Modifier.size(14.dp).background(
                        if (i < pin.length) Primary else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        // 数字键盘
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "del")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            keys.chunked(3).forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    rowKeys.forEach { k ->
                        Box(
                            Modifier.size(72.dp).background(
                                MaterialTheme.colorScheme.surface, RoundedCornerShape(36.dp)
                            ).clickable {
                                err = false
                                when (k) {
                                    "clear" -> pin = ""
                                    "del" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                    else -> if (pin.length < LockManager.pinLength(context)) pin += k
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when (k) {
                                    "clear" -> "清空"
                                    "del" -> "删除"
                                    else -> k
                                },
                                fontSize = if (k.length > 1) 16.sp else 24.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
