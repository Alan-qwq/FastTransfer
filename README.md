# 极速互传 (FastTransfer)

> 局域网「文件 + 文字」极速互传，兼容 **LocalSend v2.2** 协议。
> 一套 APK 同时适配手表、手机、平板、词典笔等全尺寸屏幕。

- 包名：`com.alan.fasttransfer`
- 最低版本：**Android 5.0 (API 21)**；目标版本：**Android 16 (API 36)**
- 网络：**纯局域网直连**，不经过任何服务器，不需要登录，不需要账号

---

## 1. 功能

| 分类 | 能力 |
|---|---|
| 传文件 | 多选文件、批量发送、发送进度 / 实时速率 / 剩余时间、可随时取消 |
| 传文字 | 输入一段文字直接发送；接收端以文本气泡展示，一键复制，并自动留档为 `.txt` |
| 设备发现 | UDP 组播自动发现（与 LocalSend 同组播组）；手动输入 `IP:端口` 直连；**扫码连接**；同网段并发扫描兜底；设备列表里常驻一项虚拟的**「浏览器」** |
| 接收 | 弹窗确认（接受 / 拒绝）、收发进度、保存位置提示、PIN 码校验；**展示连接二维码** |
| 网页传输 | 手机起一个内置网页服务并**给出一个网址**，电脑用浏览器打开即可收发，电脑端零安装。手机 → 电脑：在设备列表里点**「浏览器」**，电脑打开地址下载；电脑 → 手机：在同一个网页里选文件，但**必须手机点「接收」**才真的落盘 |
| 设置 | 设备名、可见性、PIN、端口、自动接受、屏幕常亮、**网页传输**、深色模式、**显示大小**、**自定义保存位置** |
| 关于 | 版本号、协议版本，以及**官方网站**和 **GitHub 仓库**两个外链入口（交给系统浏览器打开，不内置 WebView） |
| 其他 | 传输历史记录、群发、被其他应用「分享到」极速互传 |

### 「显示大小」

设置 → 传输 → 显示大小，**输入百分比**（50–200，100 为标准）后点确定。越界或非数字会就地报错，不会改坏设置。

实现方式：覆盖 display density 而不是 fontScale —— 也就是**间距、控件、字号一起缩放**，
效果等同于系统设置里的「显示大小」。

- Activity 侧用 `applyOverrideConfiguration()` 改自己的配置（**不新建 Context**，见下方「已知坑」）；
- Fragment 侧在 `onAttach()` 里对**非 Activity 的 Context** 做包装；
- 基准密度取**应用 Context** 的原始密度，避免在已缩放的基础上反复相乘。
- 改完 `recreate()` 立即重算。

### 「保存位置」

设置 → 传输 → 保存位置 → **修改** → 二选一：

- **手动输入路径**：直接写绝对路径，例如 `/sdcard/Download/FastTransfer`、`/storage/emulated/0/我的文件`。
  支持多种写法别名（`/sdcard`、`/storage/self/primary`、`/mnt/sdcard`、`外部存储/…` 都能认）；
  会自动在目标下建 **FastTransfer** 子目录，不弄乱你已有的目录。路径不可用会当场提示，不会写坏设置。
- **用系统界面选择**：调 SAF 目录选择器选任意目录（含 SD 卡），并持久化授权。

选过之后会出现「恢复默认」。

落盘优先级（`FileStorage.openTarget`）：

1. **用户自定义目录**：手写路径由 `PathNormalizer` 归一成 `卷:相对路径`，
   再拼成 SAF tree Uri 写入（Android 10+ 也能落盘，不依赖 SAF 授权）；
2. 否则 **Android 10+**：写公共「下载/FastTransfer」（MediaStore，无需权限）；
3. 否则 **Android 9-**：写公共「下载/FastTransfer」（需要存储权限），都不可写时退回应用目录兜底。

> 应用专属目录**不再是可选项** —— 只在「公共下载目录不可写」时作为兜底，界面上不出现。

### 扫码连接怎么用

1. **接收方**：进「接收」页，中间会有一张二维码（当前 IP + 端口，若开了 PIN 也会带进去），点二维码或「放大」可全屏显示，方便对方隔远一点扫。
2. **发送方**：进「发送」页 → 点「扫码连接」→ 对准对方二维码 → 自动填入地址并连接，成功后直接出现在设备列表里。
3. 二维码内容形如 `fasttransfer://connect?host=192.168.1.7&port=53317&pin=123456`；
   也兼容直接扫到 `192.168.1.7:53317` 这种纯文本。**PIN 会随扫码自动带上，不用手输。**
4. 没有摄像头、或相机权限被拒的设备，界面会提示改用「手动连接」—— 不会卡住。

> 实现：CameraX 取景 + **ZXing core**（纯 Java，不依赖 Google Play 服务，体积约 500KB）。
> 实测 release 包从 4.90MB 增至 5.72MB。

### 网页传输怎么用（电脑不用装任何东西）

场景：手边只有一台电脑，不想装 App，也不想连数据线。

**手机 → 电脑**

1. **手机**：进「发送」页，选好文件或输入文字 —— 和平时发送完全一样的操作。
2. **设备列表里会多出一项「浏览器」**，点它。
3. 手机弹出地址，形如 `http://192.168.1.7:53317/`，**点「复制」就能拷走**。
4. **电脑**：浏览器打开这个地址，页面最上面就是「手机要发给你的文件」，
   文件点一下就保存；文字直接铺开显示，带一键复制。

「浏览器」是设备列表里的**虚拟条目**（App 里的设备名就叫「浏览器」，不是「电脑」），
不走 LocalSend 协议，也不需要被 UDP 发现。
它永远排在最前面，因为它不是「发现到的设备」，而是随时可用的出口。
点「发送」时弹出的目标选择框里也有它。

**电脑 → 手机**

同一个网页往下滚，点「选择文件」或把文件拖进去，支持多选、支持大文件，有进度条。

**但电脑说了不算 —— 手机上会弹出「接收 / 拒绝」，点了接收才开始真的传。**
这一步和局域网传文件用的是同一套确认弹窗，所以来源、超时、拒绝的行为都一致。
没经过确认的请求，一个字节都写不进手机。

**其他**

如果手机开了配对码（PIN），网页会先要求输入，输对后换一个会话 cookie，不用每次重输。

几个刻意的设计取舍：

- **复用同一个端口**（默认 53317），不额外开监听。手机侧只有一个监听端口，
  权限和防火墙只需要处理一次；关掉「网页传输」时这些路径直接 404。
  点「浏览器」会自动开启这个开关 —— 用户点了它就是明确要用，不必再让他去设置里找。
- **上传先过一道确认门**：浏览器把文件清单 POST 到 `/web/prepare`，
  手机弹窗询问用户；同意后服务端按**文件数**发一张**3 分钟过期**的凭证，
  `/web/upload` 凭它才肯落盘。配额按文件数给而不是只给一次，
  是因为一个批次里每个文件都是独立的 multipart 请求 —— 只给一次的话
  第二个文件会被自己的防重放规则挡掉。先送清单再传字节，
  既让用户一次确认多个文件，也不用把整个请求体缓冲到磁盘。
- **API 和页面分开应答**：未授权时页面回登录页 HTML，而 `/web/prepare`、
  `/web/upload` 这些 API 必须回 401 JSON。混在一起会让浏览器拿到 200 的 HTML、
  解析不出 JSON 然后静默失败，表现成「点了接收也传不过去」且页面上看不到任何原因。
- **手机发文件时不复制**：待发队列里存的是原始 `content://` Uri，电脑点下载时
  由 App 用自己的读权限流式读出来直接写进 HTTP 响应。传一个 2GB 的视频
  不会先占掉 2GB 空间，也不用等一次拷贝。代价是依赖 App 进程活着，
  所以 Uri 会被持久化记录，并且选文件时申请了可持久化的读权限。
- **上传是流式落盘的**，不在内存里攒整份文件；单个 part 另有 2GB 上限，
  防止恶意请求把存储写爆。
- **失败原因必须可见**：上传出错时网页会把服务器返回的具体原因显示出来
  （例如「没有写入权限，请在手机设置里换一个保存位置」），
  而不是只显示一个「失败」——只报「失败」等于没报。
- **不依赖任何前端构建链**：HTML/CSS/JS 全部内嵌在 Java 里，不联网加载 CDN，
  断网热点环境下也能正常打开，并且跟随系统深色模式。

---

## 2. 快速开始

### 2.1 直接安装现成 APK

```
app/build/outputs/apk/debug/app-debug.apk     # 调试版
app/build/outputs/apk/release/app-release.apk # 发布版（暂用 debug 签名，可直接安装）
```

安装：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 2.2 自己编译

```bash
# 需要 JDK 17 与 Android SDK（compileSdk 36 / minSdk 21）
# local.properties 里写好自己的 sdk.dir
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk

# 十个离线校验任务（纯 JVM，不需要模拟器或真机）
./gradlew protocolTest         # 协议层 + Expect: 100-continue（36 项断言）
./gradlew sessionRenderTest    # 会话「界面呈现状态机」自测（17 项断言）
./gradlew uiScaleTest          # 显示大小换算自测（18 项断言）
./gradlew pathNormalizerTest   # 保存路径归一化自测（31 项断言）
./gradlew multipartTest        # multipart 流式解析自测（32 项断言）
./gradlew webPageTest          # 网页层自测（102 项断言）
./gradlew historyOpenTest      # 记录「能否用其他应用打开」自测（9 项断言）
./gradlew guardCheck           # 递归防护静态校验
./gradlew contextCheck         # Context 包装防护静态校验
./gradlew layoutCheck          # 布局 inflate 静态校验
./gradlew lintDebug            # 静态检查
```

> Windows 下用 `gradlew.bat`。首次执行会自动下载 Gradle 9.5.1。

### 2.3 十个离线校验任务

全部离线、纯 JVM，不需要设备或模拟器。合计 **248 项断言**，加上 `layoutCheck` 的静态扫描。

| 任务 | 作用 |
|---|---|
| `./gradlew protocolTest` | 编译并运行**真实的协议层实现**，验证 HTTP 解析、chunked 请求体、**`Expect: 100-continue`**、JSON 往返、上传进度、字节一致性（36 项断言） |
| `./gradlew sessionRenderTest` | 验证 `TransferSession.renderState()`：终态映射、终态判定，以及「完成后继续刷新不得回到进行中」（17 项断言） |
| `./gradlew uiScaleTest` | 验证显示大小的百分比钳制与 density 换算，含 0 / 负数 / 超界 / 密度缺失等边界（18 项断言） |
| `./gradlew pathNormalizerTest` | 验证手写保存路径的归一化：多种别名写法、中文前缀、`path:` 内部格式、非法输入、幂等性（31 项断言） |
| `./gradlew multipartTest` | 验证网页上传的 multipart 流式解析：二进制、多文件、正文里混入假边界、边界跨读取块、逐字节喂入（32 项断言） |
| `./gradlew webPageTest` | 验证网页层：**上传凭证必须一次性且会过期**（没确认过就进不来）、文件名不得目录穿越、HTML 转义防 XSS、页面结构与深色模式、窄屏适配（102 项断言） |
| `./gradlew historyOpenTest` | 验证传输记录的「能否用其他应用打开」：位置 / MIME 齐全才可打开、纯文字记录不走打开而是复制、旧记录字段缺失不崩（9 项断言） |
| `./gradlew guardCheck` | 校验「程序化设置底栏选中项」处有递归守卫 |
| `./gradlew contextCheck` | 校验包装 Context 前判断了 `instanceof Activity`，Activity 自身用 `applyOverrideConfiguration` |
| `./gradlew layoutCheck` | 扫描所有布局里的自定义 View（19 个布局 / 47 处引用 / 17 个类），用**字节码解析**确认每个类都非抽象且具备 `public (Context, AttributeSet)` 构造函数 |

这几个检查都是**真实踩坑之后补的**，每一个都用「故意改坏 → 检查失败」验证过确实有效：

- `layoutCheck`：XML 里写了抽象类（`NavigationBarView`）→ 运行时 `InflateException` 闪退；
- `guardCheck`：底栏选中与页面切换互相驱动 → `StackOverflowError`；
- `contextCheck`：包装 Context 把 Activity 变成普通 ContextWrapper → Fragment `getActivity()` 为 null → NPE；
- `pathNormalizerTest`：写测试时当场抓出两个实现 bug（幂等性被破坏、显示路径拼错）；
- `multipartTest`：抓出三个实现 bug（正文被当成头部吞掉、内容重复发送、正文多出 2 字节 CRLF）；
- `protocolTest`：抓出 `Expect: 100-continue` 未回应 —— 见下。

**`Expect: 100-continue` 这个坑值得单独说**：浏览器上传超过约 1KB 的请求体时，
会先发 `Expect: 100-continue` 并**等服务器回应才肯发 body**。最初的 `HttpServer`
从不回应它，于是服务器在等 body、浏览器在等 100，表现成「手机上点了接收之后
就卡住没反应」。LocalSend 客户端是自研的、不发这个头，所以局域网传输一直是好的，
**只有真实浏览器才会踩到** —— 这也是为什么在补上这条测试之前，
所有测试都是绿的而实际功能不可用。

对应的测试刻意**先断言收到 100、之后才发 body**，所以它能真正抓住这个 bug：
把回应代码改坏，测试立刻以 `SocketTimeoutException` 失败。

`multipartTest` 值得单独说，因为它是**最难写对的一段**：流式解析器必须在「边界被切成两半」
「正文里出现 `--boundaryX` 这种假边界」「最后一个 part 的正文紧跟着收尾边界」这些情况下
都不出错，而真机上根本没法稳定复现。踩过的三个坑：

1. 用一个游标同时表示「已扫描到哪」和「已发出到哪」，压缩缓冲区时游标归零 → **整段正文被当成头部吞掉**；
2. 发出安全正文后再次按正文起点发送 → **每个文件内容都翻倍**；
3. 正文末尾的 CRLF 提前发出去，之后才发现真边界 → **文件末尾多出 2 字节**。

最终定下的规矩：发送时永远压住 `pattern.length + 2` 字节，保证「分隔符 + 它前面的 CRLF」
一定还在缓冲里；`emitted`（发到哪）和 `searchFrom`（搜到哪）各管一件事，绝不混用。

`webPageTest` 盯的是安全边界：下载接口只接受纯文件名，`../secret`、`a/b`、`/etc/passwd`
一律拒绝——放行任何一个，电脑浏览器就能顺着 `?name=../../databases/xxx` 把 App 私有目录读走。

`layoutCheck` 存在的原因：把**抽象类**写进 XML（例如 Material 的 `NavigationBarView`）编译期不会报错、
lint 也不报，但运行时 `setContentView()` 会直接抛 `NoSuchMethodException: <init>(Context, AttributeSet)` 闪退。
这个检查专门兜住这类问题，并已用「故意写回抽象类 → 检查失败」验证过确实有效。

`guardCheck` 存在的原因：底栏选中项与页面切换互相驱动时，极易写出
`showTab() → syncNavSelection() → setSelectedItemId() → 回调 → showTab()` 这种死循环，
最终 `StackOverflowError`（**已真实发生过一次**）。这类 bug 编译期、lint、单元测试都发现不了，
只能靠静态检查兜住。同样已用「故意去掉守卫 → 检查失败」验证过有效。

### 2.4 已验证的构建环境

| 组件 | 版本 |
|---|---|
| Gradle | 9.5.1 |
| Android Gradle Plugin | 9.2.1 |
| JDK | 17 (Temurin) |
| compileSdk / targetSdk | 36 |
| minSdk | 21 |

### 2.5 提交代码：本机 `git push` 不通时怎么办

仓库：<https://github.com/Alan-qwq/FastTransfer>（当前 **private**）

**这台机器上 `git push` 是走不通的**，原因不在 git：

```
$ git ls-remote origin
fatal: unable to access 'https://github.com/Alan-qwq/FastTransfer.git/':
       Recv failure: Connection was reset
```

`github.com:443` 的 **TCP 能连上，但 TLS 会话会被重置**（`Invoke-WebRequest https://github.com` 报
「连接被意外关闭」）。而 **`api.github.com` 完全正常** —— 仓库本身就是用 API 建出来的。
这大概也是全局 git 配置里被塞了 `http.sslverify=false` 的原因，但那个设置救不了连接重置，
反而会关掉证书校验（能改掉就改掉：`git config --global --unset http.sslverify`）。

绕过办法是**走 API 推送**，不碰 `github.com` 这个主机：

```bash
node tools/push-via-api.mjs Alan-qwq/FastTransfer main .
```

它做的事：读 `git credential fill` 拿凭据 → 对 `HEAD` 里的每个文件用
`git cat-file blob <sha>` 取出**对象库里的原始字节**（不是工作区文件，这样
`core.autocrlf=true` 的行尾归一化结果能原样保留）→ 建 blob → 自底向上拼 tree →
建 commit → 更新 `refs/heads/main` → 最后逐个比对 `path:sha` 断言远端与本地完全一致。

两个坑记录一下：

1. **空仓库不能建 blob**：`POST /git/blobs` 会返回 `409 Git Repository is empty.`。
   所以必须先用 Contents API 随便提交一个文件把仓库「激活」，
   再把 ref 强制指向我们自己的根提交 —— 结果是干净的单条历史，没有多余提交。
2. **PowerShell 发 JSON 会丢中文**：`Invoke-WebRequest -Body $jsonString` 不是按 UTF-8 编码的，
   仓库描述会变成 `???????:???????`。要么显式传 `[Text.Encoding]::UTF8.GetBytes($json)`
   并带 `charset=utf-8`，要么干脆用 Node 发（`tools/push-via-api.mjs` 就是后者）。

---

## 3. 使用说明

1. **两台设备连同一个 WiFi / 热点**（不要用开启了「AP 隔离」的公共 WiFi）。
2. 双方都打开「极速互传」。首页顶部会显示本机名称与地址，眼睛图标控制**是否可被发现**。
3. 在「发送」页：
   - 切到「文字」→ 输入内容 → 点「发送」；或切到「文件」→ 选择文件 → 点「发送」。
   - 附近设备会列在下方，点某一台或点它的发送按钮即可。
   - 只有一台设备时点发送会直接发过去；多台设备会弹窗让你选。
   - 点「发送给全部在线设备」可以群发（逐个发送）。
4. 接收方会弹出确认框，显示发送方、文件数量与总大小；点「接受」开始传输。
5. 传输过程中会显示整体进度、当前文件、实时速率与剩余时间，可随时取消。
6. 收到的文件保存在：**系统「下载」目录 / FastTransfer**（Android 10+ 走 MediaStore，无需存储权限）。

### 找不到设备怎么办？

1. 确认两台设备在同一网段（对比首页显示的 IP 前三位）。
2. 点发送页右上角的刷新按钮，会做一次同网段扫描。
3. 仍然找不到 → 点「手动连接」，输入对方首页显示的 `IP:端口`。
4. 检查路由器是否开启了「AP 隔离 / 客户端隔离」，开启时设备之间无法互相访问。

---

## 4. 屏幕与系统适配

### 4.1 Material 3 设计

UI 采用 **Material 3 (Material You)** 设计体系，使用 `Theme.Material3.DayNight.NoActionBar`：

- **完整 M3 颜色角色**：`colorPrimary / Secondary / Tertiary / Error` 及其 `Container`、`On*` 变体，
  以及 5 级 surface 层级（`surfaceContainerLowest → Highest`）、`outline / outlineVariant`。
  浅色与深色各一套，定义在 `values/colors_m3.xml` 与 `values-night/colors_m3.xml`。
- **M3 形状 token**：`shapeAppearanceCornerExtraSmall / Small / Medium / Large / ExtraLarge`，
  分别 4 / 8 / 12 / 16 / 28dp，与 M3 规范一致。
- **M3 文本角色**：`Text.HeadlineSmall / TitleLarge / TitleMedium / TitleSmall / BodyLarge / BodyMedium /
  BodySmall / LabelLarge / LabelMedium`，全部继承 `TextAppearance.Material3.*`。
- **M3 组件**：
  | 组件 | 用途 |
  |---|---|
  | `MaterialToolbar`（TopAppBar） | 标题 + 可见性/设置动作，`liftOnScroll` 滚动变色 |
  | `NavigationBarView` | 底部导航；**宽屏（sw600dp）自动改为 `NavigationRailView`** |
  | `MaterialButtonToggleGroup` + OutlinedButton | 文字/文件**分段按钮** |
  | `TextInputLayout`（OutlinedBox） | 文字输入、设备名、PIN、端口、手动 IP，带 helper/counter/error |
  | `MaterialSwitch`（`materialswitch`） | 可见性、PIN、自动接受、屏幕常亮 |
  | `MaterialCardView`（filled / outlined） | 设备、设置分组、本机信息、历史条目 |
  | `LinearProgressIndicator` | 传输进度（M3 进度条） |
  | `MaterialDivider` / `MaterialAlertDialogBuilder` | 分隔线与对话框 |
- 图标全部使用 M3 outlined / filled 双态：导航项在选中时切换为 filled（`ic_nav_*` selector）。
- **没有使用动态取色（Monet）**：动态取色需要 API 31+，为保证 Android 5.0 起视觉一致，
  统一使用上面这套固定品牌色板。

### 4.2 屏幕与密度

- **全尺寸自适应**：所有尺寸都用 `dp` / `sp`，点击区不小于 44–48dp；布局按最小宽度断点提供不同资源。
  - 默认（手机竖屏，单列 + 底部导航栏）
  - `layout-sw600dp`：**平板 / 词典笔等宽屏**，改为 **NavigationRail + 单栏内容**
  - `values-sw200dp`：**手表等超小屏**，压缩字号、间距与控件高度
  - `values-night`：深色配色（M3 dark color roles）
- **全 DPI**：`mipmap-mdpi / hdpi / xhdpi / xxhdpi / xxxhdpi` 各密度启动图标齐全；
  28 以下用 PNG，26+ 额外提供自适应图标（`mipmap-anydpi-v26`）。
- **老系统**：`minSdk 21`，不使用任何需要更高版本的 API 而不加判断；
  Android 10 以下自动回退到文件路径写入（`requestLegacyExternalStorage`）。
- **声明为兼容无触摸屏 / 无 WiFi 设备**（`uses-feature required=false`），便于在词典笔、手表上安装。
- 中文 / 英文双语资源（默认英文，`values-zh` 中文）。

---

## 5. 协议兼容性（LocalSend v2.2）

实现严格对齐官方 [localsend/protocol](https://github.com/localsend/protocol) 与官方实现
`packages/core/src`，因此**可以与官方 LocalSend 及其他第三方客户端互通**。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/localsend/v2/register` | 设备注册，返回本机信息 |
| GET | `/api/localsend/v2/info` | 查询设备信息 |
| POST | `/api/localsend/v2/prepare-upload` | 发起传输（`?pin=` 校验），返回 `{sessionId, files:{fileId:token}}` |
| POST | `/api/localsend/v2/upload?sessionId&fileId&token` | 上传单个文件（原始字节流） |
| POST | `/api/localsend/v2/cancel?sessionId` | 取消会话（含 pending 阶段的无 sessionId 取消） |

- 发现：UDP 组播 `224.0.0.167:53317`，TTL=1，三连发通报（100ms / 500ms / 2000ms），
  收到通报后回 `register` 完成双向确认；自身消息按 `fingerprint` 过滤。
- 状态码语义与官方一致：缺 PIN → 401、PIN 错 3 次 → 429、会话冲突 → 409、
  没有可传文件 → 204、被拒绝 → 403。
- 文字消息沿用官方约定：作为 `text/plain` 的「文件」发送，接收端识别为文字消息直接展示。
- 本实现为 **HTTP 明文模式**（与 LocalSend 默认一致），未实现 HTTPS / 客户端证书指纹校验。

### 自测

```bash
./gradlew protocolTest
```

直接编译并运行真实的协议层实现（`HttpServer` / `HttpUtil` / DTO / JSON），覆盖：

- query 解析与百分号解码（含非法编码、无值参数）
- 所有 DTO 的 camelCase JSON 往返、未知 deviceType 回落 desktop
- HTTP 服务端绑定、register / info / prepare-upload / upload / cancel / 404
- `Content-Length` 与 **chunked** 两种请求体的字节一致性（SHA-256 比对）
- 上传进度回调（触发次数与最终字节数）
- 带特殊字符的 query 参数经客户端编码后由服务端正确还原

---

## 6. 权限说明

| 权限 | 用途 | 备注 |
|---|---|---|
| `INTERNET` | 局域网 HTTP 收发 | 必需 |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 判断网络状态 | 必需 |
| `CHANGE_WIFI_MULTICAST_STATE` | 接收 UDP 组播以发现设备 | 必需 |
| `CAMERA` | 扫码连接 | 可选；无摄像头设备照常安装（`uses-feature required="false"`） |
| `READ_EXTERNAL_STORAGE` | 列出目录 / 读取待发送文件 | Android 6–12，`maxSdkVersion=32` |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` | 同上的 Android 13+ 版 | Android 13+ |
| `MANAGE_EXTERNAL_STORAGE` | **所有文件访问权限**：Android 11+ 分区存储会挡住目录列举，开启后内置浏览器才能遍历目录 | 需在系统设置里手动开启；上架 Google Play 需单独申请（或移除本权限改用 SAF） |
| `WRITE_EXTERNAL_STORAGE` | Android 10 以下写入公共下载目录 | `maxSdkVersion=28` |
| `FOREGROUND_SERVICE(_DATA_SYNC)` | 后台保持可接收 | Android 14+ 需声明类型 |
| `POST_NOTIFICATIONS` | 传输状态通知 | Android 13+ 可选 |

### 关于「要不要存储权限」

需要分情况，**不是一律不需要**：

| 场景 | 是否需要权限 |
|---|---|
| **选文件**（`ACTION_OPEN_DOCUMENT` / SAF 系统选择器） | **不需要**。系统选择器把选中项授权给本应用，与是否持有存储权限无关 |
| **接收文件落盘**（Android 10+ 写 MediaStore Downloads） | **不需要**。写入自己创建的 MediaStore 条目即可 |
| **用 `File` API 遍历目录**（内置浏览器） | **需要**。Android 6–12 要 `READ_EXTERNAL_STORAGE`，Android 13+ 要 `READ_MEDIA_*`；Android 11+ 分区存储还会进一步挡住目录列举，此时需要「所有文件访问权限」 |

### 内置文件浏览器的权限升级链路

打开「选择文件 → 浏览文件」时按下面的顺序自动升级，每一步都会给出明确提示与按钮：

1. **基础读取权限** → 弹系统权限框（Android 13+ 是三个媒体权限）。
2. **拿到了权限、目录仍是空的**（Android 11+ 分区存储挡住了）→ 提示并给出「去设置开启」按钮，
   跳到系统的「所有文件访问权限」页；用户授权后返回应用会**自动重开刚才那个目录**（`onResume` 里判断）。
3. **都不给** → 内置浏览器不可用，改用「选择文件」走系统 SAF 选择器，那条路不需要任何权限。

> `MANAGE_EXTERNAL_STORAGE` 在 Google Play 上属于需要单独申请的高敏感权限（通常只批给文件管理器类应用）。
> 本项目按**侧载用途**声明；若要上架 Play，请移除该权限并只用 SAF。

---

## 7. 安全提示

- 传输内容是**明文 HTTP**，同一局域网内的其他人理论上可以嗅探。**请只在可信网络使用。**
- 可以在「设置」里开启 **PIN 码校验**：开启后发送方必须输入相同 PIN 才能发送，连续 3 次错误会临时拒绝。
- 也可以开启「自动接受」跳过确认框 —— 请仅在完全可信的网络下使用。
- 应用不采集、不上传任何数据，不申请定位权限。

---

## 8. 项目结构

```
app/src/main/java/com/alan/fasttransfer/
├── FastTransferApp.java            应用入口（日志、深色模式、引擎初始化）
├── core/
│   ├── LocalsendProtocol.java      协议常量（组播组、端口、路径、版本 2.2）
│   ├── AppSettings.java            设置持久化
│   ├── TransferEngine.java         引擎门面：发现 + 收发 + 历史
│   ├── SendManager.java            发送侧（prepare-upload + 逐文件上传）
│   ├── ReceiveManager.java         接收侧（HTTP 服务端 + 会话/令牌/PIN）
│   ├── TransferService.java        前台服务（后台保持可接收）
│   ├── dto/                        LocalSend DTO（Gson）
│   ├── net/
│   │   ├── HttpServer.java         自研极简 HTTP/1.1 服务端（含 chunked）
│   │   ├── HttpUtil.java           基于 HttpURLConnection 的客户端（含上传进度）
│   │   ├── MulticastDiscovery.java UDP 组播发现
│   │   └── NetworkUtils.java       本机 IP / 地址解析
│   ├── transfer/                   Peer / TransferSession / TransferFile / HistoryEntry
│   └── util/                       文件落盘、MIME、格式化、JSON、文件名等工具
└── ui/
    ├── MainActivity.java           主界面（M3 TopAppBar + ViewPager2 + NavigationBar/Rail）
    ├── SendFragment.java           发送页（本机信息卡 + 分段按钮 + 设备列表）
    ├── ReceiveFragment.java        接收页（可见性、本机地址、保存位置、提示）
    ├── HistoryFragment.java        记录页
    ├── SettingsFragment.java       设置页
    ├── FileBrowserActivity.java    内置文件浏览器（无 DocumentsUI 时兜底）
    ├── DeviceAdapter / HistoryAdapter / SelectedFileAdapter
    ├── TransferProgressDialog.java 传输进度
    └── IncomingRequestDialog.java  接收确认

app/src/main/res/
├── values/            colors_m3(颜色角色) themes(M3 主题) styles_m3(M3 组件/文本样式) dimens strings
├── values-night/      colors_m3（深色颜色角色）
├── values-zh/         中文字符串
├── values-sw200dp/    手表超小屏尺寸
├── values-sw600dp/    平板宽屏尺寸
├── layout/            手机竖屏布局
├── layout-sw600dp/    宽屏布局（NavigationRail）
├── drawable/          M3 outlined/filled 图标、状态与背景 drawable
└── mipmap-*/          各密度启动图标 + 26+ 自适应图标

tools/
├── IconGen.cs                      启动图标生成器（C#，编译期外单独运行）
├── protocoltest/                   纯 JVM 协议自测（./gradlew protocolTest）
├── preview.js                      静态预览服务器（本地看官网，不参与部署）
└── shots.mjs                       移动端适配截图验证（Edge DevTools Protocol）

website/                            官网源码（不在本仓库，单独部署，见 §11）
├── index.html                      首页：模板首屏 + 追加的官网正文
├── 404.html                        404 页（自包含，刻意不引 three.js）
├── style.css                       模板原版样式，逐字节未改
├── script.js                       模板脚本，只删掉配色切换 / 调色面板
├── site.css                        追加样式（含手机竖屏适配），不覆盖模板规则
├── site.js                         追加脚本（滚动淡出）
├── three.min.js                    three.js 0.160.0（本地化，不依赖 CDN）
└── _headers                        Pages 安全响应头 / 缓存策略

dist/
└── FastTransfer.apk                release 产物副本（**不在部署目录里**，官网不提供下载）
```

---

## 9. 已知限制

- 内置文件浏览器依赖文件系统读取权限：Android 9 及以下可用；Android 10 因
  `requestLegacyExternalStorage` 也可用；**Android 11+ 受分区存储限制，目录列举会被系统挡住**，
  这种情况请用「选择文件」走系统 SAF 选择器（系统选择器不受此限制）。
- 发送方不主动弹出 PIN 输入框：若对方开启了 PIN 且是手动输入 IP 连接的，发送会失败并提示
  「对方需要 PIN 码」。**扫码连接不受影响** —— 接收端二维码里带着 PIN，扫到后会自动带上。
- 未实现 HTTPS 模式与「下载 API」（`download: false`），因此本应用不支持作为
  LocalSend 的「分享下载链接」服务端。
- 组播在某些路由器（AP 隔离）或部分定制 ROM 上会被屏蔽，此时请用手动 IP 或扫码连接。
- 超大文件（> 2GB）未做专门优化，进度与剩余时间仍可用，但未做分片。
- 扫码依赖相机：没有摄像头的词典笔/手表仍可安装（`uses-feature required="false"`），
  但只能用「手动连接」或让对方扫你的码。

## 10. 已修复问题

| 现象 | 根因 | 修复 |
|---|---|---|
| 打开即闪退（`InflateException` / `NoSuchMethodException: NavigationBarView.<init>(Context, AttributeSet)`） | 布局里直接写了抽象类 `com.google.android.material.navigation.NavigationBarView` | 改为具体子类 `BottomNavigationView`（宽屏布局用 `NavigationRailView`），并新增 `layoutCheck` 任务防止复发 |
| 切换页面时闪退（`StackOverflowError`） | 换成 Fragment 切换后，`showTab()` 里调 `syncNavSelection()` 设置底栏选中项，又触发底栏回调回到 `showTab()` —— 无限递归 | `syncNavSelection()` 里用 `syncingNavSelection` 布尔守卫包住 `setSelectedItemId()`，重入时直接 return；`showTab()` 开头也检查该守卫。新增 `guardCheck` 任务防止复发 |
| 发送完成后进度框仍显示「正在发送」 | `TransferProgressDialog.update()` 每次都**无条件**先把标题画成「正在发送」，再判断是否完成；`finished` 守卫在覆盖之后才生效，于是完成后的任何一次更新都会把标题刷回去 | 把「会话状态 → 界面呈现」抽成纯函数 `TransferSession.renderState()`，对话框改为**先判终态**：一旦进入终态就常驻锁定，不再渲染进行中。新增 `sessionRenderTest` |
| 修改显示大小时闪退（`AppSettings.getAlias()` NPE） | 两重原因：① `attachBaseContext` 里用 `createConfigurationContext()` 返回了 ContextWrapper（**不是 Activity**），Fragment `getActivity()` 变 null；② Fragment 把 `settings`/`engine` **缓存在 `onCreate()` 取到的字段里**，而「改显示大小 → Activity 本地重建」时 Fragment 会由 FragmentManager 从已保存状态直接恢复到 `onViewCreated()`，缓存字段还是 null | ① Activity 改用 `applyOverrideConfiguration()` 改自身配置，`applyUiScale()` 加 `instanceof Activity` 判断；② `BaseTabFragment` 不再缓存字段，改为**现取访问器**，并把 `onViewCreated()` 声明为 `final` 统一先做就绪判断，子类只能实现 `onBindViews()` —— 忘记判空在结构上不可能发生；未就绪时 `onResume()` 会自动重试绑定 |
| 顶部被状态栏挡住 | 状态栏透明后没有做 inset 处理 | 三个布局根节点加 `fitsSystemWindows="true"`；状态栏统一透明，浅色图标由 `values-v23` 提供、深色由 `values-night` 提供 |
| 右上角按钮冗余 | 顶栏「设置」图标与底部导航「设置」页重复 | 移除顶栏设置图标；「可见性」开关只保留顶栏一个入口（接收页的开关保留，因为它同时展示状态说明） |
| 底栏遮挡内容 / 底栏自己被系统导航栏切掉 | 根布局写 `fitsSystemWindows="true"` 让根整体内缩，但底部导航栏（`layout_gravity="bottom"`）和侧边 NavigationRail 的**父容器高度没跟着缩**，控件比可见区域更高 | 根布局去掉 `fitsSystemWindows`，改由 `InsetsHelper` **逐个控件**加 inset：顶栏吃状态栏、底栏/侧边栏吃手势条。内容区因此自动被正确顶上去 |
| 内置文件选择器只显示文件夹、看不到文件 | 两件事叠加：① Android 11+ 分区存储下 `File.listFiles()` 对 `/sdcard` 子项返回 `null`；② 我改用 MediaStore 后**没有申请存储权限**（查询别的应用的文件是需要的） | 见下方「文件选择器的取舍」 |

### 文件选择器的取舍（踩过的坑，记录一下）

这个选择器前后改了三版，最终**回到最初的文件系统实现**：

| 版本 | 做法 | 结果 |
|---|---|---|
| v1（最初） | `File` API 直接列目录 | Android 9/10 正常；**Android 11+ 只看到文件夹看不到文件**（分区存储） |
| v2 | 改成 MediaStore 分类列表 + SAF 浏览文件夹 | 分类列表**没申请权限**所以是空的；SAF 每次要用户选文件夹 |
| v3 | 自行构造 tree Uri，免去选文件夹 | **行不通**：自己拼的 SAF tree URI 没有对应授权，系统直接拒绝；又因为我 `catch` 掉了异常，表现为「目录打不开、又跳回系统授权界面」 |
| **最终** | **回到 v1**（`File` API） | Android 10 及以下可用；Android 11+ 受限时用系统 SAF 选择器 |

教训：SAF 的 tree URI **必须**来自一次真实的 `ACTION_OPEN_DOCUMENT_TREE` 授权，不能自己拼。


---

## 11. 官网

线上地址：**<https://fasttransfer.alanqwq.top>**（备用：<https://fasttransfer.pages.dev>）

> 📌 本节描述的官网源码**不在本仓库内**（本仓库只收 APK 的源码，`website/` 已被
> `.gitignore` 排除），它单独部署。这里保留完整记录作为部署文档。

### 11.1 和模板的关系

首屏的液态渐变、跟随鼠标的圆环光标都来自 CodePen 模板
*interactive-liquid-gradient-using-three-js*。原则是**套模板而不是重做**：

- `style.css`、`three.min.js` **逐字节保持原样**（`style.css` 有 SHA256 校验）；
- `script.js` 只做减法：删掉配色 2~5、`setColorScheme()`、调色面板 / 导出按钮的全部代码，
  换成无参数的 `applyColors()`，启动时套用模板的配色 1（橙 `#F15A22` + 深蓝 `#0a0e27`）；
- 官网正文、手机适配等**全部追加**在 `site.css` / `site.js` 里，不改模板文件。

### 11.2 手机竖屏适配

模板是个纯桌面单屏 demo，**一个 `@media` 都没有**，所以竖屏适配整段都在 `site.css` 里补。
踩到的三个真问题：

| 现象 | 根因 | 修复 |
|---|---|---|
| 手机左上角有个白色圆圈 | 模板的圆环光标靠 `mousemove` 定位，触屏上没有鼠标事件，`position:fixed` 又没写 `top/left`，于是停在文档静态位置（左上角） | `@media (hover:none),(pointer:coarse)` 下 `display:none`，并把 `body{cursor:none}` 还原成 `auto` |
| 大标题被折成「极速互 / 传」 | 模板用 `left:50% + translateX(-50%)` 居中。fixed 元素只给 `left` 不给 `right` 时，**可用宽度只剩视口的一半**（390px 屏上只有 195px，而标题要 234px），而标题的 `white-space:nowrap` 又被后面那句 `text-wrap:auto` 覆盖（`text-wrap` 是 `text-wrap-mode` 的简写，写在后面会重置换行模式） | 改成 `left:0; right:0; transform:translateY(-50%)` 整宽居中，并补 `white-space:nowrap` + `text-wrap:nowrap` |
| 地址栏挡住正文；键盘弹出后布局跳动 | `100vh` 在手机上是「地址栏收起」的大视口高度 | 首屏预留改用 `--first-screen: 100svh`（`@supports` 兜底回 `100vh`），`.heading` 的 `top` 同步用 `50svh` |

> ⚠ **大标题那条修复必须放在全局，绝不能塞进 `@media` 里。**
> 第一版我把它放进了 `@media (max-width:700px), (pointer:coarse)`，用户手机上仍然折行。
> 只要媒体查询因为任何原因没命中（桌面模式、奇怪的 WebView、旧浏览器不认 `pointer:coarse`），
> bug 就会原样复现。整宽居中在任何浏览器、任何宽度下都成立，而且对桌面是**零视觉差异**
> —— 模板本来就是 `text-align:center`，只是盒子从「半屏收缩包裹」变成「整屏」。
> 测试脚本里现在有一条 `7-narrow-finepointer` 用例专门守这个：窄屏 + `pointer:fine`。

顺带补的：`env(safe-area-inset-*)`（iPhone 手势条 / 刘海）、`-webkit-backdrop-filter`
（iOS Safari 不加前缀毛玻璃完全不生效）、`text-size-adjust`、`color-scheme: dark`、
`:active` 点击反馈（触屏没有 hover）、窄屏首屏加「向下滑动」提示。

### 11.2.1 怎么验证的（以及验证本身踩的坑）

`node tools/shots.mjs <url> <outDir>` —— 无头 Edge + DevTools Protocol，
在**真实移动端模拟**下截图并采集计算样式。必须走 CDP 而不是 `--screenshot`：
后者能改窗口尺寸，但 `(pointer:coarse)` 不命中、`svh` 不生效，等于没测。

覆盖 8 个视口：1440×900 / 390×844 / 320×568 / 280×653 / 768×1024 / 844×390，
外加 390×844 与 360×740 两个「窄屏但 `pointer:fine`」的回归用例。

判定条件：**横向溢出必须为 0**、标题 `getClientRects().length` 必须为 1（直接数行盒，
比量宽度可靠）、触屏下光标必须隐藏、桌面下光标必须还在。

验证脚本本身踩过两个坑，都已加防护：

1. **假绿**：预览服务器挂了，Edge 渲染成自己的 `ERR_CONNECTION_REFUSED` 错误页，
   而那一页也有 `h1/p/ul`，各项测量返回一堆 `null`，看起来像「跑完了」。
   更坑的是错误页里的 `div.interstitial-wrapper` 宽 700px，会把横向溢出算成 140px，
   害我以为是自己引入了布局 bug。→ 现在探测不到 `.heading` 就**直接抛错**。
2. **DPR**：无头 Edge 只有 SwiftShader 软件渲染，把 `deviceScaleFactor` 设成 3
   等于每帧多画 9 倍像素，直接把截图卡死超时。→ 统一用 DPR=1（不影响媒体查询和布局）。

### 11.3 部署

```bash
wrangler pages deploy website --project-name=fasttransfer --branch=main --commit-dirty=true
```

这一节是运维记录，只有 deploy 命令本身绕不开托管平台的名字；对外（官网、App）
一律只说 **`fasttransfer.alanqwq.top`**，不提平台。

`site.css` / `site.js` 改动后，记得把 `index.html` 里的 `?v=N` 一起 +1。
静态资源发的是 `max-age=0` + ETag，正常会协商缓存，但手机浏览器在
「标签页一直开着」时经常直接用旧副本 —— 大标题折行那次很可能就栽在这上面。

**未匹配的路径默认回首页（200）**，是软 404。`website/404.html` 放进去之后
（自包含，刻意不引 three.js，一秒钟就能出来），未知路径才正确返回 404。
官网已**不再提供 APK 下载**：`website/` 里没有安装包，旧链接会落到 404 页；
产物副本挪到了 `dist/FastTransfer.apk`（不在部署目录内）。

自定义域 `fasttransfer.alanqwq.top` 需要一条 **CNAME `fasttransfer` → `fasttransfer.pages.dev`（已代理）**。

### 11.3.1 发安装包（GitHub Releases）

安装包走 **GitHub Releases**，官网上不放（见上一节）。已发布：

| 版本 | tag | 安装包 |
|---|---|---|
| 1.0.0 | `v1.0.0` | <https://github.com/Alan-qwq/FastTransfer/releases/download/v1.0.0/FastTransfer.apk> |

发布流程（这台机器 `git push` 不通，所以用 API）：

```bash
# 1. 先确保 APK 是最新的
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk dist/FastTransfer.apk

# 2. 建 Release（tag 不存在会自动创建），再上传 asset
#    注意上传走的是 uploads.github.com，不是 api.github.com
curl -X POST -H "Authorization: token $TOKEN" \
  https://api.github.com/repos/Alan-qwq/FastTransfer/releases \
  -d '{"tag_name":"v1.0.1","target_commitish":"main","name":"...","body":"..."}'
curl -X POST -H "Authorization: token $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @dist/FastTransfer.apk \
  "https://uploads.github.com/repos/Alan-qwq/FastTransfer/releases/<id>/assets?name=FastTransfer.apk"
```

几个要点：

- **tag 名跟 `versionName` 对齐**（现在都是 `1.0.0` / `v1.0.0`）。改版本时 `app/build.gradle.kts`
  里的 `versionCode` 和 `versionName` 要一起改。
- **安装包是 debug 签名**（`signingConfig = signingConfigs.getByName("debug")`）。
  能装能用，但**不能上架应用商店**；换电脑导致 debug keystore 重新生成后，
  用户必须先卸载旧版才能装新版。要正式发布得先生成正式 keystore。
- **Gitee 的同步不包含 Release 附件**（只同步分支 / 标签 / 提交），
  所以 Gitee 上只有 tag，没有安装包。要在 Gitee 也放一份得手动传。
- ⚠️ **这台机器下载 GitHub Release 很慢**：实测约 **40 KB/s**（1 MB 要 26 秒，
  5.77 MB 的包要 2 分半），而 Gitee 有 **237 KB/s**。给手机装的时候心里有数。

### 11.4 源码仓库：GitHub 为主，Gitee 为镜像

| 平台 | 地址 | 定位 |
|---|---|---|
| **GitHub** | <https://github.com/Alan-qwq/FastTransfer> | **主仓库**。issues / PR / Releases 都走这里 |
| Gitee | <https://gitee.com/alanqwq/FastTransfer> | 国内镜像，方便访问 |

两边都是公开的，内容一致（Gitee 那边导入时是同一个 commit `8a4b0a67`）。
官网上首屏放了两个按钮，各配一个品牌图标：

```html
<a class="site-btn site-btn-ghost" href="https://github.com/Alan-qwq/FastTransfer" rel="noopener">
  <svg class="site-gh-icon" viewBox="0 0 16 16">…octocat…</svg> GitHub
</a>
<a class="site-btn site-btn-ghost" href="https://gitee.com/alanqwq/FastTransfer" rel="noopener">
  <svg class="site-gh-icon" viewBox="19.9353 19.9353 49.8383 49.8383">…G…</svg> Gitee
</a>
```

图标只取了品牌标志的单色部分（Gitee 取的是红圆底里那个「G」），这样能直接
`fill: currentColor` / `app:tint` 跟着主题走 —— 原版「红圆 + 白 G」在浅色和深色主题下都不合适。

### 11.4.1 同时放 GitHub 和 Gitee 要注意什么

结论：**没问题**，GPL-3.0 不限制托管位置，同一份代码放几个平台都行。但有几点要守：

- **必须有一个"主"**。两边都能直接 push 的话很容易分叉。这里定的是 **GitHub 为主、
  Gitee 为镜像**，issue / PR 只在 GitHub 收；否则同一个改动可能在两边各开一个 PR。
- **别配"双向镜像"**。Gitee 官方文档明确警告双向镜像有代码丢失风险（要求两边提交间隔
  大于 30 分钟）。单向（GitHub → Gitee）才是安全的。
- **Gitee 是境内平台，有内容审核**。理论上存在被限制的可能，有 GitHub 作为主仓库正好是保险。
- **自动同步**：Gitee 自带的「仓库镜像管理 → Pull 方向」现在是**申请制**，开通范围限
  **GVP 或推荐项目**（[官方申请页](https://gitee.com/gitee-community/mirror-repository)），
  个人小项目基本批不下来。想自动同步更现实的做法是 **GitHub Actions 反向推送**到 Gitee。
- **Gitee 仓库的描述得手动维护**：它不会跟着 GitHub 的 description 走，导入时填错就会一直错着。



### 11.5 App 里的「关于」三个外链

设置页「关于」卡片底部加了三行可点的条目：

| 条目 | 显示文案 | 实际跳转 |
|---|---|---|
| 官方网站 | `fasttransfer.alanqwq.top` | `https://fasttransfer.alanqwq.top` |
| GitHub 仓库 | `github.com/Alan-qwq/FastTransfer` | `https://github.com/Alan-qwq/FastTransfer` |
| Gitee 仓库 | `gitee.com/alanqwq/FastTransfer` | `https://gitee.com/alanqwq/FastTransfer` |

几个实现上的取舍：

- **只用一份地址**。显示文案是从 URL 去掉 `https://` 前缀推导出来的
  （`SettingsFragment.bindLink()`），不是另写一条字符串 ——
  否则显示的和跳转的两处各写一份，改了一处忘了另一处就会出现「显示 A 跳到 B」。
- **跳系统浏览器，不内置 WebView**。官网只是一个静态页，为它多带一个 WebView
  既涨体积又多一整套要维护的安全面。用隐式 `Intent.ACTION_VIEW`。
- **打不开要出提示**。设备上没有任何浏览器时 `startActivity` 会抛
  `ActivityNotFoundException`，不兜住就表现成「点了没反应」，用户只会以为按钮是坏的。
  所以 `catch` 住并 toast「没有可用来打开这个链接的应用」。
- 地址写在 `strings.xml` 里并标了 `translatable="false"`，避免中英两份各存一遍。


---

## 12. 许可

本项目以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)（SPDX：`GPL-3.0`）。

```
Copyright (C) 2026 Alan-qwq

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.
```

协议实现参考了 [localsend/protocol](https://github.com/localsend/protocol)（MIT）。
第三方依赖的许可都是 Apache-2.0，与 GPL-3.0 兼容（Apache-2.0 允许被并入 GPLv3 作品）：

| 依赖 | 许可 |
|---|---|
| AndroidX（appcompat / core / recyclerview / constraintlayout） | Apache-2.0 |
| Material Components | Apache-2.0 |
| Gson | Apache-2.0 |
| ZXing core（二维码） | Apache-2.0 |
| CameraX（扫码） | Apache-2.0 |

> 若要把版权署名换成别的名字（真实姓名或另一个 ID），改上面那段 `Copyright (C) 2026 ...` 即可。

