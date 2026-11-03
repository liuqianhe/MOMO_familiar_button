# 墨墨熟知按钮 (MOMO_familiar_button)

一个 LSPosed 模块：把墨墨背单词页面底部的 **三个** 按钮
`认识 / 不确定 / 不认识` 变成 **四个**
`认识 / 熟知 / 不确定 / 不认识`。

![深色主题](docs/screenshot.png)
![浅色主题](docs/screenshot-light.png)
![墨水屏](docs/screenshot-eink.png)

> 在真机（OPPO PLZ110 / Android 17 / KernelSU + LSPosed）上针对 **墨墨 5.6.00 (versionCode 900)** 实测通过；
> 在 **海信 A5 Pro（HLTE203T / Android 10 / Magisk + LSPosed）** 上针对 **墨墨 5.6.05 (versionCode 904)** 实测通过。

---

## 效果

- 底部操作栏变成四等分：`认识`(绿) `熟知`(蓝) `不确定`(黄) `不认识`(红)
- `熟知` 的高度、圆角（`@dimen/r_default`）、按压态、字号字体全部沿用原生按钮
- 配色自适应：深色主题浅蓝描边 + 浅蓝字；浅色主题深一点的蓝；**墨水屏设备自动转黑白**（和 App 自己的黑白化保持一致）
- 点击 `熟知` 走的是 App 自己的应答链路：
  `ReviewFragment.X0(ReviewResponse.WELL_FAMILIAR.value, false)`
  与 App 顶部工具栏的「熟知」图标、长按认识弹出的「标记熟知」是同一个入口
- 原有的三个按钮、滑动、拼写、确认弹窗都不受影响

### 为什么按钮背景不能直接染色

墨墨的按钮背景是 `android:background="?rev_button_familiar"`，两套主题的 drawable 结构完全不同：

| 主题 | drawable | 结构 |
| --- | --- | --- |
| 浅色（默认） | `drawable/revision_familiar_button_bg.xml` | `<solid #82c2ab>` + 圆角，**实心、无描边** |
| 深色 | `drawable-night/revision_familiar_button_bg.xml` | `transparent` + `1.2dp stroke`，**空心描边** |

模块早期版本是「克隆原按钮背景 + `PorterDuff.SRC_IN` 整块染蓝」：空心描边时只染描边，没问题；
但浅色主题是实心填充，`SRC_IN` 会把整块不透明区域一起替换成蓝色 —— 于是变成一个
**没有文字的纯蓝色块**（文字也是蓝色，被底色吞掉）。

现在的做法是先把克隆出来的 drawable 渲染成小图采样：

- 中心透明 → 判定为空心描边，照旧克隆染色，外观和原生完全一致（深色主题）
- 中心不透明 → 判定为实心填充，丢弃采样到的填充色（它跟随主题/皮肤，可能是绿/黄），
  改用「淡蓝填充 + 蓝色描边 + 蓝色文字」自绘，圆角半径仍取原生值

### 墨水屏：自动转黑白

墨水屏设备上墨墨会自己做黑白化（不是模块干的），此时硬塞蓝色会很突兀，所以模块也做同样的判断：

```java
// 1) 系统属性（海信是 persist.sys.epd.*，其它家一般是 ro.eink.*）
persist.sys.epd.black / persist.sys.epd.contrast / persist.sys.epd.mode
ro.eink.enable / ro.eink / ro.hisense.eink / ro.epd.version / persist.sys.eink / sys.eink.enable
// 2) 墨水屏桌面
com.hmct.einklauncher（海信）/ com.onyx / com.onyx.android / com.boox.boox / com.meebook.launcher ...
```

命中任一即视为墨水屏：描边和文字直接用**原生按钮的文字色**（海信上是 `#4B4C4D`），填充透明，
外观和旁边三个按钮同一种观感，靠「熟知」两个字区分。判定结果会缓存，只在首次注入时计算一次。

圆角统一取 App 自己的 `@dimen/r_default`（4dp），不再用估算值 —— 早期版本用了 10dp 兜底，
比原生按钮明显更圆。

## 安装

1. 手机需要 root + LSPosed
2. 安装 `MoMoRev.apk`（到 Releases 下载，或自己构建）
3. 打开 LSPosed 管理器 → 模块 → 启用「墨墨熟知按钮」
4. 在模块的**作用域**里勾选「墨墨背单词」
5. 强制停止墨墨背单词，再重新打开

## 构建

无需 Gradle：

```powershell
.\build.ps1
# 产物 build\MoMoRev.apk
```

依赖 JDK 17 + Android SDK build-tools 34（脚本里默认 `C:\AndroidDev\jdk-17`、`C:\AndroidDev\sdk`，
可以用 `-JdkHome` / `-SdkRoot` 覆盖）：

```powershell
.\build.ps1 -JdkHome "C:\Paths\java_jdk\jdk17" -SdkRoot "C:\Paths\Android_Path" -Install
```

`stubs/` 里是**编译期**用的 Xposed API 桩，不会打进 dex，运行时用 LSPosed 提供的真类。
版本号只在 `app/AndroidManifest.xml` 里维护，`build.ps1` 会读出来传给 aapt2。

## 更新日志

### v1.1

- 修复浅色主题下 `熟知` 变成一个没有文字的纯蓝块（`SRC_IN` 把实心填充一起染蓝）
- 圆角改用原生 `@dimen/r_default`（4dp），和旁边三个按钮形状一致
- 墨水屏设备自动转黑白，配色取原生按钮的文字色
- 已在海信 A5 Pro（Android 10）+ 墨墨 5.6.05 实测

### v1.0

- 首个版本，在 OPPO PLZ110（Android 17）+ 墨墨 5.6.00 实测



## 免责声明

仅供个人学习与逆向技术研究使用：

- 本仓库**不包含**墨墨背单词的任何代码、资源或安装包，只包含运行时注入的模块代码
- 不修改、不重打包原 App，不破解其付费内容
- 使用本模块可能违反该 App 的用户协议，由此产生的一切后果由使用者自行承担
- 请勿用于商业用途或二次分发
