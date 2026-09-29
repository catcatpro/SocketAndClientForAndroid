package com.catcatpro.socketserverandclient.viewmodels

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.catcatpro.socketserverandclient.services.ClientService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.jmdns.JmDNS
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.catcatpro.socketserverandclient.model.Message
import com.catcatpro.socketserverandclient.services.ClientState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

data class ClientViewModelUIState(
    val serverName: String = "",
    val serverIp: String = "",
    val serverPort: Int? = null,
    val errorMessage: String = "",
    val isConnected: Boolean = false
)
class ClientViewModel(application: Application): AndroidViewModel(application){
    private val context = application

    // service相关
    private var service: ClientService? = null
    private var serviceIntent: Intent? = null
    private var serviceConnection: ServiceConnection? = null
    private var onServiceReady: (() -> Unit)? = null  // 就绪回调

    var isServiceReady by mutableStateOf(false)  // 服务是否就绪
        private set

    var isBound by mutableStateOf(false)
        private set

    var isRunning  by mutableStateOf(false)  // 服务是否在运行状态
        private set
    //使用StateFlow管理状态
    private val _uiState = MutableStateFlow(ClientViewModelUIState())
    val uiState = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()

    private var jmDns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoverJob: Job? = null

    private var jmDNS: JmDNS? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object{
        private const val TAG = "ClientViewModel"
        private const val SERVICE_TYPE = "_socket._tcp.local."
        private  const val SERVER_NAME = "AndroidSocketServer"

    }

    //jmdns事件监听
    private val serviceListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // 首次回调只有名字,需主动解析
            jmDNS?.requestServiceInfo(event.type, event.name)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            Log.d(TAG, "服务下线: ${event.name}")
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            Log.d(
                TAG,
                "解析成功: ${info.name} -> ${info.hostAddresses.firstOrNull()}:${info.port}"
            )

            if (SERVER_NAME == info.name){
                updateServerIp(info.hostAddresses.firstOrNull())
                updateServerPort(info.port)
            }

        }
    }




    /**
     * 开始扫描局域网内的服务
     */
    fun scanServer() {
        ioScope.launch {
            runCatching {
                acquireMulticastLock()
                val ip = getLocalIpAddress()?: "0.0.0.0"
                val addr = withContext(Dispatchers.IO) {
                    InetAddress.getByName(ip)
                }
                // 1. 创建 JmDNS 实例
                jmDNS = JmDNS.create(addr, "android-jmdns")


                // 3. 发现服务
                jmDNS?.addServiceListener(SERVICE_TYPE , serviceListener)
            }.onFailure { Log.e(TAG, "start failed", it) }
        }
    }

    fun stopJmDns() {
        ioScope.launch {
            runCatching {
                jmDNS?.let {
                    it.removeServiceListener("_http._tcp.local.", serviceListener)
                    it.unregisterAllServices()
                    it.close()
                }
                jmDNS = null
            }.onFailure { Log.e("JmDNS", "stop failed", it) }
            releaseMulticastLock()
        }
    }

    //获取本机地址
    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }


    /** 防止息屏时 WiFi 休眠导致收不到组播包 */
    private fun acquireMulticastLock() {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("jmdns").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
        multicastLock = null
    }


    fun updateServerIp(value: String?){
        _uiState.update {
            it.copy(serverIp = value as String)
        }
    }

    fun updateServerPort(value: Int){
        _uiState.update {
            it.copy(serverPort = value  )
        }
    }

    fun updateConnectStatus(value: Boolean){
        _uiState.update {
            it.copy(isConnected =  value)
        }
    }

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
                val localBinder = binder as ClientService.LocalBinder
                service = localBinder.getService()

//                _uiState.update { it.copy(isBound = true, isServiceReady = true) }
                isBound = true
                isServiceReady = true
//                Log.d(TAG,"start socketio server "+ service?.serverState?.value?.isRunning)

                // 收集状态
                viewModelScope.launch {
                    service?.clientState?.collect { state ->
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
                Log.d(TAG, "Service 已连接")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
//                _uiState.update { it.copy(isBound = false, isServiceReady = false, isRunning = false) }
                isBound = false
                isServiceReady = false
                isRunning = false
                Log.d(TAG, "Service 已断开")

//                _uiState.update {
//                    it.copy(isBound = false)
//                }
                isBound = false
            }
        }

        Intent(context, ClientService::class.java).also { intent ->
            context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        }
    }


    private fun startClientInternal() {
        serviceIntent = Intent(context, ClientService::class.java).apply {
            action = ClientService.ACTION_START
            putExtra("ip", _uiState.value.serverIp)
            putExtra("port", _uiState.value.serverPort)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(this)
            } else {
                context.startService(this)
            }
        }
    }

    private fun updateUiState(state: ClientState) {
        //更新设备状态
        _uiState.update {
            it.copy(isConnected = state.isConnected)
        }
        isRunning = state.isRunning
        //当服务器断开，关闭socket
        if (!state.isConnected &&  isRunning) {
            doDisConnectSocketServer()
        }
    }


    //连接socket服务器
    fun doConnectSocketServer(){
        if (service == null) {
            // 延迟执行：等绑定完成后再启动
            bindService {
              startClientInternal()
            }
            return
        }
        startClientInternal()
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
    fun doDisConnectSocketServer(){
        if (service == null) {
            _uiState.update { it.copy(errorMessage = "服务未初始化") }
            return
        }
        Intent(context, ClientService::class.java).apply {
            action = ClientService.ACTION_STOP
            context.startService(this)
        }

        unbindService()
    }

    fun sendTextMsg(data: String){
        service?.sendText(data)
    }

    fun sendImgMsg(imgFileUri: Uri?){
        service?.sendImage( imgFileUri!!)
    }
    override fun onCleared() {
        super.onCleared()
        doDisConnectSocketServer()
        stopJmDns()
    }
}

