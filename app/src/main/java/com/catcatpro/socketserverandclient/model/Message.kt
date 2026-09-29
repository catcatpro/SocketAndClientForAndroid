package com.catcatpro.socketserverandclient.model

import android.graphics.Bitmap

data class Message(val clientId: String, val msgType: MsgType = MsgType.TEXT, val content: String = "", val bitmap: Bitmap? = null, val time: Long = System.currentTimeMillis(),val  sender: String = "")

enum class MsgType {
    TEXT,
    IMAGE
}