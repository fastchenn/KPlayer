package dev.davplayer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davplayer.app.data.LibraryText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LibraryTextEditor(initial: LibraryText, onDismiss: () -> Unit, onSave: suspend (LibraryText) -> Unit) {
    var firstLine by remember { mutableStateOf(initial.firstLine) }
    var secondLine by remember { mutableStateOf(initial.secondLine) }
    var description by remember { mutableStateOf(initial.description) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val draft = LibraryText(firstLine, secondLine, description)
    val valid = runCatching { draft.validated() }.isSuccess
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("修改首页文字") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("标题保留两行，只修改文字内容。字号、颜色与排版保持不变。", color = MutedColor, fontSize = 12.sp)
                OutlinedTextField(firstLine, onValueChange = { firstLine = it; error = null }, label = { Text("标题第一行") },
                    supportingText = { Text("${firstLine.length}/${LibraryText.TITLE_LIMIT}") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secondLine, onValueChange = { secondLine = it; error = null }, label = { Text("标题第二行") },
                    supportingText = { Text("${secondLine.length}/${LibraryText.TITLE_LIMIT}") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, onValueChange = { description = it; error = null }, label = { Text("说明文字") },
                    supportingText = { Text("${description.length}/${LibraryText.DESCRIPTION_LIMIT}") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                if (!valid) Text(runCatching { draft.validated() }.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                TextButton(onClick = {
                    val defaults = LibraryText()
                    firstLine = defaults.firstLine; secondLine = defaults.secondLine; description = defaults.description; error = null
                }, enabled = !saving) { Text("恢复默认文字") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                saving = true
                scope.launch {
                    try { onSave(draft.validated()); onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "保存失败，请稍后重试" }
                    finally { saving = false }
                }
            }, enabled = valid && !saving) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
