<!-- ⚠️ 本文件是自动导出产物，请勿直接编辑。
     单一事实来源：app/src/main/res/values*/strings.xml
     重新生成：python tools/export_legal_docs.py -->

# 隐私政策 / Privacy Policy

> 本文件与应用内「设置 → 关于棠雪」展示的法律文本一致，对应应用内 `POLICY_VERSION = 22`。
> 最后更新：2026年09月26日
> **请勿直接编辑**；请修改 `strings.xml` 后运行 `python tools/export_legal_docs.py` 重新导出。

**目录 / Contents**　[中文](#隐私政策) · [English](#english)

---

## 隐私政策

更新日期：2026年09月26日

1. 我们处理哪些信息、为了什么

本应用为纯本地浏览工具。正常使用中，我们不在您的设备之外收集任何个人信息：书签、浏览历史、标签页会话快照（用于在进程被系统回收后恢复您上次打开的标签页，仅保存普通标签，无痕标签不落盘；默认开启，可在设置中关闭，关闭后立即清除已保存的快照）、主页定制与设置、以及您在站点权限弹窗勾选「记住我的选择」后对应站点的授权决定（允许/拒绝，**仅保存在本机**；可经「清除浏览数据」的『Cookie 与站点数据』一并清除，未勾选时不保存）等数据仅保存在您的设备本地。处理这些数据的目的，是向您提供网页浏览与本地辅助功能（例如历史联想）；这是为履行您所使用的本地工具功能所必需，本应用不将这些数据用于营销、画像或任何自动化决策。本应用不含统计、广告或追踪 SDK，不出售个人信息。

2. 权限说明（最小化原则）

· INTERNET：加载网页、获取扩展元数据与扩展包；

· CAMERA：仅在您主动使用「扫码」功能时调用，用于识别二维码，不做任何拍摄存储；

· ACCESS_LOCAL_NETWORK（Android 17 及以上）：仅在您打开局域网地址（例如路由器、NAS、打印机的管理页）时按需申请，用于与局域网内的设备建立连接。更低版本的系统没有这项权限，也不存在该限制；

· 内核运行所需（普通权限，由 Mozilla GeckoView 内核声明，系统不弹窗询问，亦不涉及个人数据）：ACCESS_NETWORK_STATE（检查网络连接状态）、WAKE_LOCK（播放音视频时保持唤醒）、MODIFY_AUDIO_SETTINGS（调节音量等音频设置）；

· 相册：本应用不申请相册读取权限。「从相册识别二维码」「自定义主页图片」使用 Android 系统照片选择器，只读取您点选的那一张图片，无法遍历相册、绝不上传；

· 剪贴板：仅在您点击「复制」时写入内容（扫码结果、复制链接、复制选中文字、一键清除）。另需说明：为提醒您剪贴板内容被改写，应用在处于前台且持有窗口焦点期间会监听系统剪贴板是否有新内容，仅用于弹出提醒并提供「一键清除」，每个前台周期最多提醒一次；该监听不读取、不保存、不上传剪贴板内容，也不区分写入来源（系统不提供来源信息，故提示不会指认某个网页）。

3. 默认搜索引擎

首次安装默认搜索引擎为「必应国内」（cn.bing.com），以保证国内网络下的访问稳定。列表中的境外或自定义条目仅由您主动选择启用；请仅在自身所在地区合规的网络与法律环境下使用，并自行承担责任。

4. 网络访问对象与处理位置

· addons.mozilla.org（AMO 官方）：扩展目录、元数据与官方签名扩展包的获取；

· 您主动访问的网站；

· api.github.com（GitHub 官方 API）：**仅当您点击「设置 → 检查更新」时**，读取棠雪的公开版本信息（最新版本号与更新说明），用于告知是否有新版本。该请求只读取公开数据，不携带任何设备标识、账号或浏览记录；应用**不会**在后台自动发起此请求。

· 内置跟踪保护默认开启，可在「设置 → 隐私与安全」调整强度（标准/严格/自定义）或整体关闭；拦截判断在本机完成，应用不会把您访问的网址或浏览行为上报给我们或任何第三方。其中「严格」档、以及自定义档勾选「拦截指纹跟踪」时，会额外在本机启用运行时级指纹保护（削弱可供指纹识别的浏览器特征）；该能力会改变部分网页可读取的浏览器信息、可能影响个别站点的显示效果，故仅在您主动选择这些档位时启用。「弹跳跟踪保护」（拦截以“红跳转”方式建立的跨站跟踪）与跟踪保护档位同开关，关闭跟踪保护时一并关闭。跟踪保护所使用的拦截名单由 Mozilla 官方远程设置服务（firefox.settings.services.mozilla.com）提供并在本机更新，仅下载名单与哈希前缀比对，不含您访问网址的明文，也不用于识别您的身份；

· 链接跟踪参数清理与「全球隐私控制（GPC）」信号默认开启、可在同一设置页关闭：前者在打开链接时本地移除跟踪参数，后者仅向支持网站发送声明、是否遵从由网站决定，均不向第三方传输您的浏览数据；

· 从本地导入 .xpi 时仅由内核在本机校验 Mozilla 官方签名后安装，不发起网络请求，亦不绕过任何地区限制。

除上述为获取扩展与名单更新而向 Mozilla 官方服务（addons.mozilla.org、名单服务）发起的必要请求，以及您主动点击「检查更新」时向 GitHub 官方 API（api.github.com）发起的版本查询外，本应用不向任何第三方共享、出售或委托处理您的个人信息，不存在其他跨境传输个人信息的情形。本应用已关闭内核的「安全浏览」远程查询能力，不因此向任何第三方发送您访问的网址。为弥补由此减少的风险提示，地址栏会显示连接安全状态（已加密 / 不安全，后者含已加载而未被拦住的不安全内容）—— 该判断完全在设备内完成，不发起任何网络请求。崩溃日志亦不上传（见第 7 条）。

5. 同意机制与撤回

首次使用或本政策/用户协议更新后，应用先展示政策摘要，仅在您点击「同意并继续」后才加载网页并运行浏览内核；点击「不同意」立即退出且不做任何数据处理，同意状态仅存本机。

您可随时撤回同意：停止使用并卸载本应用即可，卸载后本地数据随之删除；卸载前也可按第 7、8 条在应用内自行清理。

6. 第三方内容与扩展

网页内容由第三方网站提供，扩展由其开发者负责；应用仅按您的要求打开网站或安装来自 Mozilla 官方签名的扩展，不对第三方内容负责。下载文件请自行确认来源与安全性。本应用不内置、也不推荐任何广告拦截类扩展（其在部分司法辖区的可用性与法律评价存在差异，并可能构成不正当竞争）：精选目录只收录隐私保护与工具类扩展。您自行安装的第三方扩展由其开发者负责，请自行判断并承担相应后果。

7. 数据保留与删除

书签、历史、标签页会话快照等本地数据在您删除、执行「清除浏览数据」或卸载应用后被移除（无痕标签从不保存任何会话快照，也既不写入、也不读取本机历史 —— 因此在无痕标签中，页面链接不会依据您以往的访问记录着色）。「清除浏览数据」按您的勾选分别清除：Cookie 与站点数据（含站点权限与 HTTP 认证会话 —— 您此前允许的地理定位/通知等授权、以及 HTTP 登录状态会一并清除；又因内核把网页与图片缓存归入该项的清除范围，勾选它会**连带清除缓存** —— 无法做到「只清 Cookie 而不动缓存」）、缓存、本机历史记录、标签页会话快照、下载记录——书签不在其中，须在「资料库」单独管理；「资料库」支持一键清空历史、书签或下载记录（清空下载记录仅删除记录，不删除已下载的文件）。浏览历史仅保留最近 200 条，更早的条目会被自动移除。下载的文件保存在系统下载目录或应用专属目录，通过系统内容授权（FileProvider）打开或分享，绝不上传。程序异常时，若应用进程发生可被捕获的错误，会留存在设备本地的崩溃日志：仅含应用名称与版本、崩溃时间、设备型号与系统版本、进程与线程标识及错误堆栈，并可能记录最近访问站点的域名（仅域名、不含完整网址）；不含页面内容、搜索词、账号或其它可识别您个人的浏览信息。日志自动保留最近 10 条，可在「设置 → 关于棠雪 → 崩溃报告」逐条查看、分享或删除，应用不会自动上传。「禁止截屏」是可选的本机设置（默认关闭）：开启后仅对应用窗口加系统安全标志，阻止系统截图与「最近任务」预览显示内容；它不读取、不收集任何信息。本应用无云端账户，卸载即完成数据的完全删除。

8. 您的权利

因您的数据全部保存在本机，您可以随时：查看与更正（重新保存书签等）；删除（「资料库」一键清空历史/书签/下载记录、主页设置、崩溃报告删除、清除浏览数据）；卸载即删除全部数据。如您对本应用处理您的信息有疑问，可通过第 11 条联系方式提出，我们将在核实身份后协助处理（本应用不保存能识别您身份的信息，故通常直接以本机删除为准）。

9. 未成年人保护

未成年人应在监护人指导下使用本应用；应用不提供、不推荐任何面向未成年人的定向内容与功能。

10. 侵权与举报

如您认为网站或扩展内容侵犯您的权益，可优先联系对应网站/扩展方；涉及本应用自身的问题，可通过本项目 GitHub 仓库提交的 Issue 联系处理。

11. 联系我们

如有隐私或合规问题，请通过本项目 GitHub 仓库提交的 Issue 与我们联系（请注明问题类型与应用版本号）。

---

## English

Last updated: 2026-09-26

1. What we process and why

TangSnow is a strictly local browser tool. In normal use we do not collect any personal information outside your device: bookmarks, history, tab session snapshots (to restore your last open tabs if the process is reclaimed by the system; only normal tabs are saved, never private tabs; on by default and can be turned off in Settings, which clears any saved snapshot immediately), homepage customization and settings, **as well as the per-site permission decisions (allow/deny) for prompts where you ticked "Remember my choice" (stored on this device only; cleared together with "Cookies and site data" via Clear browsing data; nothing is stored unless you tick it)** live only on this device. These data are processed solely to provide browsing and local assistant features (e.g. history suggestions); this is necessary to provide the local-tool functionality you use. The app does not use your data for marketing, profiling or automated decision-making. It contains no analytics, advertising or tracking SDKs and does not sell personal information.

2. Permissions (minimized)

· INTERNET — load pages and fetch extension metadata and packages.

· CAMERA — used only when you actively scan a QR code; nothing is recorded or stored.

· ACCESS_LOCAL_NETWORK (Android 17 and later) — requested on demand only when you open a local network address (for example the admin page of your router, NAS or printer), so the app can connect to devices on that network. Older systems have no such permission and carry no such restriction.

· Engine operations (ordinary permissions declared by the Mozilla GeckoView engine; never prompted, no personal data involved): ACCESS_NETWORK_STATE (check connectivity), WAKE_LOCK (keep the device awake during media playback), MODIFY_AUDIO_SETTINGS (adjust media volume and audio settings).

· Gallery — the app does not request album/photo permissions. “Scan from image” and “Custom home image” use Android’s system photo picker and read only the single picture you select; it cannot browse your library and never uploads pictures.

· Clipboard — written only when you tap copy (scan results, copy link, copy selection, one-tap clear). To be transparent: to warn you when the clipboard is rewritten, the app listens for new system-clipboard content while it is in the foreground and holds window focus. This is used only to show a reminder with a one-tap “Clear”, at most once per foreground session. The listener never reads, stores or uploads clipboard content, and does not attribute the change to any particular page (the system provides no origin information).

3. Default search engine

The default is “Bing CN” (cn.bing.com) for stable, compliant access on mainland networks. Overseas or custom entries are enabled only by explicit user choice; please use them only where your network and local law allow, at your own responsibility.

4. Network targets and where processing happens

· addons.mozilla.org (AMO) — extension catalog, metadata and signed packages.

· Sites you visit yourself.

· api.github.com (GitHub’s official API) — **only when you tap Settings → Check for updates**, to read TangSnow’s public release information (latest version number and release notes) so the app can tell you whether an update exists. This request reads public data only and carries no device identifier, account or browsing record; the app **never** issues it automatically in the background.

· Built-in tracking protection is on by default and can be adjusted (Standard/Strict/Custom) or fully turned off in Settings → Privacy & Security; blocking decisions are made on your device, and neither we nor any third party receives the URLs you visit or your browsing behaviour. In the Strict profile — and in Custom when “Block fingerprinting” is ticked — additional runtime fingerprinting protection is enabled on your device (it weakens browser characteristics usable for fingerprinting); because it changes information some pages can read and may affect how a few sites render, it is enabled only in the profiles you explicitly choose. “Bounce tracking protection” (blocking cross-site tracking built through redirection chains) follows the same setting and is turned off together with tracking protection. The filter lists used by tracking protection are provided and updated on-device by Mozilla’s official remote settings service (firefox.settings.services.mozilla.com); only lists and hash-prefix comparisons are downloaded, never the plain text of the URLs you visit, and they are not used to identify you.

· Stripping tracking parameters from links and the Global Privacy Control (GPC) signal are on by default and can be turned off in the same settings page: the former removes tracking parameters locally when opening links; the latter only sends a declaration to supporting websites (compliance is the site’s own choice). Neither transfers your browsing data to any third party.

· Local .xpi import is verified and installed on-device by the engine only, makes no network request and bypasses no regional limits.

Apart from the necessary requests made to Mozilla’s official services (addons.mozilla.org and the list service) to fetch extensions and list updates, and the version lookup made to GitHub’s official API (api.github.com) when you tap “Check for updates”, the app does not share, sell or commission the processing of your personal information with any third party, and there is no other cross-border transfer of personal information. The engine’s remote “Safe Browsing” lookups are switched off, so no URL you visit is sent to any third party on that account. To make up for the reduced warning coverage, the address bar shows the connection security state (encrypted / not secure, the latter including insecure content that was loaded instead of blocked) as judged entirely on this device — it issues no network requests. Crash logs are never uploaded either (see §7).

5. Consent and withdrawal

On first use, or after this policy or the user agreement changes, a summary is shown first; no web content or engine starts until you tap “Agree and continue”. “Disagree” exits immediately without processing, and consent state lives only on this device.

You can withdraw consent at any time: stop using the app and uninstall it — local data are deleted with it. Before uninstalling you can also clear data in-app as described in §7 and §8.

6. Third-party content and extensions

Pages are provided by their own sites and extensions by their developers; the app merely opens pages you request or installs Mozilla-signed extensions from the official source. Downloaded files should be checked for source and safety by yourself. The app neither bundles nor recommends any ad-blocking extension (their availability and legal status differ across jurisdictions and they may constitute unfair competition): the curated catalog lists privacy and utility extensions only. Third-party extensions you install yourself are the responsibility of their developers — please evaluate them and accept the consequences yourself.

7. Data retention and deletion

Bookmarks, history, tab session snapshots and other local data are removed when you delete them, run “Clear browsing data”, or uninstall the app (private tabs never save any session snapshot, and neither write to nor read from on-device history — so in a private tab, page links are never highlighted based on sites you visited before). “Clear browsing data” clears exactly what you select: cookies & site data (including site permissions and HTTP auth sessions — permissions you previously granted, such as location or notifications, and HTTP login state are cleared as well; and because the engine groups the page and image caches into this option, selecting it **also clears the cache** — clearing cookies while leaving the cache untouched is not possible), cache, local history, tab session snapshots and download records — bookmarks are not included and are managed separately in the Library, which also supports clearing history, bookmarks or download records in one tap (clearing download records only removes the records, never the downloaded files). Browsing history keeps only the most recent 200 entries; older entries are removed automatically. Downloaded files are stored in the system download directory or the app-private directory and are opened or shared through system content authorization (FileProvider), never uploaded. In case of a catchable unexpected error in the app process, a crash log is saved on this device only: it contains only the app name and version, crash time, device model and OS version, process and thread identifiers and the error stack, and may record the domain of the site you most recently visited (domain only, never the full URL). It never includes page content, search terms, account details or other browsing information that identifies you. Logs are capped at the 10 most recent and can be viewed, shared or deleted one by one via Settings → About TangSnow → Crash reports; they are never uploaded. “Block screenshots” is an optional on-device setting (off by default): when on, it only adds a system security flag to the app window so that system screenshots and the Recents preview show no content; it reads and collects nothing. TangSnow has no cloud account — uninstalling fully deletes your data.

8. Your rights

Because your data live entirely on this device, you can at any time: view and correct them (e.g. re-save bookmarks); delete them (one-tap clearing of history/bookmarks/download records in the Library, homepage settings, crash-report deletion, “Clear browsing data”); uninstall to delete everything. If you have questions about how TangSnow handles your information, contact us via §11; after verifying identity we will assist (as the app does not store identity-revealing data, on-device deletion is normally the complete remedy).

9. Protection of minors

Minors should use this app under the guidance of their guardians; the app offers no content or features targeting minors.

10. Infringement and reporting

For content hosted by third-party sites or extensions, contact those providers first. For issues about this app itself, contact us via the project’s GitHub repository.

11. Contact

For privacy or compliance questions, contact us via the project’s GitHub repository (please mention the issue type and app version).
