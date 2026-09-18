package com.tangsnow.tangsnow.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片解码的统一异步入口。
 *
 * 存在意义：首页背景与定制主页预览都需要读用户相册里的大图。
 * 早期实现出现过两条不同口径的路径——主界面在 IO 线程做采样解码，
 * 定制页直接用 `ImageView.setImageURI` 在主线程整张解码（大图卡顿甚至 OOM）。
 * 收敛到本对象后，所有调用方共享同一套"采样 + 内存上限"策略。
 *
 * 约定：本对象的函数一律在 IO 线程执行并自行兜住异常，
 * 失败返回 null，调用方只需处理"没有图"这一种情况。
 */
object ImageLoader {

    /** 采样解码到不超过 reqW×reqH（内部还有绝对像素上限兜底） */
    suspend fun loadScaled(
        context: Context,
        uri: Uri,
        reqW: Int,
        reqH: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            Bitmaps.decodeSampled(context.applicationContext.contentResolver, uri, reqW, reqH)
        }.getOrNull()
    }
}
