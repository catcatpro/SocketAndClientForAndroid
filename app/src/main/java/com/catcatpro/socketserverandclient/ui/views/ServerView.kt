package com.catcatpro.socketserverandclient.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catcatpro.socketserverandclient.model.Message
import  com.catcatpro.socketserverandclient.ui.views.MessageItem
import com.catcatpro.socketserverandclient.viewmodels.ServerViewModel
import kotlinx.coroutines.launch

@Composable
fun ServerView(viewModel: ServerViewModel = viewModel() ) {
    val uiState by viewModel.uiState.collectAsState()
//    var isServerRunning by remember { mutableStateOf(false) }
//    var ipAddress by remember { mutableStateOf("192.168.1.100") }
    val messages by viewModel.messages.collectAsState()
    var inputText by remember { mutableStateOf("") }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 相册选择器
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let {
            viewModel.broadcastImage(uri)
        } }
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 服务器控制区域
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "服务器控制",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
//
                            //  实际启动服务器逻辑
                            viewModel.startServer()
                        },
                        enabled = !viewModel.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("启动服务")
                    }

                    Button(
                        onClick = {

                            // 实际停止服务器逻辑
                            viewModel.stopServer()

                        },
                        enabled = viewModel.isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("停止服务")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (viewModel.isRunning) Icons.Default.CheckCircle else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (viewModel.isRunning) Color.Green else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "状态: ${if (viewModel.isRunning) "运行中" else "已停止"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if(viewModel.isRunning){
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "IP地址: ${uiState.serverIp}:${uiState.serverPort}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 消息列表
        Text(
            text = "聊天记录",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageItem(message = message)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 输入区域
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") },
                enabled = viewModel.isRunning
            )

            IconButton(
                onClick = {
                    // 选择图片逻辑
//                    val newMessage = Message(
//                        type = MessageType.IMAGE,
//                        content = "图片占位符",
//                        sender = "服务器",
//                        timestamp = System.currentTimeMillis()
//                    )
//                    messages = messages + newMessage
//                    coroutineScope.launch {
//                        listState.animateScrollToItem(messages.size - 1)
//                    }
                    galleryLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                    coroutineScope.launch {
                            if(messages.isNotEmpty())  listState.animateScrollToItem(messages.size - 1)
                    }
                },
                enabled = viewModel.isRunning
            ) {
                Icon(Icons.Default.Image, contentDescription = "发送图片")
            }

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
//                        val newMessage = Message(
//                            type = MessageType.TEXT,
//                            content = inputText,
//                            sender = "服务器",
//                            timestamp = System.currentTimeMillis()
//                        )
                       viewModel.broadcast(inputText)
                        inputText = ""
                        coroutineScope.launch {
                            if(messages.isNotEmpty())    listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                },
                enabled = viewModel.isRunning && inputText.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
        }
    }
}
