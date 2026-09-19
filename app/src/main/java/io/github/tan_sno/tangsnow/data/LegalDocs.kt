package io.github.tan_sno.tangsnow.data

import androidx.annotation.StringRes
import io.github.tan_sno.tangsnow.R

/**
 * 法律文本（隐私政策 / 用户协议）的单一事实来源：
 *  - ConsentActivity（首次启动 / 政策更新）与「关于棠雪」页面读取同一组字符串资源，
 *    避免多份副本在后续更新中失同步；
 *  - [POLICY_VERSION]：每当隐私政策或用户协议文案有实质变化时必须 +1，
 *    已同意版本 < POLICY_VERSION 的用户在下次冷启动会重新看到同意页。
 */
object LegalDocs {

    /**
     * 当前协议版本：文案有实质变化时必须 +1。
     * v16：随功能补强同步文本 ——
     *  ① 无痕：应用侧已自行保证私有会话「既不写入、也不读取本机历史」（HistoryDelegate 加守卫，
     *     不再依赖引擎是否回调），故明确写入「无痕下链接不按既有历史着色」。
     *  ② 清除浏览数据：补上「站点权限与 HTTP 认证会话」（内核 ClearFlags 位值核实：SITE_DATA
     *     已含权限，另补 AUTH_SESSIONS），文本如实说明清除范围扩大。
     *  ③ 新增地址栏连接安全状态指示（完全本机判断、不联网）——安全浏览关闭后的风险提示补偿。
     *  ④ 新增可选「禁止截屏」（窗口级系统标志，不读取不收集任何信息）。
     *  ⑤ 用户协议能力列表与同意页摘要同步上述两项。
     * v15：两处「文案与实现/另一份文本不符」的修正 ——
     *  ① 用户协议第 1 条原称「不缓存任何网页内容」：与事实不符（浏览器必有网页缓存，
     *     隐私政策第 7 条亦将「缓存」列为可清除项），改为如实表述「缓存等浏览数据仅保存在设备本地」。
     *  ② 同意页摘要对崩溃日志的枚举漏了「可能记录最近访问站点的域名（仅域名）」——
     *     CrashLogger 确有该字段（用于崩溃复现），完整政策第 7 条已披露而摘要漏列，现已补齐。
     *  另：THIRD_PARTY_NOTICES.md 移除已下架扩展（SponsorBlock）的图标出处。
     * v14：把两处「承诺与实现不符」对齐到真实行为 ——
     *  ① 剪贴板：原政策只说「仅在点击复制时写入」，未披露应用在前台且持有焦点期间会
     *     监听剪贴板是否有新内容（仅用于提示与一键清除）；现如实披露，并同步把提示
     *     频率收敛为每个前台周期一次、文案不再指认「网页写入」。
     *  ② 网络访问对象：原政策称内置防护「在设备本地判断处理」且「不存在向第三方共享或
     *     跨境传输」。实测内核确认「安全浏览」会向 Mozilla 名单服务做远程哈希查询、
     *     并向 Google 上报下载；现已**关闭该远程查询能力**，同时如实说明跟踪保护名单
     *     由 Mozilla 官方名单服务在本机更新，并把「不共享/不跨境」的绝对表述限定为
     *     「除官方服务（addons.mozilla.org 与名单服务）之外」。
     *  另：扩展目录移除 Ghostery（其定位含广告拦截），政策与用户协议同步声明精选目录
     *  只收录隐私保护与工具类扩展。
     */
    const val POLICY_VERSION = 17

    // 注意：以下资源引用保持「非 const」，避免 Kotlin IR 在编译期常量折叠时
    // 因 R 常量跨模块求值触发 InterpreterMethodNotFoundError 内部错误。

    /** 隐私政策摘要（同意页直接展示；关于棠雪第一项） */
    @StringRes
    val SUMMARY: Int = R.string.legal_privacy_summary

    /** 完整隐私政策 */
    @StringRes
    val PRIVACY: Int = R.string.privacy_policy_text

    /** 用户协议 */
    @StringRes
    val AGREEMENT: Int = R.string.user_agreement_text

    /**
     * 开源协议（第三方组件许可、源码获取途径与商标声明）。
     *
     * ⚠️ 它**不参与同意门禁**：与隐私政策/用户协议不同，开源声明是**信息性文本**，
     * 不是用户需要「同意」的条款。因此修改它**不**需要提升 [POLICY_VERSION] ——
     * 否则用户会为一次纯声明更新而被迫重新走同意流程。
     */
    @StringRes
    val LICENSES: Int = R.string.open_source_text

    @StringRes
    val TITLE_LICENSES: Int = R.string.pref_open_source_title

    /** 摘要的段落标题 */
    @StringRes
    val TITLE_SUMMARY: Int = R.string.about_privacy_summary

    @StringRes
    val TITLE_PRIVACY: Int = R.string.about_privacy_full

    @StringRes
    val TITLE_AGREEMENT: Int = R.string.about_agreement
}

/** 同意状态门禁：所有会初始化浏览器引擎的入口都必须先通过它。 */
object ConsentGate {
    fun needsConsent(prefs: PreferenceStore): Boolean =
        prefs.acceptedPolicyVersion < LegalDocs.POLICY_VERSION

    fun agree(prefs: PreferenceStore) {
        prefs.acceptedPolicyVersion = LegalDocs.POLICY_VERSION
    }
}