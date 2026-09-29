package com.catcatpro.client

import android.content.Context
import android.net.Uri
import com.catcatpro.common.utils.Utils
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.Unit
import kotlin.io.path.pathString

/**
 * 与服务端 BinaryWithJson 协议对齐的 Netty 客户端
 */
class NettySocketClient(
    private val host: String,
    private val port: Int,
    private val context: Context,
    /** 心跳间隔（秒），0 表示关闭心跳 */
    private val heartbeatIntervalSec: Long = 30,
    /** 是否自动重连 */
    private val autoReconnect: Boolean = true,
    /** 重连基础延迟(ms) */
    private val reconnectBaseDelayMs: Long = 1000,
    /** 重连最大延迟(ms) */
    private val reconnectMaxDelayMs: Long = 30_000,
    private val connectTimeoutMs: Int = 5000,
    /** 客户端标识，可自定义 */
    private val clientId: String = "client-${System.currentTimeMillis() % 100000}"
) {

    private val log = LoggerFactory.getLogger(NettySocketClient::class.java)

    private val group: EventLoopGroup = NioEventLoopGroup(1)

    @Volatile
    private var channel: Channel? = null

    private val started = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)

    @Volatile
    private var manuallyClosed = false

    private val closeFuture = CompletableDeferred<Unit>()
    fun awaitClose(): Unit = runBlocking { closeFuture.await() }

    // ---------------- 回调 ----------------


    /** 收到文本消息 */
    var onText: (sender: String, content: String) -> Unit = { _, _ -> }

    var onMessageImage: (sender: String, content: String) -> Unit ={_, _ -> }

    /** 收到事件消息 */
    var onEvent: (action: String, code: String, data: String) -> Unit = { _, _, _ -> }

    /** 收到文件/二进制数据 */
    var onFile: (fileName: String, data: ByteArray) -> Unit = { _, _ -> }

    /** 收到通知 */
    var onNotification: (content: String) -> Unit = { }

    /** 连接建立 */
    var onConnected: () -> Unit = { }

    /** 连接断开 */
    var onDisconnected: () -> Unit = { }

    /** 发生错误 */
    var onError: (message: String) -> Unit = { }

    // ---------------- 生命周期 ----------------

    fun start() {
        if (!started.compareAndSet(false, true)) {
            log.warn("Client already started")
            return
        }
        manuallyClosed = false
        doConnect()
    }

    /** 阻塞等待连接成功 */
    @Throws(InterruptedException::class)
    fun startSync(timeoutMs: Long = 10_000) {
        start()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isConnected()) return
            Thread.sleep(50)
        }
        if (!isConnected()) {
            throw IllegalStateException("Connect timeout after ${timeoutMs}ms")
        }
    }
//    通知关闭挂起函数
    // 在 channel 关闭时（channelInactive 或 shutdown）完成它
    private fun notifyClosed() {
        if (!closeFuture.isCompleted) closeFuture.complete(Unit)
    }
    fun shutdown() {
        if (!started.compareAndSet(true, false)) return
        manuallyClosed = true
        try {
            channel?.close()?.sync()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS)
            log.info("Client shutdown")
            notifyClosed()
        }
    }

    fun isConnected(): Boolean = channel?.isActive == true

    // ---------------- 发送 API ----------------

    /** 发送文本 (type = "text") */
    fun sendText(content: String): Boolean {
        val meta = mutableMapOf<String, Any>(
            "type" to "text",
            "sender" to clientId,
            "timestamp" to System.currentTimeMillis()
        )
        return send(BinaryWithJson(content.toByteArray(StandardCharsets.UTF_8), meta))
    }

    /** 发送事件 (type = "event") */
    fun sendEvent(action: String, code: Int = 0, data: String = ""): Boolean {
        val meta = mutableMapOf<String, Any>(
            "type" to "event",
            "action" to action,
            "code" to code.toString(),
            "sender" to clientId,
            "timestamp" to System.currentTimeMillis()
        )
        return send(BinaryWithJson(data.toByteArray(StandardCharsets.UTF_8), meta))
    }

    /** 发送文件 (type = "file") */
    fun sendFile(
        type: String,
        fileUri: Uri,
        action: String? = null
    ){
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
                "sender" to clientId,
                "timestamp" to System.currentTimeMillis()
            )
            if (action != null) meta["action"] = action
             send(BinaryWithJson(fileData, meta))
            }

    }



    /** 发送心跳 (type = "ping") */
    fun sendPing(): Boolean {
        val meta = mutableMapOf<String, Any>(
            "type" to "ping",
            "sender" to clientId,
            "timestamp" to System.currentTimeMillis()
        )
        return send(BinaryWithJson(ByteArray(0), meta))
    }

    /** 通用发送 */
    fun send(msg: BinaryWithJson): Boolean {
        val ch = channel
        if (ch == null || !ch.isActive) {
            log.warn("Send failed: channel not active")
            return false
        }
        return try {
            ch.writeAndFlush(Unpooled.copiedBuffer(msg.encode()))
                .await(3, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            log.error("Send error", e)
            false
        }
    }

    // ---------------- 内部实现 ----------------

    private fun doConnect() {
        if (manuallyClosed) return

        val bootstrap = Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val p = ch.pipeline()

                    // 1. 心跳(写空闲触发)
                    if (heartbeatIntervalSec > 0) {
                        p.addLast(
                            IdleStateHandler(
                                0,
                                heartbeatIntervalSec,
                                0,
                                TimeUnit.SECONDS
                            )
                        )
                    }

                    // 2. 与服务端一致的解码器
                    p.addLast(BinaryWithJsonDecoder())

                    // 3. 业务 handler
                    p.addLast(ClientHandler())
                }
            })

        val future = bootstrap.connect(host, port)
        future.addListener { f ->
            if (f.isSuccess) {
                val cf = f as io.netty.channel.ChannelFuture
                channel = cf.channel()
                log.info("Connected to {}:{}", host, port)
            } else {
                log.warn("Connect failed: {}", f.cause()?.message)
                onError(f.cause()?.message ?: "connect failed")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (manuallyClosed || !autoReconnect) return

        val attempt = reconnectAttempts.incrementAndGet()
        val exp = (attempt - 1).coerceAtMost(6)
        val delay = (reconnectBaseDelayMs shl exp).coerceAtMost(reconnectMaxDelayMs)

        log.info("Reconnect in {}ms (attempt #{})", delay, attempt)

        group.schedule({
            if (!manuallyClosed && !isConnected()) {
                doConnect()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    /**
     * 内部 handler —— 处理所有从服务端来的 BinaryWithJson
     */
    private inner class ClientHandler : SimpleChannelInboundHandler<BinaryWithJson>() {

        override fun channelActive(ctx: ChannelHandlerContext) {
            reconnectAttempts.set(0)
            log.info("Channel active: {}", ctx.channel().remoteAddress())
            onConnected()
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            log.info("Channel inactive")
            onDisconnected()
            scheduleReconnect()
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: BinaryWithJson) {
            try {
                dispatch(msg)
            } catch (e: Exception) {
                log.error("Handle message error", e)
                onError(e.message ?: "handle error")
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent && evt.state() == IdleState.WRITER_IDLE) {
                sendPing()
                log.debug("Heartbeat ping sent")
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            log.error("Channel exception: {}", cause.message)
            onError(cause.message ?: cause.javaClass.simpleName)
            ctx.close()
        }

        /**
         * 根据 type 分发
         */
        private fun dispatch(msg: BinaryWithJson) {
            val type = msg.getString("type")
            val sender = msg.getString("sender") ?: "server"

            when (type) {
                "text" -> {
                    val content = String(msg.binaryData, StandardCharsets.UTF_8)
                    onText(sender, content)
                }

                "event" -> {
                    val action = msg.getString("action") ?: ""
                    val code = msg.getString("code") ?: "0"
                    val data = String(msg.binaryData, StandardCharsets.UTF_8)
                    onEvent(action, code, data)
                }

                "image" -> {
                    handleImage(msg)
                }

                "file" -> {
                    val fileName = msg.getString("fileName") ?: "unnamed"
                    onFile(fileName, msg.binaryData)
                }

                "notification" -> {
                    val content = String(msg.binaryData, StandardCharsets.UTF_8)
                    onNotification(content)
                }

                "ping" -> log.debug("Recv ping from server")

                else -> log.warn("Unknown message type: {}", type)
            }
        }

        /**
         * 处理图片（缩略图预览）
         */
        private fun handleImage( msg: BinaryWithJson) {
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
    }
}