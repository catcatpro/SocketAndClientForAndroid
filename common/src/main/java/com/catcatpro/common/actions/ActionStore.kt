package com.catcatpro.common.actions


object ActionStore {
    //发送的动作
    object CLIENT_ACTIONS{
        //            val  SUCCESS: String ="action/connect_success"
        //任务开始前检查完成
        const val ACTION_TASK_CHECKED =  "client_action_task_checked"
        //任务步骤已完成
        const val  ACTION_TASK_STEP_COMPLETED =   "client_action_start_task_step_completed"
        //任务已完成
        const val ACTION_TASK_COMPLETED = "client_action_task_completed"
        //重启系统
        const val ACTION_SYSTEM_REBOOT = "client_action_system_reboot"

    }
    //收到的动作
    object SERVER_ACTIONS{
        //开始拍照
        const val ACTION_TASK_START_TAKE_PHOTO = "server_action_task_start_take_photo"

        //拍照成功
        const val ACTION_TASK_TAKE_PHOTO_SUCCESS = "server_action_task_take_photo_success"

        //拍照失败
        const val ACTION_TASK_TAKE_PHOTO_FAIL = "server_action_task_take_photo_fail"

        //开始录音
        const val ACTION_TASK_START_RECORDING = "server_action_task_start_recording"
        //录音成功
        const val ACTION_TASK_RECORD_SUCCESS = "server_action_task_start_record_success"
        //录音失败
        const val ACTION_TASK_RECORD_FAIL = "server_action_task_start_record_fail"

        //任务开始前检查
        const val ACTION_START_TASK_CHECK =   "server_action_start_task_check"

        //开始执行任务步骤
        const val  ACTION_START_TASK_STEP =  "server_action_start_task_step"

    }
}