package com.catcatpro.common.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException


object Utils {

    @Throws(IOException::class)
    fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream!!.close()
        return bitmap
    }

    /**
     * 复制 assets 到 files 目录（最常用的位置）
     */
    fun copyAssetToAppFiles(
        context: Context,
        assetPath: String,
        subDir: String = ""
    ): File? {
        val targetDir = if (subDir.isNotEmpty()) {
            File(context.getExternalFilesDir(null), subDir)
        } else {
            context.getExternalFilesDir(null)
        } ?: return null

        return copyAssetToTarget(context, assetPath, targetDir)
    }

    /**
     * 核心复制函数：将 assets 复制到指定目标目录
     */
    private fun copyAssetToTarget(
        context: Context,
        assetPath: String,
        targetDir: File
    ): File? {
        // 确保目标目录存在
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val assetFile = File(assetPath)
        val targetFile = File(targetDir, assetFile.name)

        return try {
            if (isAssetDirectory(context, assetPath)) {
                // 如果是目录，递归复制
                copyAssetDirectory(context, assetPath, targetFile)
                targetFile
            } else {
                // 如果是文件，直接复制
                copyAssetFile(context, assetPath, targetFile)
                targetFile
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }



    /**
     * 判断 assets 中的路径是否是目录
     */
    private fun isAssetDirectory(context: Context, path: String): Boolean {
        return try {
            val list = context.assets.list(path)
            !list.isNullOrEmpty()
        } catch (e: IOException) {
            false
        }
    }


    /**
     * 复制单个文件
     */
    private fun copyAssetFile(
        context: Context,
        assetPath: String,
        targetFile: File
    ) {
        context.assets.open(assetPath).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * 递归复制目录
     */
    private fun copyAssetDirectory(
        context: Context,
        assetPath: String,
        targetDir: File
    ) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val items = context.assets.list(assetPath) ?: return

        for (item in items) {
            val assetItemPath = "$assetPath/$item"
            val targetItem = File(targetDir, item)

            if (isAssetDirectory(context, assetItemPath)) {
                copyAssetDirectory(context, assetItemPath, targetItem)
            } else {
                copyAssetFile(context, assetItemPath, targetItem)
            }
        }
    }


    /**
     * 通过R.raw.id获取uri
     */
    fun getRawResourceUri(context: Context, resId: Int): Uri {
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }

    //通过文件获取 uri
    fun getFileUriByFile(context: Context,file: File): Uri {

// 转换逻辑
        val contentUri: Uri?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0+ 使用 FileProvider
            // 注意：第二个参数必须与 Manifest 中声明的 authorities 一致
            contentUri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
        } else {
            // 低版本可直接转换
            contentUri = Uri.fromFile(file)
        }
        return contentUri
    }


    fun playMedia(context: Context, mediaResId: Int){
        val mediaPlayer = MediaPlayer()
        try {
            val afd = context.resources.openRawResourceFd(mediaResId)
            mediaPlayer.setDataSource(
                afd.fileDescriptor,
                afd.startOffset,
                afd.getLength()
            )


            // 设置音频流类型
            // Android 8.0+ 使用 AudioAttributes
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            mediaPlayer.prepare() // 同步准备，确保完全加载
            mediaPlayer.start()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    /**
     * 从 Uri 读取文件内容为字节数组
     */
    fun readBytesFromUri(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            null
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从 Uri 获取文件名
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex >= 0) {
                        return cursor.getString(displayNameIndex)
                    }
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }


    /**
     * 获取 Uri 指向文件的详细信息
     */
    fun getFileInfo(context: Context, uri: Uri): FileInfo? {
        var fileSize: Long = 0
        var mimeType: String? = null
        var displayName: String? = null

        // 查询元数据
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            }
        }

        // 获取 MIME 类型
        mimeType = context.contentResolver.getType(uri)

        return FileInfo(
            uri = uri,
            name = displayName,
            size = fileSize,
            mimeType = mimeType
        )
    }

    /**
     * 将图片bitmap转biteArray
     */
    fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }
    data class FileInfo(
        val uri: Uri,
        val name: String?,
        val size: Long,
        val mimeType: String?
    )
}