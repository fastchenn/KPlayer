package dev.davplayer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.davplayer.app.data.ServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditor(initial: ServerConnection?, onDismiss: () -> Unit, onSave: suspend (ServerConnection) -> Unit) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var url by rememberSaveable(initial?.id) { mutableStateOf(initial?.rootUrl ?: "") }
    var username by rememberSaveable(initial?.id) { mutableStateOf(initial?.username ?: "") }
    // Do not put passwords into saved-instance-state bundles.
    var password by remember(initial?.id) { mutableStateOf(initial?.password ?: "") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = CanvasColor, topBar = {
        TopAppBar(title = { Text(if (initial == null) "添加连接" else "编辑连接") },
            navigationIcon = { IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor))
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Tag("WEBDAV")
            Text("连接自己的收藏", style = MaterialTheme.typography.headlineSmall)
            Text("填写服务器提供的 WebDAV 地址和账号。", color = MutedColor, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text("连接名称") }, placeholder = { Text("例如：家里的 NAS") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = url, onValueChange = { url = it; error = null }, label = { Text("服务器地址") }, placeholder = { Text("https://nas.example.com/dav/") }, supportingText = { Text("包含 http:// 或 https://，以及 WebDAV 目录路径") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, supportingText = { Text("匿名访问可留空") }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !saving,
                trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密码" else "显示密码") } }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = {
                scope.launch {
                    saving = true
                    try {
                        val server = if (initial == null) ServerConnection.create(name, url, username, password)
                        else ServerConnection.create(name, url, username, password, initial.id)
                        onSave(server)
                        onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: IllegalArgumentException) { error = failure.message ?: "请检查连接信息" }
                    catch (_: Exception) { error = "保存失败，请稍后重试" }
                    finally { saving = false }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (saving) "正在保存" else "保存连接")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(15.dp), tint = MutedColor)
                Text("配置会加密保存在本机，保存后点击连接即可浏览。", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
