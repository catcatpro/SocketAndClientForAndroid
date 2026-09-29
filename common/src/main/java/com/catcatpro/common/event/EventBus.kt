package com.catcatpro.common.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

// base/src/main/java/com/yourapp/event/EventBus.kt
object AppEventBus {

    // 使用 SharedFlow 作为事件总线
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,  // 不重放历史事件
        extraBufferCapacity = 64  // 缓冲容量，避免丢事件
    )

    // 暴露只读的 Flow
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    // 发送事件（可以在任何线程调用）
    suspend fun post(event: AppEvent) {
        _events.emit(event)
    }

    // 非 suspend 版本（用于不想写协程的地方）
    fun postEvent(event: AppEvent) {
        CoroutineScope(Dispatchers.Main).launch {
            _events.emit(event)
        }
    }
}

// 定义事件类型
sealed class AppEvent {
//    data class LoginSuccess(val userId: String) : AppEvent()
//    object Logout : AppEvent()
//    data class CartItemChanged(val itemCount: Int) : AppEvent()
//    data class ShowToast(val message: String) : AppEvent()
    data class CheckTaskPrepared(val data: Any): AppEvent() //验证任务是否准备好
    data class StartTaskStep(val data: Any): AppEvent() //开始任务步骤

    data class TaskStartTakePhoto(val data: Any) : AppEvent() // 开始拍照
    data class TaskTakePhotoSuccess(val data: Any) : AppEvent() // 拍照成功
    data class TaskTakePhotoFail(val data: Any) : AppEvent() // 拍照失败

    data class TaskStartRecording(val data: Any): AppEvent() //开始录音
    data class TaskRecordSuccess(val data: Any): AppEvent() //录音成功
    data class TaskRecordFail(val data: Any): AppEvent() //录音失败
}