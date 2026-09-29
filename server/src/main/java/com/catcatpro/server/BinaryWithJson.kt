package com.catcatpro.server


import com.alibaba.fastjson2.JSON
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets

/**
 * 二进制 + JSON 混合消息（Kotlin 版）
 */
data class BinaryWithJson(
    val binaryData: ByteArray,
    val metadata: MutableMap<String, Any>
) {
    /**
     * 编码为 ByteArray（发送用）
     */
    fun encode(): ByteArray {
        val jsonBytes = JSON.toJSONString(metadata).toByteArray(StandardCharsets.UTF_8)

        val buf = Unpooled.buffer().apply {
            writeInt(binaryData.size)      // 4字节二进制长度
            writeBytes(binaryData)            // 二进制数据
            writeInt(jsonBytes.size)          // 4字节JSON长度
            writeBytes(jsonBytes)             // JSON数据
        }
        println("binaryData.size ${buf.capacity()}")
        return ByteArray(buf.readableBytes()).apply {
            buf.readBytes(this)
        }.also {
            buf.release()
        }
    }

    /**
     * 从 ByteBuf 解码（接收用）
     */
    companion object {
        fun decode(buf: ByteBuf): BinaryWithJson {
            val binaryLen = buf.readInt()
            val binary = ByteArray(binaryLen).apply { buf.readBytes(this) }

            val jsonLen = buf.readInt()
            val jsonBytes = ByteArray(jsonLen).apply { buf.readBytes(this) }

            val meta = JSON.parseObject(String(jsonBytes, StandardCharsets.UTF_8))
                    as MutableMap<String, Any>

            return BinaryWithJson(binary, meta)
        }
    }

    /**
     * 快捷获取字段
     */
    fun getString(key: String): String? = metadata[key] as? String
    fun getInt(key: String): Int? = (metadata[key] as? Number)?.toInt()
    fun getLong(key: String): Long? = (metadata[key] as? Number)?.toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BinaryWithJson

        if (!binaryData.contentEquals(other.binaryData)) return false
        if (metadata != other.metadata) return false

        return true
    }

    override fun hashCode(): Int {
        var result = binaryData.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}