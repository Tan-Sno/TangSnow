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
     * v19：隐私政策 §7 与同意页摘要补一处**「文本承诺了实现做不到的事」**的修正 ——
     *   v16 核实了 `SITE_DATA` 已含站点权限，但漏了同一组位值的另一半：`SITE_DATA`(=471)
     *   同时含 `NETWORK_CACHE`(2) 与 `IMAGE_CACHE`(4)，因此勾选「Cookie 与站点数据」**必然
     *   连带清缓存**，而原文「按您的勾选分别清除 / clears exactly what you select」等于
     *   承诺了一个不存在的独立性。现按事实写明该连带，并在清除数据对话框上加一行前置提示
     *   （用户勾选前即可见）。该事实由新增的 `ClearFlagsGuardTest` 反射读内核常量断言，
     *   上游改位值即变红。
     *   另：政策正文的「更新日期」同步为 **2026-09-21**（v18 于 09-20 改文案时漏改日期，
     *   属既有疏漏，本次一并补正）。
     *   另：展示层修复 —— 政策正文里的 `**…**` 此前会被**原样显示成星号**（TextView 与
     *   AlertDialog 都不做 Markdown），现统一转成加粗（`util/LegalText`，含解析单测）。
     *   它只改渲染、不改文本事实，故不单独占一次版本。
     * v18：**新增对外端点，必须披露** ——「检查更新」改为读取 GitHub Releases 的公开版本信息。
     *   §4 增列 `api.github.com`，并写明三项限定：仅由用户点击触发、不在后台自检、
     *   只读公开数据且不携带任何设备标识或浏览记录；结尾「除 Mozilla 官方服务外不发请求」
     *   的绝对表述相应扩展。用户访问对象确有增加，属实质变化，故 +1。
     * v17：随「应用标识变更」（applicationId → io.github.tan_sno.tangsnow）提升。
     *   补记：该次**法律文本本身未改动**（`strings.xml` 未进那次提交），只是配合身份变更
     *   走一次重新同意。因新身份对所有用户都等同于全新安装，实际并未造成额外的重复同意；
     *   此处如实记录，以免后人对照文本时找不到 v17 改了什么。
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
     *
     *  19 → 20（2026-09-25，站点权限「记住我的选择」）：新增一类本机数据 —— 用户在
     *  站点权限询问弹窗勾选「记住我的选择」后，对应站点的授权决定（允许/拒绝）经内核
     *  StorageController 持久化在本机；可经「清除浏览数据 → Cookie 与站点数据」一并
     *  清除，未勾选时不保存，无痕会话一律不提供该勾选。政策 §1 数据枚举与摘要同步补句。
     *
     *  20 → 21（2026-09-26，披露事实纠错，全部经制品取证）：
     *  ① §4 名单主机更正 —— 解包 omni.ja 实测：shavar.services.mozilla.com 只出现在
     *     safebrowsing gethash（本应用已禁），跟踪保护名单实际经 Remote Settings
     *     （firefox.settings.services.mozilla.com，bucket main，带签名校验）本机更新；
     *     原披露的主机不产生流量、真实主机未披露。
     *  ② 摘要移除「请勿跟踪（DNT）」—— javap 证实 GeckoView 155 无 DNT API，
     *     omni.ja 无 browser.dnt pref；该承诺无法兑现，如实移除（GPC 保留）。
     *  ③ §4 与摘要中 AMO 的网络用途去掉「图标」—— 扩展/引擎图标全部内嵌于 APK，
     *     不构成网络访问。
     *  ④ 用户协议正文补更新日期（docs/TERMS.md 头部「见正文」指针由此落到实处）。
     */
    const val POLICY_VERSION = 21

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