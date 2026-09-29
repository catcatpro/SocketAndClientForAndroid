package com.catcatpro.socketserverandclient.ui.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catcatpro.common.utils.Utils
import com.catcatpro.socketserverandclient.viewmodels.ClientViewModel
import kotlinx.coroutines.launch


@Composable
fun ClientView(viewModel: ClientViewModel = viewModel()) {
    val context = LocalContext.current

    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    var isScanning by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var showServerList by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    fun onImageSelected(uri: Uri){
//        viewModel.setSelectedImageBitmap(Utils.uriToBitmap(context,uri))
//        viewModel.setSelectedImageUri(uri)
//        viewModel.doTextRecognizeIng()
    }
    // 相册选择器
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let {
            viewModel.sendImgMsg(uri)


        } }
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 连接控制区域
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "连接控制",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 扫描服务器按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            isScanning = true
                            showServerList = true
                            // 实际扫描逻辑
                            viewModel.scanServer()
                            isScanning = false
                        },
                        enabled = !uiState.isConnected,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isScanning) "扫描中..." else "扫描服务")
                    }
                }


                Spacer(modifier = Modifier.height(8.dp))

                // 服务器地址输入
                OutlinedTextField(
                    value = "${uiState.serverIp}:${uiState.serverPort}",
                    onValueChange = {
                                    viewModel.updateServerIp(it)
                                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器地址") },
                    placeholder = { Text("192.168.1.100") },
                    enabled = !uiState.isConnected,
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 连接/断开按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.doConnectSocketServer()

                        },
                        enabled = !uiState.isConnected && uiState.serverIp.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("连接")
                    }

                    Button(
                        onClick = {
                            viewModel.doDisConnectSocketServer()
                        },
                        enabled = uiState.isConnected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("断开")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 连接状态
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (uiState.isConnected) Icons.Default.CheckCircle else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (uiState.isConnected) Color.Green else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "状态: ${if (uiState.isConnected) "已连接" else "未连接"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                enabled =uiState.isConnected
            )

            IconButton(
                onClick = {
                    // TODO: 选择图片逻辑

//                    相册选择
                    galleryLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                    coroutineScope.launch {
                        if(messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                    }
                },
                enabled =uiState.isConnected
            ) {
                Icon(Icons.Default.Image, contentDescription = "发送图片")
            }

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendTextMsg(inputText)
                        inputText = ""
                        coroutineScope.launch {
                            if(messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                },
                enabled = uiState.isConnected && inputText.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
        }
    }
}
