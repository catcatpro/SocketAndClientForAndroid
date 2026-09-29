package com.catcatpro.server
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
object BroadcastServer {
    // 存储所有客户端连接（线程安全）
    val ALL_CHANNELS: ChannelGroup = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)




    /**
     * 广播给所有客户端
     */
    fun broadcast(msg: ByteBuf) {
        // 必须在 EventLoop 线程执行
        ALL_CHANNELS.writeAndFlush(msg)
    }

    /**
     * 广播（排除自己）
     */
    fun broadcastExcept(exclude: Channel?, msg: ByteBuf) {
        for (ch in ALL_CHANNELS) {
            if (ch !== exclude && ch.isActive()) {
                ch.writeAndFlush(msg)
            }
        }
    }
}