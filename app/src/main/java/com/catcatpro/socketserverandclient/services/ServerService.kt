package com.catcatpro.socketserverandclient.services
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.catcatpro.common.utils.Utils
import com.catcatpro.server.enums.DeviceStatus
import com.catcatpro.server.NettySocketServer
import com.catcatpro.socketserverandclient.model.Message
import com.catcatpro.socketserverandclient.model.MsgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

//服务端进程
class ServerService : Service() {
    private lateinit var server: NettySocketServer
    private lateinit var jmdns: JmDNS
    private lateinit var mdnsServiceInfo: ServiceInfo

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _serverState = MutableStateFlow<ServerState>(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 100)


    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    lateinit var context: Context


    companion object {
        const val PORT = 8888
        const val TAG = "SocketServerV2"
        const val SERVER_NAME = "AndroidSocketServer"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val CHANNEL_ID = "server_channel"
        const val NOTIFICATION_ID = 1

    }
    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods.
        fun getService(): ServerService = this@ServerService
    }

    data class ServerState (
        var isRunning : Boolean = false,
        var ip: String = "",
        var port: Int = PORT,
        val statusMessage: String = "",
        val clientId: String = "",
        val clientConnectStatus: DeviceStatus = DeviceStatus.UNCONNECTED,
        val errorMessage: String = ""
    )


    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder


    override fun onCreate() {
        super.onCreate()
        context  = applicationContext
        createNotificationChannel()  // 服务创建时立即创建渠道
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START ->{
                serviceScope.launch {
                    // ① 同步前台化，先于任何耗时逻辑
                    startForeground(NOTIFICATION_ID, createNotification("启动中..."))

                    startServer()
                }
            }
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    suspend fun startServer(){
        if (_serverState.value.isRunning) return

        val ip = getLocalIpAddress()?: "0.0.0.0"
        val addr = withContext(Dispatchers.IO) {
            InetAddress.getByName(ip)
        }
        Log.d(TAG, addr.address.contentToString())

        jmdns = withContext(Dispatchers.IO) {
            JmDNS.create(addr)
        }
        registerMdnsService()
        Log.d(TAG, "mDNS 服务已注册: $ip:$PORT")

//        Log.d(TAG,"start socketio server "+_serverState.value.isRunning)
        server = NettySocketServer(ip, PORT, context)
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(Thread(server::shutdown))

//         server.onMessage = {it ->
//             onMessage(it)
//         }
        server.onMessage  = {s, c ->
            onMessage(s,c)
        }

        server.onMessageImage = {s, c ->
            onMessageImage(s, c)

        }

        server.onDeviceStatusChange = {t, c, m ->
            onDeviceStatusChange(t, c, m)
        }

        server.onError = {e ->
            onError(e)
        }


        serviceScope.launch {
            try {
                startForeground(NOTIFICATION_ID, createNotification("运行中: $ip:${PORT}"))
                _serverState.value = ServerState(true, ip, PORT)
//                delay(Long.MAX_VALUE)
                server.start()

            }catch (e: InterruptedException){
                Log.e(TAG,"socketio server error: "+e.message)
                e.printStackTrace()
            } finally {
//                shutdown()
            }

        }

    }

    private fun stopServer(){
        serviceScope.launch {
            stopForeground(STOP_FOREGROUND_REMOVE)
            server.shutdown()
            _serverState.update {
                it.copy(isRunning = false,ip = "", port = PORT)
            }
            unregisterMdnsService()
            stopSelf()
        }
    }

    // 接收文本
    private fun onMessage(sender: String, content: String) {
        serviceScope.launch {

//            _messages.emit(Message("client", content = msg?.getPayload()?.payload as String))
//            val payload = msg?.getPayload()
//            Log.d(TAG,"payload.payload_type${payload?.payload_type}")
//            if (payload?.payload_type == PAYLOAD_TYPE.FILE){
//                _messages.emit(Message("client", msgType = MsgType.IMAGE, bitmap = Utils.base64ToBitmap(payload.payload!!) as Bitmap))
//            }else
//                _messages.emit(Message("client",  content = payload?.payload as String))
            _messages.emit(Message(sender,  content = content))

        }
    }

    //  接收图片
    private fun onMessageImage(sender: String, imagePath: String) {
        serviceScope.launch {

//            _messages.emit(Message("client", content = msg?.getPayload()?.payload as String))
//            val payload = msg?.getPayload()
//            Log.d(TAG,"payload.payload_type${payload?.payload_type}")
//            if (payload?.payload_type == PAYLOAD_TYPE.FILE){
//                _messages.emit(Message("client", msgType = MsgType.IMAGE, bitmap = Utils.base64ToBitmap(payload.payload!!) as Bitmap))
//            }else
//                _messages.emit(Message("client",  content = payload?.payload as String))

            Log.d(TAG, "imagePath: ${imagePath}")
            val imageBitmap = BitmapFactory.decodeFile(imagePath)
            _messages.emit(Message(sender, msgType = MsgType.IMAGE, content = "", bitmap = imageBitmap))
        }
    }

    private fun  onDeviceStatusChange(status: DeviceStatus, clientId: String, message: String){
        _serverState.update {
            it.copy(statusMessage = message, clientId = clientId,  clientConnectStatus = status)
        }
    }


    private fun onError(errorMessage: String){
        _serverState.update {
            it.copy(errorMessage = errorMessage)
        }
    }

    private fun registerMdnsService() {
        try {

            mdnsServiceInfo = ServiceInfo.create(
                "_socket._tcp.local.",  // 服务类型
                 SERVER_NAME,    // 服务名称
               PORT,
                "Socket server on Android",
            )

            jmdns.registerService(mdnsServiceInfo)


        } catch (e: Exception) {
            Log.e(TAG, "mDNS 注册失败", e)
        }
    }

    private fun unregisterMdnsService(){
        jmdns.unregisterService(mdnsServiceInfo)
        jmdns.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Socket服务器",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持Socket服务器运行"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
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



    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Socket服务器")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    //广播文本
    fun broadcast(msg: String){
//            server.broadcastOperations.sendEvent("broadcast", msg)
        serviceScope.launch {
            _messages.emit(Message("server", content = msg))

            server.broadcast(msg)
        }

    }

    fun broadcastEvent(action: String,error_code: Int,  data: Any){
//            server.broadcastOperations.sendEvent("broadcast", msg)
        serviceScope.launch {
            server.broadcastEvent(action,error_code,  data)
        }

    }
    //广播文件
   private fun broadcastFile(type: String, fileUri: Uri) : Boolean{
        return try {
            server.broadcastFile(type, fileUri)
//            client.sendImageV2(bitmap)

            true
        } catch (e: Exception) {
            Log.e(TAG, "发送失败", e)
            false
        }

    }

    //广播
    fun broadcastImageMessage(imageUri: Uri){
        serviceScope.launch {
            _messages.emit(Message("server", content = "", msgType = MsgType.IMAGE, bitmap = Utils.uriToBitmap(context, imageUri!!)))
           broadcastFile("image", imageUri)
        }
    }


}