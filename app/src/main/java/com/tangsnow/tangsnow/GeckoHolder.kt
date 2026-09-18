package com.tangsnow.tangsnow

import org.mozilla.geckoview.GeckoRuntime

/** 全局唯一的 [GeckoRuntime]，与 Application 生命周期同寿。 */
object GeckoHolder {
    @Volatile
    var runtime: GeckoRuntime? = null
}