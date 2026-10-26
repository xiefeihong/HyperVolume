# HyperVolume

> 把手机音量调得更"准"、更"好用"——尤其是蓝牙耳机音量偏大、低档位没声音、相邻两档听感几乎一样时，用它来精细掌控每一格音量。

需要 **Root + LSPosed** 的 Android 系统增强模块（含配套设置 App）。它在系统底层重新划分"音量刻度"：音量条更细、按键落点更稳、低音量区间距更大，并可按 **蓝牙 / 有线耳机 / 外放** 分别配置生效模式与音量范围。

本项目是 [volume_control](https://github.com/xiefeihong/volume_control)（Java + View + 手写 libxposed 入口）的 **Kotlin 重写版**：界面换用小米澎湃 OS 设计语言的 [Miuix](https://github.com/compose-miuix-ui/miuix)（排版风格参考 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)），Hook 层改用 [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) + [KavaRef](https://github.com/HighCapable/KavaRef)，功能与算法与旧版逐字对齐。

## 能帮你解决什么

- **媒体音量档位（10~100）**：整条音量被切成多少格。格数越多，每格之间的音量差越小、调得越细，解决"相邻两档听感相同""一开就炸、再小就没声"。
- **音量键步进（10~29 段）**：把整条音量均分成你设定的段数，每按一次音量键恰好跳一格；落点稳定可预期，且不影响屏幕滑条逐档精调。
- **最小 / 最大音量**：抬高第 1 档起点（低档听不见时上调），限制最高点（保护听力、安静环境使用）。
- **三种音量控制模式**：绝对音量（AVRCP 曲线映射，**仅蓝牙**）/ 相对音量（手机端软件衰减）/ 默认（直通系统原生行为）。
- **按输出设备独立配置**：蓝牙、有线耳机、外放各自保存模式与范围；打开 App 自动定位到当前输出设备。绝对音量仅蓝牙可选（有线耳机/外放不显示该选项）。
- **映射曲线**：对数（默认）/ 线性 / 平方根三种档位→音量分布，绝对音量与相对音量的曲线各自独立保存。
- **ROM 真实曲线查看器**：以 root 读取本机音频配置（音量引擎表 → audio policy 表 → AOSP 参考曲线三级回退），按设备类别展示每档实际增益，纵轴可切 **感知响度 / dB 线性**，横轴可切 **档位 / 百分比**。

> 只影响**媒体音量**（音乐、视频、游戏等），来电、通知、闹钟等其他音量不受影响。

## 运行环境

| 项目 | 要求 |
|---|---|
| 系统 | Android 10（API 29）及以上（`minSdk 29`） |
| 目标机型 | 主要面向小米 **HyperOS / MIUI**（档位属性 `ro.config.media_vol_steps` 的读取方式取自 HyperCeiler）；旧版 volume_control 实测基准为 Redmi 12 5G（`23077RABDC`）/ HyperOS 2（Android 15） |
| 必备 | 已 Root（授予 su）+ 已安装并启用 LSPosed |
| Xposed 框架 | libxposed API **102**（`minApiVersion` / `targetApiVersion` 均为 102） |
| 模块作用域 | `android` / `system` / `com.android.bluetooth`，`staticScope=true`（无需手动勾选目标） |

未 Root 的设备无法生效：档位改写与配置下发都要经过 root 通道（应用内浏览/编辑生效配置不需要 root，`Settings.Global` 的读取不设权限）。

## 快速上手

1. 安装 App，在 LSPosed 中启用 **HyperVolume** 模块（作用域已静态声明）。
2. 打开 App，在「媒体音量档位」卡片打开 **启用档位修改** 开关。
3. 按需调整档位、按键步进、顶部设备选项卡（蓝牙/有线耳机/外放）的模式与最小/最大范围、映射曲线——右侧曲线图与下方映射表**实时预览**。
4. 点 **保存修改**，确认框会逐条列出改动，并按需询问重启什么组件（修改只存在于界面内存里，重进应用回到生效配置）：
   - 改了**档位数** → 需「重启系统框架」（屏幕短暂黑屏属正常现象）；
   - 改了**模式 / 音量范围** → 通常「重启蓝牙」即可；
   - 音量键步进即时生效。
5. 想回到原生行为：把设备选 **默认** 模式，或到 **设置 → 通用** 关闭 **启用模块**（保存后按提示重启系统框架即完全停用，参数仍保留）；要抹掉本应用写入系统的配置、开机脚本与日志，用 **设置 → 备份与恢复 → 清除数据**。

排查手段：首页顶栏右上角「刷新检测」与「运行状态」六行检测（模块是否加载、档位数是否生效、当前输出设备等）；**设置 → 故障排除 → 查看模块日志** 读取模块自写日志（支持复制/清理），同页还有「调试信息」（含 root / LSPosed / **默认档位** / 档位数来源 / 日志路径 / **系统侧落点状态** / 那唯一一份生效配置原文，共 16 行）、「刷新检测」与可选的「性能日志」开关（开着时一次保存额外记一条 `push perf` 耗时）。

## 三种模式对比

| | 绝对音量（蓝牙专属，默认推荐） | 相对音量 | 默认 |
|---|---|---|---|
| 原理 | 保持绝对音量，档位经曲线映射为 AVRCP 0~127 | 停用绝对音量，由手机端软件衰减音频信号 | 不改档位数、不加自定义曲线，仅在范围内线性重映射 |
| 适用场景 | 耳机支持绝对音量、想让低音量更细腻 | 耳机内部档位少（如 8~19 档）、低音量重复严重 | 只想微调最小/最大音量，保留 ROM 原生响度曲线 |
| 耳机按键 | 可控制手机音量并同步显示 | 只控制耳机自身音量，不同步显示 | 跟随系统原生行为 |
| 低音量表现 | 增强曲线拉开每档间距 | 完全由手机曲线决定，无耳机量化干扰 | 两端锚到 min/max，中间保留原生曲线 |

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Kotlin | 2.4.21 | 全量 Kotlin，JVM target 17 |
| AGP / Gradle | 9.3.3 / 9.8.0 | AGP 9 内置 Kotlin 支持，不再需要 `kotlin-android` 插件 |
| KSP | 2.3.12 | 生成 YukiHook 框架入口与 `META-INF/xposed/*` |
| Compose | 1.12.0 | UI 基础 |
| Miuix | 0.9.4 | `miuix-ui` / `miuix-preference` / `miuix-icons`，澎湃 OS 设计语言 |
| YukiHookAPI | 1.5.0 | `yukihook-core` + `yukihook-runtime-libxposed` + `yukihook-compiler` + `yukihook-extension-kavaref` |
| KavaRef | 1.1.1 | `kavaref-core` / `kavaref-android` / `kavaref-extension` |
| libxposed api | 102.0.0 | `compileOnly`，运行期由 LSPosed 注入 |
| compileSdk / targetSdk / minSdk | 37 / 35 / 29 | |

版本号单一来源：`gradle.properties` 的 `appVersionName` / `appVersionCode`。递增约定：每次功能代码改动超过 10 行即版本号 +0.0.1（`1.1.0 → 1.1.1`），`versionCode` 同步 +1。

## 架构

```
app/src/main/java/com/xiefeihong/hypervolume/
├── HookEntry.kt                 # @YukiHookLibXposedEntry 模块入口（configure + registerFrameworkEvents + encase）
├── core/                        # 纯逻辑层：被 App 进程与 Hook 端（system_server / 蓝牙进程）共用，禁止引用任何 Xposed API
│   ├── Prefs.kt                 #   配置常量 + 档位/键步网格吸附算法（clampMediaSteps/keyDelta/nextKeyStepUp…）
│   ├── VolumeConfig.kt          #   不可变配置值对象 + 自描述 JSON 序列化/解析（手写格式化串 + 容忍手工编辑）
│   ├── DeviceConfig.kt / Range.kt / VolumeMode.kt / OutputDevice.kt  # 单设备配置、范围、模式「枚举即策略」
│   ├── Avrcp.kt                 #   AVRCP 绝对音量映射计算 + 预览/映射表文本生成
│   ├── NativeVolumeCurve.kt     #   ROM 真实音量曲线读取（引擎表/策略表/AOSP 回退、dB 归一）
│   ├── SettingsGlobal.kt        #   Settings.Global 薄封装（配置读取通道 1）
│   └── Shell.kt / ShellResult.kt#   root shell 工具：配置下发、蓝牙/框架重启、日志读写
├── hook/                        # Hook 层（YukiHookAPI + KavaRef）
│   ├── XposedKit.kt             #   共享基础层：日志去重、反射封装、配置三级读取、Context 解析、进程标记
│   ├── AudioHooks.kt            #   system_server 侧：档位五重保险 + 音量键接管 + 模式B 软件衰减 + 自愈校正
│   └── BtHooks.kt               #   com.android.bluetooth 侧：AVRCP 绝对音量曲线替换 + 上报拦截
├── app/                         # 应用侧业务逻辑（与界面解耦，可在 JVM 单测直接断言）
│   ├── ConfigStore.kt           #   生效配置的唯一读取入口（ContentResolver 直读 Settings.Global）+ 环境事实键
│   ├── BackupEngine.kt          #   备份与恢复：JSON 存档编解码（配置值写成嵌套对象）+ 偏好写回
│   ├── StatusProbe.kt           #   运行状态检测：逐行状态文本生成
│   ├── EditorState.kt           #   编辑态快照（模型 / 生效基线 / 弹框状态）
│   ├── PreviewBuilder.kt        #   曲线预览点列 + 等宽映射表文本
│   └── SaveEngine.kt            #   保存流程：先算 SavePlan（门控/改动/重启判定），再执行 root 动作
└── ui/                          # Miuix + Compose 界面
    ├── MainActivity.kt / HyperVolumeApp.kt / HyperVolumeViewModel.kt
    ├── component/Common.kt      #   SectionCard / Chip / MonoText / PrimaryButton 等薄封装
    ├── component/CurveChart.kt  #   Canvas 曲线图（题注 + 轴标签 + 数据点）
    └── screen/Sections.kt / AppDialogs.kt  # 分区卡片流 + OverlayDialog 弹框集
```

### Hook 点位一览

**档位数（五重保险，重启手机立即生效）**

1. 拦截 `SystemProperties.getInt("ro.config.media_vol_steps")`——HyperOS 的 AudioService 初始化读该属性决定档位数；缓存未就绪时在回调内现场刷新；
2. AudioService 构造器开始前改写静态 `MAX_STREAM_VOLUME` 数组；
3. `createStreamStates` 前再改写一次（ROM 可能在构造器中途按属性重新赋值）；
4. 构造完成后、开机延迟校正（2/5/15/30/60s）、`getStreamMaxVolume` 自愈三处校验实例 `mIndexMax`，不一致立即校正；
5. App 保存时以 root 写入 post-fs-data 开机脚本，system_server 启动前直接 `resetprop` 真实属性（不依赖任何 Hook）。

对 AudioService 构造器与 `createStreamStates` 执行 `deoptimize`，防止调用点被内联后绕过属性拦截。

**音量键步进**：接管 `adjustStreamVolume`（仅媒体流、仅按键），按网格吸附重算目标级；拖动与按键以相邻变更间隔区分；拖后经 Binder 下发的场景回落到 `setStreamVolume` / `setStreamVolumeWithAttribution`。

**相对音量（模式B）**：Hook `VolumeStreamState#setStreamVolumeIndex` 按 min~max 曲线做软件衰减，并反射恢复 `mIndexMap` 原始档位，避免 `getStreamVolume` 读回二次映射导致音量卡死；同时 `avrcpSupportsAbsoluteVolume` 强制上报不支持、拦截 `postSetAvrcpAbsoluteVolumeIndex`。

**蓝牙进程侧**：Hook `AvrcpVolumeManager#systemToAvrcpVolume` / `avrcpToSystemVolume`（正/反函数保持双向一致）；默认模式收窄范围时改 `AvrcpNativeInterface.sendVolumeChanged` 抬升绝对音量以消除首档无声；模式B 在 `deviceConnected` 阻断协商。

## 配置格式与生效链路

配置以**自描述 JSON** 在三个通道间传递（只认 JSON，不保留任何历史格式的解析分支）：

```json
{
  "module": true,
  "enabled": true,
  "mediaSteps": 50,
  "keySteps": 15,
  "devices": {
    "speaker": { "mode": 2, "b": { "min": 2, "max": 48, "curve": 0 }, "d": { "min": 0, "max": 100, "curve": 0 } },
    "wired":   { "mode": 2, "b": { "min": 1, "max": 40, "curve": 1 }, "d": { "min": 0, "max": 100, "curve": 0 } },
    "bt":      { "mode": 1, "a": { "min": 0, "max": 127, "curve": 2 }, "b": { "min": 2, "max": 100, "curve": 0 }, "d": { "min": 0, "max": 100, "curve": 0 } }
  }
}
```

全链路只有一种写出形态，由同一个手写写出器产出（`org.json` 在 JVM 上按 `HashMap` 迭代、键序不稳定，故不用它写出）：

- **缩进两空格**：`Settings.Global`、配置镜像与备份里存的都是这一串，便于 `cat` / `settings get` 直接阅读；键序固定。
- **两处一致性判定口径不同**：「有无未保存修改」比**解析后的模型**（排版不参与）；写入后的**回读校验比归一后的格式化串**（
  两侧都再过一遍写出器，只比真正落盘的内容）。后者不能比模型：模式A 范围只对蓝牙持久化，外放 / 有线的该字段
  不进 JSON，系统侧没有配置时回落的默认值就带着这个不落盘的值，按模型相等去判会让「写入成功」被误报成失败。

历史存量的紧凑单行串（旧版暂存键 / 旧存档）无需迁移：`JSONObject` 的解析天然容忍空白与换行。

解析面尽量容忍手改：布尔接受 `true/false` 与 `1/0`（含字符串写法）、数字接受 `"50"`、未知键忽略、字段缺失或越界则回落默认并钳制到合法区间；顶层四个键一个都没有（不是本模块的配置）则整份拒绝。`module` 键缺失按 `true` 解析（历史存档不应被误判为停用）。全程只用双引号、不含单引号，因此嵌入 `settings put global '<v>'`（POSIX 单引号包裹）仍然安全，格式化串的换行也在单引号内原样保留。

| 通道 | 位置 | 用途 |
|---|---|---|
| **唯一一份配置** | `Settings.Global["volume_steps_hook_config"]`（格式化 JSON） | Hook 端实时读取（`registerContentObserver` 监听变更即时失效缓存）；App 端经 `ContentResolver` 直读它装载界面（读取不设权限，写入需 root） |
| 镜像兜底 | `/data/system/hypervolume_config` | 冷启动早期 SettingsProvider 未就绪时直读（内容与主通道一致；v1.1.5 起以 `printf '%s'` 写出、**无尾随换行**，与主通道逐字节一致；读入时限 8 KB，格式化串实测约 1.1 KB） |
| 开机脚本 | `/data/adb/post-fs-data.d/hypervolume.sh` | Magisk / KernelSU 最早阶段 `resetprop ro.config.media_vol_steps`；v1.1.5 起仅在 `(生效标志, 档位数)` 变化时重写（幂等） |
| 开发者选项 | `Settings.Global["bluetooth_disable_absolute_volume"]` | 模式B 时置 `1` |
| 模块日志 | `/data/system/hypervolume_sys.log`、`/data/data/com.android.bluetooth/files/hypervolume_bt.log` | App 内「查看模块日志」读取 |


应用侧不再有配置副本：`SharedPreferences("settings")` 只留 `system_default_steps`、`boot_script_active` / `boot_script_steps`（开机脚本幂等基线）等环境事实与语言/颜色等界面偏好；界面编辑只存在于内存，重进应用回到生效配置。升级安装残留的旧键 `config_json` 不再读写，导出时也会被丢弃。

`mode`：`0=默认（直通）` / `1=绝对音量`（仅蓝牙，其他设备归一为 2） / `2=相对音量`；`curve`：`0=对数` / `1=线性` / `2=平方根`；`a/b/d` 分别为绝对音量 / 相对音量 / 默认三套范围（`a` 仅蓝牙写）。

两个开关都在 JSON 里，Hook 端的生效判据只取一个合取：`engaged = module && enabled`。
`module`（设置 → 通用「启用模块」）为模块级总闸，`false` 时不重写档位数、不接管音量键、不改 AVRCP 曲线、不做软件衰减；`enabled`（主页「启用档位修改」）只管档位与范围映射。

模块元数据（`META-INF/xposed/java_init.list`、`module.prop`、`scope.list`）由 `yukihook-compiler` 依据 `HookEntry` 上的注解在编译期生成，源码目录中不手动声明。

## 保存链路与 Hook 读配置开销（v1.1.5）

v1.1.5 不改变任何对外行为，只压缩「一次保存的 root 往返数」与「Hook 热路径读配置的开销」，并加固写入面。

**实测基线（Redmi `23077RABDC` / HyperOS + KernelSU，adb-su 命令净耗时，各取多次中位）**：su 守护进程往返的
固定开销≈ 0（`su -c true` 与 `adb shell true` 相当，App 用 `ProcessBuilder` 直连不付 adb 传输），一次保存的耗时
主要在命令本身——`settings put global` ~60–80ms（走 SettingsProvider binder）、镜像写 ~15ms、开机脚本写 ~90ms、
`resetprop` ~30ms、`settings get` ~37ms。故**幂等跳过是最大杠杆**：稳态一次保存（参数与绝对音量均未变）从 ~250–300ms
降到 ~135ms。

- **保存链路 6 次 su → 2 次**：`Settings.Global` 主键 + 配置镜像 + 「停用绝对音量」开关 + 日志追加合并为一条
  `Shell.pushAll`（前置 `V=<quote(json)>` 让主键与镜像复用同一变量、避免多行 JSON 二次转义；每段成功回显
  `hv_ok:*` 落点标记、末尾显式 `exit 0`，失败位置由**缺失的标记**判定，顺带修掉「`settings put` 写空值也返回 rc=0」
  的误判；只有主键标记缺失才算写入失败）。回读仍单独一次 root `settings get`（新进程绕开 SettingsProvider 缓存）。
- **开机脚本幂等**：脚本只由 `(生效标志, 档位数)` 决定，写入前与 `boot_script_active`/`boot_script_steps` 事实键比对，
  未变即跳过重写；「清除数据」成功后移除这两键（否则清完改同样参数保存会漏写脚本）。脚本写出改逐行走 `quote()`
  （旧实现裸 `'…'` 包裹，某行含单引号即破腔——注入面），并把「写脚本」与「立即对齐 `resetprop`」并入同一次 su。
- **绝对音量幂等**：App 进程用 `ContentResolver` 读现值（读全局设置不需要 root），仅目标值不同才写入该段。
- **探测合并**：主页刷新检测的 root 可用性 + LSPosed 目录两次 su 合成一次 `Shell.rootAndLsposed()`，调试信息复用
  同一探测结果，不再重复 su。
- **落点状态可见**：`pushAll` 后未命中标记记入模块日志；调试信息新增一行「系统侧落点状态」（共 **16 行**，
  root 可用时探测全局设置 / 配置镜像两个落点，缺失项直接列出），保存成功但镜像缺失时在此可见。
- **可开关性能日志**：「设置 → 故障排除 → 性能日志」开关（`perf_log`，默认关），开着时一次保存额外发 1 次 su
  写一条 `push perf` 耗时日志（实测模式，可接受）。

**Hook 侧读配置**：`readConfig` 的 TTL 800ms 缓存 + `ContentObserver` 即时失效不变，另加两档保护——

- **负面退避**：双通道（Settings.Global + 镜像文件）都读不到时记下失败时刻，`CONFIG_MISS_BACKOFF_MS = 5s` 内快路径
  直接返回 `null`，避免系统侧无配置时音量键 / `VolumeStreamState` / AVRCP 转换这些高频热路径每次都走 binder IPC +
  文件 `stat`/`open`。代价是「系统侧无配置」状态下新写入的配置最多延迟 5s 生效（下一次退避到期即命中并注册观察器）。
- **single-flight**：缓存过期时仅首个线程走慢路径，其余沿用旧缓存（含 `null`），杜绝并发 binder 风暴。
- **镜像短读**：`readMirrorConfig` 拷贝循环按剩余长度续读、真 EOF（`read<0`）才停，用 `String(buffer, 0, n, UTF_8)`
  精确构造，不再用 0x00 填充后靠 `trim()` 兜。

## 备份与恢复

**设置 → 备份与恢复** 经 SAF 导出/导入 `.json` 存档（格式版本 `3`：配置只有 `global` 一份）：

```json
{
  "format": "HyperVolume-backup",
  "version": 3,
  "createdAt": "2026-10-22T03:00:00",
  "package": "com.xiefeihong.hypervolume",
  "prefs": [
    { "k": "system_default_steps", "t": "i", "v": 15 },
    { "k": "ui_language", "t": "s", "v": "zh" }
  ],
  "global": { "enabled": true, "mediaSteps": 50, "…": "写入系统的生效配置" }
}
```

顶层 `global`（那唯一一份配置）**写成嵌套对象**而非转义字符串，便于直接阅读与改档位；导入时归一回同一个格式化串后推送系统。`prefs` 只装环境事实与界面偏好，单字符类型标记 `t`（`s/i/l/f/b/u`）保住 `SharedPreferences` 的强类型。v1/v2 存档仍可导入，其 `prefs` 里的 `config_json` 副本被直接丢弃；解析不出对象的（空串 / 畸形）原样保留字符串，不静默丢数据。**导入需要 root**：配置只能落回系统，无 root（或推送失败）时整份中止、不改动任何现有数据。

同一区块还有**清除数据**：以 root 清除本应用写入系统的配置（`Settings.Global` 键 / 镜像文件 / 开机脚本）与模块日志，界面偏好与环境事实保留；成功后按需询问重启蓝牙与系统框架（刚退下的旧档位数要重启才回落）。

模块元数据（`META-INF/xposed/java_init.list`、`module.prop`、`scope.list`）由 `yukihook-compiler` 依据 `HookEntry` 上的注解在编译期生成，源码目录中不手动声明。

## 构建

```bash
# Debug
./gradlew :app:assembleDebug

# Release（R8 混淆 + 资源收缩）
./gradlew :app:assembleRelease

# 单元测试
./gradlew :app:testDebugUnitTest

# 一条命令跑全部并归档产物
./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease copyApk
```

`copyApk` 任务把两个变体统一到根目录 `build/apk/` 并按版本号命名：

```
build/apk/HyperVolume-v<appVersionName>-debug.apk
build/apk/HyperVolume-v<appVersionName>-release.apk          # 已签名（存在 keystore.properties）
build/apk/HyperVolume-v<appVersionName>-release-unsigned.apk  # 未签名（缺 keystore.properties）
```

> 产物输出目录必须是独立子目录：若直接复制到工程根目录，Gradle 会把根目录推断为 copyApk 输出，而根目录同时是 `app/src` 全部编译输入的祖先目录，`compileReleaseKotlin` 会因「uses this output of task without declaring an implicit dependency」直接失败。

**发布签名**：根目录放置 `keystore.properties`（含口令，与 `*.jks` / `*.keystore` 一同已在 `.gitignore` 中，不入库）即自动启用 release 签名：

```properties
storeFile=../release.keystore
storePassword=****
keyAlias=hypervolume
keyPassword=****
```

`storeFile` 相对于 `app/` 模块目录，`../release.keystore` 即仓库根目录。密钥库自行生成：

```bash
keytool -genkeypair -v -keystore release.keystore -storetype PKCS12 \
  -alias hypervolume -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 10950 \
  -storepass '<口令>' -keypass '<口令>' -dname 'CN=HyperVolume, O=<你的名>'
```

缺失该文件时 release 退化为**未签名包**，以便 CI / 他机构建不失败。

> ⚠️ **务必备份 `release.keystore` 与口令**：Xposed 模块以 APK 形式安装，一旦密钥丢失就无法用新包覆盖升级（只能卸载重装，语言/颜色等本地偏好随之丢失，写进系统的配置不受影响），发布到 GitHub Release 后也无法撤回。同一个仓库内不要混用 debug 密钥。

## 测试

JVM 单元测试覆盖与界面无关的核心算法，共 **14 个测试类 / 121 个用例**（`failures=0 errors=0`）：

| 测试类 | 覆盖内容 |
|---|---|
| `PrefsTest` | 档位/键步 clamp、`keyDelta`、网格吸附 `nextKeyStepUp/Down` 的严格单调性与边界 |
| `VolumeConfigTest` | JSON 往返序列化、格式化串键序锁定、排版无关的语义相等、非法/缺失字段回落、手工编辑（`1/0`、字符串数字）容忍、模块总闸 `engaged` 判据与缺 `module` 键按启用解析、非蓝牙设备无模式A 维度（读回归一为 `null`、默认回落值可无损往返）、**属性化往返恒等**（3 模式×3 设备全组合逐组 `fromJson(toJson())==self` + 非蓝牙脏 absolute 归一到同一典范对象） |
| `AvrcpTest` | AVRCP 0~127 映射、曲线标签、预览与映射表文本生成 |
| `EditorStateTest` | 编辑态派生（目标物理档位、未保存判定按配置语义比较）、以生效配置装载与无配置时回落默认 |
| `PreviewBuilderTest` | 曲线点列与映射表构建 |
| `SavePlanTest` | 保存计划：门控三选项、改动清单（含「启用模块」条目）、重启判定（系统框架 / 蓝牙）、回读校验只比落盘内容（不参与序列化的字段不算差异）、开机脚本幂等判据 `bootScriptShouldRewrite`（未记录保守必写、参数变化才重写） |
| `StatusLinesTest` | 运行状态各行文本生成 |
| `BackupFormatTest` | 备份存档往返与类型还原、`global` 导出为嵌套对象 / 导入回串、遗留 `config_json` 两端过滤、v1/v2 存档兼容、非法存档拒绝、新增环境事实键（boot active/steps + perf_log）强类型往返 |
| `ShellPushCommandTest` | `pushAll` 命令拼装快照（`V=` 变量复用 + `printf '%s'` + `exit 0` + `hv_ok:*` 标记顺序 / av 为 null 时省略）、开机脚本逐行 `quote`（注入面）、`PushMarks.missing` 解析、`RootProbe` 合并探测输出解析 |
| `ConfigReadPathTest` | Hook 读配置分支判定 `decideConfigReadPath`（假时钟）：缓存命中 / 负面退避跳过 / 退避到期重走慢路径 / TTL<=0 不走缓存 |
| `DebugInfoLinesTest` | 调试信息各段文本生成（共 16 行，新增「系统侧落点状态」行：全部命中 / 列出缺失 / 无 root 标注需授权） |
| `EffectiveConfigParseTest` | 生效配置原文 → 模型的脏值归一（空串 / 字面 `null` / 畸形 / 非本模块键返回 `null`，排版不参与语义） |
| `UpdateCheckerTest` | 版本比较与更新渠道解析 |
| `StringResourcesTest` | 中英双语资源键集对齐、插参一致性、`\n` 必须为转义而非物理换行 |

界面与应用侧逻辑已在模拟器（Pixel 3a / API 34）实机验收：状态检测区、开关与禁用态滑条、设备选项卡切换、**有线耳机/外放不再显示「绝对音量」选项**（仅蓝牙列出）、等宽预览摘要与映射表、曲线图双口径切换与轴标签刷新、日志/提示/保存确认弹框、保存失败 Toast、未保存提示行、使用说明；主页顶栏右上角「刷新检测」、主页副标题精简；设置页三区块同页堆叠（无顶部标签）、「启用模块」总闸位于「通用」首行、「清除数据」位于「备份与恢复」末行（其后为备份范围说明）、「故障排除」不再含恢复默认入口；蓝牙未连接时运行状态只写「蓝牙音频设备：未连接」；调试信息新增「默认档位」行且与主页档位行的默认值一致；`Settings.Global` 与配置镜像同为一份格式化 JSON（含 `module` 键），界面直接装载它——**无 root 也能读到生效配置**（`ContentResolver` 直读）；未保存的编辑只存在于内存，杀进程重进回到生效值，偏好文件 `settings.xml` 里不再出现 `config_json` 键；调试信息只剩生效配置一段原文；导出存档里配置只出现在 `global`（嵌套对象），导入需要 root、无 root 时整份中止；仅改动排版（把格式化串挤成单行）不再被误判为「有未保存修改」。

真机（Redmi 23077RABDC / HyperOS + KernelSU + LSPosed）另验收一处回归：系统侧没有生效配置时（清空数据后）首次点「保存修改」，
v1.1.2 起会提示「写入系统配置失败，请确认 su 已授权」——实际配置已落盘（`Settings.Global` 与镜像逐字节一致、开机脚本已写出），
只是回读校验按模型相等误判（非蓝牙的模式A 范围不落盘）；改回归一后的格式化串后，同一场景直接给出重启生效提示，不再误报。

> **Hook 运行时行为尚未在真机验证**：模拟器无 HyperOS 环境、无真实音频与蓝牙硬件。`AudioHooks` / `BtHooks` 的实际效果与 root 保存/重启流程需在真机启用模块后确认。

## 与旧版 volume_control 的差异

| | volume_control（Java） | HyperVolume（Kotlin） |
|---|---|---|
| 语言 / JVM | Java 21 | Kotlin 2.4.21 / JVM 17 |
| 界面 | AppCompat + Material3 + ViewBinding | Miuix 0.9.4 + Compose 1.12（HyperCeiler 风格卡片流） |
| 模块入口 | 手写 `XposedModule` + `META-INF/xposed/*` 资源 | `@YukiHookLibXposedEntry` 注解 + KSP 编译期生成 |
| 反射 | 手写 `Class.getMethod` + 缓存 | YukiHookAPI `HookChain` + KavaRef（保留方法/字段缓存与逐级回退） |
| 配置格式 | 10 字段位置式 `;` 串 | 自描述 JSON（按设备分组，字段可缺失回落默认，全链路只有一种格式化串，差异靠语义比较） |
| 保存流程 | `AlertDialog` + `Toast` + `Handler` 手工线程 | `SaveEngine` 先算 `SavePlan` 再执行 `suspend` root 动作，逻辑可单测 |
| 档位上限 | 127 | 100 |

功能语义与算法判据（门控条件、重启判定、按键网格、曲线映射）与旧版逐条对齐。

## 常见问题

- **改完没生效？** 档位数变化必须「重启系统框架」；只调范围/模式一般「重启蓝牙」即可。App 会在保存后按需提示。
- **一定要 Root 吗？** 是的，需要 Root + LSPosed；未 Root 无法下发配置与开机脚本。
- **会不会影响其他音频？** 只作用于媒体音量；随时可到 **设置 → 通用** 关闭 **启用模块**（保存后重启系统框架）回到原生行为，或在 LSPosed 中停用模块后重启手机。
- **其他机型表现不同？** 档位数与原声曲线因厂商音频配置而异，App 会自动读取本机实际配置；读不到任何 ROM 文件时曲线查看器回退 AOSP 参考曲线。

## 许可

本项目以 **GNU GPLv3** 发布（完整条款见 [LICENSE](LICENSE)），与旧版 volume_control 保持一致。

实现过程中参考了 [Miuix](https://github.com/compose-miuix-ui/miuix)、[YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)、[KavaRef](https://github.com/HighCapable/KavaRef) 与 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) 的设计与接口用法，在此致谢；使用本项目时请同时遵守上述上游项目各自的许可证。仅供学习与个人使用。
