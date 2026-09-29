package com.catcatpro.server

import com.alibaba.fastjson2.JSON
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import java.nio.charset.StandardCharsets

// 解码器（正确处理长度头）
class BinaryWithJsonDecoder : ByteToMessageDecoder() {
    companion object{
        const val TAG = "BinaryWithJsonDecoder"
    }
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        while (buf.readableBytes() >= 8){

            val binaryLength = buf.getInt(buf.readerIndex())  // 读取总长度（验证用）

            // 现在解析内部结构
            if (buf.readableBytes() < 4 + binaryLength + 4) return  // 需要 binaryLen

            buf.skipBytes(4)

            if (buf.readableBytes() < 4 + binaryLength) return

            val binary  = ByteArray(binaryLength)
            buf.readBytes(binary)

            val jsonLength = buf.readInt()
           println("$TAG,buf.readableBytes():　${buf.readableBytes()}, jsonLength: ${jsonLength}")
            if (buf.readableBytes() < jsonLength){
//                throw CorruptedFrameException("JSON data incomplete")
                continue
            }


            val jsonBytes = ByteArray(jsonLength)
            buf.readBytes(jsonBytes)
            println("$TAG, String(jsonBytes, StandardCharsets.UTF_8): ${String(jsonBytes, StandardCharsets.UTF_8)}")
            val mete = JSON.parseObject(String(jsonBytes, StandardCharsets.UTF_8)) as MutableMap<String, Any>

            out.add(BinaryWithJson(binary, mete))

        }
    }
}
