package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.SyncManager
import com.shanji.app.data.net.ApiFactory
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.Red
import kotlinx.coroutines.launch

/* 登录/注册页：字段即全部（无说明段落）；错误就地显示且是人话 */
@Composable
fun LoginScreen(onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf("login") }   // login | register
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(ApiFactory.baseUrl(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        val u = username.trim()
        // 就地校验：提交前拦住，焦点留在问题字段
        when {
            u.isEmpty() -> { error = "请输入用户名"; return }
            u.length < 3 -> { error = "用户名至少 3 个字符"; return }
            password.length < 6 -> { error = "密码至少 6 位"; return }
        }
        error = null; busy = true
        scope.launch {
            val result = runCatching {
                ApiFactory.setBaseUrl(context, serverUrl)
                if (mode == "login") SyncManager.login(context, u, password)
                else SyncManager.register(context, u, password)
            }
            busy = false
            result.onSuccess { onLoggedIn() }
                .onFailure { error = SyncManager.friendlyError(it) }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("云同步", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        // 登录 / 注册 切换
        Row(Modifier.padding(bottom = 20.dp)) {
            listOf("login" to "登录", "register" to "注册").forEach { (k, label) ->
                val selected = mode == k
                Text(
                    label, fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { mode = k; error = null }
                        .padding(end = 28.dp)
                )
            }
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it.take(32); error = null },
            label = { Text("用户名") },
            singleLine = true,
            isError = error?.contains("用户名") == true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(64); error = null },
            label = { Text("密码") },
            singleLine = true,
            isError = error?.contains("密码") == true,
            visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password
            ),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; testMsg = null },
            label = { Text("服务器地址") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                testMsg = "…"
                scope.launch {
                    ApiFactory.setBaseUrl(context, serverUrl)
                    testMsg = SyncManager.testConnection(context)
                }
            }) { Text("测试连接", color = Primary) }
            testMsg?.let {
                Text(
                    it, fontSize = 13.sp,
                    color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.secondary else Red
                )
            }
        }

        // 错误就地显示（人话，见 SyncManager.friendlyError）
        error?.let {
            Text(
                it, fontSize = 13.sp, color = Red,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        Button(
            onClick = { submit() },
            enabled = !busy,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (busy) "请稍候…" else if (mode == "login") "登录" else "注册并登录", fontSize = 16.sp)
        }
    }
}
