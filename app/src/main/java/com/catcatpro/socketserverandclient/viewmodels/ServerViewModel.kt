package com.catcatpro.socketserverandclient.viewmodels

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.catcatpro.socketserverandclient.services.ServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.catcatpro.server.enums.DeviceStatus
import com.catcatpro.socketserverandclient.model.Message
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerViewModelUIState(
    val serverIp: String = "",
    val serverPort: Int? = null,
    val clientCount: Int = 0,
    val errorMessage: String = "",

)
class ServerViewModel(application: Application): AndroidViewModel(application) {
    private val context = application

    // service相关
    private var service: ServerService? = null
    private var serviceIntent: Intent? = null
    private var serviceConnection: ServiceConnection? = null
    private var onServiceReady: (() -> Unit)? = null  // 就绪回调

    var isServiceReady by mutableStateOf(false)  // 服务是否就绪
        private set

    var isBound by mutableStateOf(false)
        private set
    var isClientConnected by mutableStateOf(
        DeviceStatus.UNCONNECTED
    )  // 客户端是否已经连接
        private set
    var isRunning  by mutableStateOf(false)  // 服务是否在运行状态
        private set
    //使用StateFlow管理状态
    private val _uiState = MutableStateFlow(ServerViewModelUIState())
    val uiState = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()


    companion object{
        private const val TAG = "HomeTabViewModel"
    }


//    fun toggleServeStatus(value: Boolean){
//        isRunning = value
//
//        if (isRunning)
//            startServer()
//        else
//            stopServer()
//    }

    //绑定Service
    fun bindService( onReady: (() -> Unit)? = null) {
        if (serviceConnection != null) {
            onReady?.invoke()  // 已绑定，直接回调
            return
        }

        this.onServiceReady = onReady
//
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val localBinder = binder as ServerService.LocalBinder
                service = localBinder.getService()

//                _uiState.update { it.copy(isBound = true, isServiceReady = true) }
                isBound = true
                isServiceReady = true
//                Log.d(TAG,"start socketio server "+ service?.serverState?.value?.isRunning)

                // 收集状态
                viewModelScope.launch {
                    service?.serverState?.collect { state ->
                        updateUiState(state)
                    }
                }

                // 收集消息
                viewModelScope.launch {
                    service?.messages?.collect { msg ->
                        _messages.value =  _messages.value.plus(msg)
                    }
                }

                // 执行就绪回调
                onServiceReady?.invoke()
                onServiceReady = null
                isBound= true
//                _uiState.update {
//                    it.copy(isBound = true)
//                }
                Log.d("ServerViewModel", "Service 已连接")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
//                _uiState.update { it.copy(isBound = false, isServiceReady = false, isRunning = false) }
                isBound = false
                isServiceReady = false
                isRunning = false
                Log.d("ServerViewModel", "Service 已断开")

//                _uiState.update {
//                    it.copy(isBound = false)
//                }
                isBound = false
            }
        }

        Intent(context, ServerService::class.java).also { intent ->
            context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        }
    }


    // 安全调用：确保 service 不为 null
    fun startServer() {
        if (service == null) {
            // 延迟执行：等绑定完成后再启动
            bindService {
                startServerInternal()
            }
            return
        }


        startServerInternal()
    }

    private fun startServerInternal() {
        serviceIntent = Intent(context, ServerService::class.java).apply {
            action = ServerService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(this)
            } else {
                context.startService(this)
            }
        }
    }


    //解绑Service
    fun unbindService() {
        serviceConnection?.let {
            try {
                context.unbindService(it)
            } catch (e: Exception) {
                Log.e("ViewModel", "解绑失败", e)
            }
            serviceConnection = null
            service = null
            isBound = false
        }
    }

    fun stopServer() {
        if (service == null) {
            _uiState.update { it.copy(errorMessage = "服务未初始化") }
            return
        }
        Intent(context, ServerService::class.java).apply {
            action = ServerService.Companion.ACTION_STOP
            context.startService(this)
        }

        unbindService()

    }


    private fun updateUiState(state: ServerService.ServerState) {
        //更新设备状态
        if (state.clientId.isNotEmpty()){
            isClientConnected = state.clientConnectStatus
        }
        isRunning = state.isRunning
        _uiState.update {
            it.copy(
                serverIp = state.ip,
                serverPort = state.port
            ) }

    }

    fun broadcast(msgText: String){
        service?.broadcast(msgText)
    }
    fun broadcastImage(imageUri: Uri){
        service?.broadcastImageMessage(imageUri)
    }
    override fun onCleared() {
        stopServer()
    }
}