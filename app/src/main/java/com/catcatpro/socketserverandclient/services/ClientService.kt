package com.catcatpro.socketserverandclient.services

import android.R
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.catcatpro.client.NettySocketClient
import com.catcatpro.common.utils.Utils
import com.catcatpro.socketserverandclient.model.Message
import com.catcatpro.socketserverandclient.model.MsgType
import com.catcatpro.socketserverandclient.services.ServerService.Companion.CHANNEL_ID
import com.catcatpro.socketserverandclient.services.ServerService.Companion.NOTIFICATION_ID
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
import org.slf4j.helpers.Util

data class ClientState (
    var isRunning : Boolean = false,
    var ip: String = "",
    var port: Int? = null,
    val statusMessage: String = "",
    val clientId: String = "",
    val isConnected: Boolean = false,
    val errorMessage: String = ""
)
//客户端进程
class ClientService: Service() {
    private lateinit var client: NettySocketClient

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _clientState = MutableStateFlow<ClientState>(ClientState())
    val clientState: StateFlow<ClientState> = _clientState.asStateFlow()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 100)

    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    lateinit var context: Context


    companion object{
        val TAG = "ClientService"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
    }
    override fun onCreate() {
        super.onCreate()
        context  = applicationContext
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START ->{
                _clientState.update {
                    it.copy(ip = intent.getStringExtra("ip") as String, port = intent.getIntExtra("port", 8888))
                }
                serviceScope.launch {
                    // ① 同步前台化，先于任何耗时逻辑
                    startForeground(NOTIFICATION_ID, createNotification("连接中..."))
                    startClient()
                }
            }
            ACTION_STOP -> stopClient()
        }
        return START_STICKY
    }
    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods.
        fun getService(): ClientService = this@ClientService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder


    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Socket客户端")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
  fun startClient(){
        if (_clientState.value.isRunning) return
        client = NettySocketClient(_clientState.value.ip, _clientState.value.port as Int, context)
//        client.onNotification = { s, c ->
//        }
        client.onConnected = {
            _clientState.update {
                it.copy(isConnected = true)
            }
        }

      client.onDisconnected = {
          _clientState.update {
              it.copy(isConnected = false)
          }
      }

      client.onText = {s, c ->
          serviceScope.launch {
              _messages.emit(Message(s, content = c, sender = s))
          }

      }

      client.onMessageImage = {s, c ->
          onMessageImage(s, c)
      }

        serviceScope.launch(Dispatchers.IO) {
            try {
               client.start()
                Log.d(TAG, "started!")
                client.awaitClose()                   // 挂起，直到客户端关闭
                Log.d(TAG, "client closed")
            }catch (e: InterruptedException){
                Log.e(TAG,"socketio server error: "+e.message)
                e.printStackTrace()
            } finally {
//                shutdown()
            }

        }
    }

    fun sendText(text: String){
        serviceScope.launch {
            _messages.emit(Message("client", content = text, sender = "client"))

            client.sendText(text)
        }

    }

    fun sendImage( imgFileUri: Uri){
        serviceScope.launch {
            _messages.emit(Message("client", content = "", msgType = MsgType.IMAGE, bitmap = Utils.uriToBitmap(context, imgFileUri)))

            client.sendFile(type = "image", imgFileUri)
        }

    }
//    fun send(type: MsgType, data: Any){
//        when(type){
//          MsgType.TEXT ->{
//              client.sendText(data as String)
//          }
//
//            MsgType.IMAGE -> {
//                client.sendFile(data as String)
//
//            }
//
//        }
//    }

    fun stopClient(){
        //停止客户端

        client.shutdown()
    }

    //  接收图片
    private fun onMessageImage(sender: String, imagePath: String) {
        serviceScope.launch {
            Log.d(TAG, "imagePath: ${imagePath}")
            val imageBitmap = BitmapFactory.decodeFile(imagePath)
            _messages.emit(Message(sender, msgType = MsgType.IMAGE, content = "", bitmap = imageBitmap))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopClient()
    }
}