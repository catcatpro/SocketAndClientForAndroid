package com.catcatpro.server


import android.content.Context
import android.net.Uri
import android.util.Log
import com.catcatpro.common.actions.ActionStore
import com.catcatpro.common.event.AppEvent
import com.catcatpro.common.event.AppEventBus
import com.catcatpro.common.utils.Utils
import com.catcatpro.server.enums.DeviceStatus
import io.netty.channel.socket.SocketChannel

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.EventLoopGroup
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.concurrent.GlobalEventExecutor
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.pathString


class NettySocketServer(private val ip: String,private val port: Int, private val context: Context ) {

    private lateinit var  channel :Channel
    private lateinit var   bossGroup:EventLoopGroup
    private lateinit var  workerGroup: EventLoopGroup

    lateinit var onMessage: (sender: String, content: String) -> Unit
    lateinit var onMessageImage: (sender: String, content: String) -> Unit

    //设备状态发生变化时回调
    lateinit var onDeviceStatusChange: (status: DeviceStatus, clientId: String, message: String) -> Unit

    //发生错误时回调
    lateinit var onError: (errorMessage: String) -> Unit
    @Throws(InterruptedException::class)
    fun start() {
        // 创建两个线程组
        // bossGroup: 接收客户端连接
        // workerGroup: 处理 I/O 操作
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup()

        try {
            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java) // TCP 参数配置
                .option<Int?>(ChannelOption.SO_BACKLOG, 128)
                .childOption<Boolean?>(ChannelOption.SO_KEEPALIVE, true)
                .childOption<Boolean?>(ChannelOption.TCP_NODELAY, true) // 添加日志处理器
                .handler(LoggingHandler(LogLevel.INFO))
                .childHandler(object : ChannelInitializer<SocketChannel?>() {
                    override fun initChannel(ch: SocketChannel?) {
                        val pipeline = ch?.pipeline()
//                        // 添加自定义处理器（按顺序）
//                        pipeline?.addLast(MessageDecoder()) // 解码器
//                        pipeline?.addLast(MessageEncoder()) // 编码器
//                        pipeline?.addLast(ServerBusinessHandler()) // 业务处理器

                        pipeline?.apply {
                            // 1. 粘包处理
//                            addLast(
//                                LengthFieldBasedFrameDecoder(
//                                    100 * 1024 * 1024,  // max 100MB
//                                    0, 4, 0, 0
//                                )
//                            )

                            addLast(BinaryWithJsonDecoder())
                            // 3. 业务处理（单例共享）
                            addLast(MixedDataHandler())
                        }

                    }


                })


            // 绑定端口并同步等待成功
            val future = bootstrap.bind(ip,port).sync()
            channel = future.channel()

            println("Netty Socket Server 启动成功，端口: " + port)


            // 等待服务器 socket 关闭
            future.channel().closeFuture().sync()
//            channel.eventLoop()
        } finally {
            shutdown()
        }
    }

    fun shutdown() {
        channel.close()
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
        println("Netty Socket Server 已关闭")
    }

    // 广播文本
    fun broadcast(msg: String){

//        val data = MessageProtocol(3.toByte(), payload = msg)
        val meta = mutableMapOf<String, Any>(
            "type" to "text",
            "sender" to "client-${channel?.id()?.asShortText()}",
            "timestamp" to System.currentTimeMillis()
        )

        val msg = BinaryWithJson(msg.toByteArray(StandardCharsets.UTF_8), meta)
        println("发送广播")
        BroadcastServer.broadcastExcept(channel, Unpooled.copiedBuffer(msg.encode()))
    }

    /**
     * 广播事件
     * @param action 动作
     * @param error_code 错误码
     * @param data 数据
     */
    fun broadcastEvent(action: String, error_code: Int, data: Any  = ""){
        val meta = mutableMapOf<String, Any>(
            "type" to "event",
            "action" to action,
            "code" to error_code.toString(),
            "sender" to "server",
            "timestamp" to System.currentTimeMillis()
        )

        val msg = BinaryWithJson(data.toString().toByteArray(StandardCharsets.UTF_8), meta)
        BroadcastServer.broadcastExcept(channel, Unpooled.copiedBuffer(msg.encode()))
    }

    /**
     * 广播文件
     * @param context Android Context
     * @param fileUri 文件fileUri
     */
    fun broadcastFile(type: String, fileUri: Uri){
        fileUri.let {
            // 方式1：直接读取字节（上传服务器）
            val fileData = Utils.readBytesFromUri(context, it)

            // 方式2：复制到私有目录获取路径（第三方库需要路径时）

            // 方式3：获取文件信息
            val info = Utils.getFileInfo(context, it)
            val meta = mutableMapOf<String, Any>(
                "type" to type,
                "fileName" to info?.name!!,
                "fileSize" to fileData?.size!!,
                "sender" to (channel.id()?.asShortText() ?: "unknown")
            )
            val msg = BinaryWithJson(fileData, meta)
            BroadcastServer.broadcastExcept(channel, Unpooled.copiedBuffer(msg.encode()))
        }
    }


    /**
     * 混合数据处理 Handler（Kotlin 版）
     * 处理 BinaryWithJson 消息，支持文本、文件、图片
     */

    //客户端处理类
    @Sharable
    inner class MixedDataHandler : SimpleChannelInboundHandler<BinaryWithJson>() {

        val TAG = "MixedDataHandler"

        // 内部类：客户端信息
        private inner class ClientInfo(// Getters...
            val clientId: String?, val ip: String?, val connectTime: Long
        ) {
            var lastHeartbeat: Long

            init {
                this.lastHeartbeat = connectTime
            }
        }

        // 存储所有客户端连接
        private val channels: ChannelGroup = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

        // 存储所有连接的客户端
        // 存储客户端信息
        private val clientMap = ConcurrentHashMap<Channel?, ClientInfo>()



        // 文件保存目录
//        private val saveDir =    context.getExternalFilesDir(null)



        //用于存放客户端发来文件数据-录音数据&图像数据
        private val doubleFiles = mutableListOf<BinaryWithJson>()

        /**
         * 客户端连接时触发
         */
        override fun channelActive(ctx: ChannelHandlerContext) {
            val channel = ctx.channel()
            BroadcastServer.ALL_CHANNELS.add(ctx.channel());
            channels.add(channel)

            val clientId = channel.id().asShortText()
            val ip = (channel.remoteAddress() as InetSocketAddress).getAddress().getHostAddress()

            val info = ClientInfo(clientId, ip, System.currentTimeMillis())
            clientMap.put(channel, info)

            System.out.printf(
                "客户端连接: %s, IP: %s, 当前在线: %d%n",
                clientId, ip, channels.size
            )

            onDeviceStatusChange(DeviceStatus.CONNECTED, clientId, "已连接")
            //  payload
            // 发送欢迎消息
//        val welcome = MessageProtocol(
//            1.toByte(),
//            payload =  "欢迎连接服务器，您的ID: " + clientId
//        )

            val text = "欢迎连接服务器，您的ID: " + clientId
            val meta = mutableMapOf<String, Any>(
                "type" to "notification",
                "sender" to "client-${channel?.id()?.asShortText()}",
                "timestamp" to System.currentTimeMillis()
            )

            val msg = BinaryWithJson(text.toByteArray(StandardCharsets.UTF_8), meta)
          println("server "+ "欢迎消息")

            channel.writeAndFlush(Unpooled.copiedBuffer(msg.encode()))
        }

        /**
         * 核心方法：处理完整的 BinaryWithJson 消息
         * 保证一个完整消息只触发一次
         */
        override fun channelRead0(ctx: ChannelHandlerContext, msg: BinaryWithJson) {
            println("Handler "+ msg.getString("type"))
            when (msg.getString("type")) {
                "text" -> handleText(ctx, msg)
                "file" -> handleFile(ctx, msg)
                "image" -> handleImage(ctx, msg)
                "ping" -> {
                    println("ping")
                }
//                "notification" -> handleNotification(ctx, msg)
                "event" -> handleEvent(ctx, msg)
//                "double_file" -> handleDoubleFile(ctx, msg)
                else -> handleUnknown(ctx, msg)
            }
        }

        /**
         * 处理文本消息
         */
        private fun handleText(ctx: ChannelHandlerContext, msg: BinaryWithJson) {
            val content = String(msg.binaryData, StandardCharsets.UTF_8)
            val sender = msg.getString("sender") ?: "unknown"

            println("[${msg.getString("type")}] $sender: $content")

            // 广播给其他客户端
//        broadcast(msg, ctx.channel())
            onMessage(sender, content)

        }

        /**
         * 处理文件上传
         */
        private fun handleFile(ctx: ChannelHandlerContext, msg: BinaryWithJson) {
            val fileName = msg.getString("fileName") ?: "unnamed"
            val fileData = msg.binaryData
            Log.d(TAG, "file: $fileName, data: $fileData")

//             保存文件
            val saveDir = File("${ context.externalCacheDir}")

            val output = File(saveDir, "${System.currentTimeMillis()}_$fileName")
            try {
                Files.write(output.toPath(), fileData)
                println("文件保存: ${output.absolutePath}")
            } catch (e: IOException) {
                e.printStackTrace()
                throw e
            }

            val fileUriArr = mutableListOf<Uri>()
            val saveFileUri = Utils.getFileUriByFile(context,output)
            fileUriArr.add(saveFileUri)
            Log.d(TAG, "saveFileUri: ${saveFileUri.toString()}")
//
            val action = msg.getString("action")
            if (action == null){
               println("$TAG, 没有action")
                return
            }
            //处理文件数据
            //code

            handleAction(action, "")


        }

        /**
         * 处理图片（缩略图预览）
         */
        private fun handleImage(ctx: ChannelHandlerContext, msg: BinaryWithJson) {
            val imageData = msg.binaryData
            val fileName = msg.getString("fileName") ?: "unnamed.jpg"
            val sender = msg.getString("sender") ?: "unknown"

            println("收到图片 $fileName (${imageData.size} bytes)")
            val saveDir = File("${ context.getExternalFilesDir(null).toString()}","cache")
            if (!saveDir.exists())
                saveDir.mkdirs()

            val output = Paths.get("${saveDir.path}/${System.currentTimeMillis()}_${fileName}")
            println("path: ${output.pathString}")
            try {
                Files.write(output, imageData, StandardOpenOption.CREATE)
                println("文件保存: ${output.pathString}")
            } catch (e: IOException) {
                print("err image: ${e.message}")
                e.printStackTrace()
            }

            // 广播原图给其他人
            onMessageImage(sender, output.pathString)
        }
//


//        /**
//         * 处理通知
//         */
//        private fun handleNotification(ctx: ChannelHandlerContext, msg: BinaryWithJson) {
//            println("收到处理通知: ${msg.metadata}")
//            val channel = ctx.channel()
//            val action = msg.getString("action")
//
//            handleAction(action)
//            val data = ""
//            val meta = mutableMapOf<String, Any>(
//                "code" to "0",
//                "type" to "event",
//                "sender" to "server",
//                "action" to ActionStore.CLIENT_ACTIONS.ACTION_TASK_CHECKED,
//                "timestamp" to System.currentTimeMillis()
//            )
//
//            val msg = BinaryWithJson(data.toByteArray(StandardCharsets.UTF_8), meta)
//            channel.writeAndFlush(Unpooled.copiedBuffer(msg.encode()))
//        }

        /**
         * 处理事件
         */
        private fun handleEvent(ctx: ChannelHandlerContext, msg: BinaryWithJson) {
          println("$TAG , 收到处理事件: ${msg.metadata}")
            val channel = ctx.channel()

            val data = ""
            val meta = mutableMapOf<String, Any>(
                "type" to "event",
                "sender" to "server",
                "action" to ActionStore.CLIENT_ACTIONS.ACTION_TASK_CHECKED,
                "code" to "0",
                "timestamp" to System.currentTimeMillis()
            )

            val msg = BinaryWithJson(data.toByteArray(StandardCharsets.UTF_8), meta)
            channel.writeAndFlush(Unpooled.copiedBuffer(msg.encode()))

            val action = msg.getString("action")
            handleAction(action)
        }

        /**
         * 处理客户端发来动作
         */
        private fun handleAction(action: String?, data: Any = ""){
            when(action){
                ActionStore.SERVER_ACTIONS.ACTION_START_TASK_CHECK -> {
                    AppEventBus.postEvent(AppEvent.CheckTaskPrepared(data))
                }

                ActionStore.SERVER_ACTIONS.ACTION_START_TASK_STEP -> {
                    AppEventBus.postEvent(AppEvent.StartTaskStep(data))
                }

                ActionStore.SERVER_ACTIONS.ACTION_TASK_START_TAKE_PHOTO ->{
                    AppEventBus.postEvent(AppEvent.TaskStartTakePhoto(data))
                }

                ActionStore.SERVER_ACTIONS.ACTION_TASK_TAKE_PHOTO_SUCCESS ->{
                    AppEventBus.postEvent(AppEvent.TaskTakePhotoSuccess(data))
                }
                ActionStore.SERVER_ACTIONS.ACTION_TASK_TAKE_PHOTO_FAIL->{
                    AppEventBus.postEvent(AppEvent.TaskTakePhotoFail(data))
                }

                ActionStore.SERVER_ACTIONS.ACTION_TASK_START_RECORDING->{
                    AppEventBus.postEvent(AppEvent.TaskStartRecording(data))
                }

                ActionStore.SERVER_ACTIONS.ACTION_TASK_RECORD_SUCCESS ->{
                    AppEventBus.postEvent(AppEvent.TaskRecordSuccess(data))

                }

                ActionStore.SERVER_ACTIONS.ACTION_TASK_RECORD_FAIL->{
                    AppEventBus.postEvent(AppEvent.TaskRecordFail(data))
                }

            }
        }

        /**
         * 处理双文件数据-音频数据和图像数据
         */
        private fun handleDoubleFile(ctx: ChannelHandlerContext, msg: BinaryWithJson){
            Log.d(TAG, "double_files size: ${doubleFiles.size}")
            doubleFiles.add(msg)
            if(doubleFiles.size < 2){
                return
            }
            Log.d("double_file", "double_files size: ${doubleFiles.size}")
            val saveFileUris = mutableListOf<Uri>()
            doubleFiles.forEach { msg ->
                //处理多文件
                val fileName = msg.getString("fileName") ?: "unnamed"
                val fileData = msg.binaryData
//            Log.d(TAG, "file: $fileName, data: $fileData")

//             保存文件
                val saveDir = File("${ context.externalCacheDir}")

                val output = File(saveDir, "${System.currentTimeMillis()}_$fileName")
                try {
                    Files.write(output.toPath(), fileData)
                    println("文件保存: ${output.absolutePath}")
                } catch (e: IOException) {
                    e.printStackTrace()
                    throw e
                }
                val saveFileUri = Utils.getFileUriByFile(context,output)
                Log.d(TAG, "saveFileUri: ${saveFileUri.toString()}")

                saveFileUris.add(saveFileUri)
            }

            val action = doubleFiles[0].getString("action")
            if (action == null){
                Log.d(TAG,"没有action")
                return
            }
            Log.d(TAG, "action: $action")
            handleAction(action, saveFileUris)
            doubleFiles.clear()
        }

        /**
         * 处理未知类型
         */
        private fun handleUnknown(ctx: ChannelHandlerContext, msg: BinaryWithJson) {
            println("收到未知类型消息: ${msg.metadata}")
        }

        /**
         * 广播给所有客户端（排除指定 Channel）
         */
        private fun broadcast(msg: BinaryWithJson, exclude: Channel? = null) {
            val data = msg.encode()

            channels.filter { it != exclude && it.isActive }
                .forEach { ch ->
                    ch.writeAndFlush(Unpooled.copiedBuffer(data))
                }
        }

        /**
         * 单播给指定用户
         */
        fun sendToUser(userId: String, msg: BinaryWithJson) {
            val data = msg.encode()

            channels.find { it.id().asShortText() == userId && it.isActive }
                ?.writeAndFlush(Unpooled.copiedBuffer(data))
        }

        /**
         * 获取在线人数
         */
        fun getOnlineCount(): Int = channels.size

        /**
         * 客户端断开时触发
         */
        override fun channelInactive(ctx: ChannelHandlerContext) {
            val clientId = ctx.channel().id().asShortText()
            channels.remove(ctx.channel())
            onDeviceStatusChange(DeviceStatus.UNCONNECTED, clientId, "已断开连接")
            println("客户端断开: ${ctx.channel().id()}，当前在线: ${channels.size}")
            ctx.close()
        }


        /**
         * 异常处理
         */
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            if (cause is IOException) {
                // 连接重置是常见网络问题，通常无需打印完整堆栈
              println("Connection reset: {}"+ctx.channel().remoteAddress().toString());
            } else {
                println("Unexpected error"+ cause.message.toString());
            }

            onError(cause.message.toString())
            ctx.close() // 必须关闭，避免资源泄漏

        }
    }

}
