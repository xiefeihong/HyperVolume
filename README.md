# HyperVolume

> 把手机音量调得更"准"、更"好用"——尤其是蓝牙耳机音量偏大、低档位没声音、相邻两档听感几乎一样时，用它来精细掌控每一格音量。

需要 **Root + LSPosed** 的 Android 系统增强模块（含配套设置 App）。它在系统底层重新划分"音量刻度"：音量条更细、按键落点更稳、低音量区间距更大，并可按 **蓝牙 / 有线耳机 / 外放** 分别配置生效模式与音量范围。

本项目是 [volume_control](../volume_control)（Java + View + 手写 libxposed 入口）的 **Kotlin 重写版**：界面换用小米澎湃 OS 设计语言的 [Miuix](https://github.com/Miuix-UI/Miuix)（排版风格参考 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)），Hook 层改用 [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) + [KavaRef](https://github.com/HighCapable/KavaRef)，功能与算法与旧版逐字对齐。

## 能帮你解决什么

- **媒体音量档位（10~100）**：整条音量被切成多少格。格数越多，每格之间的音量差越小、调得越细，解决"相邻两档听感相同""一开就炸、再小就没声"。
- **音量键步进（10~29 段）**：把整条音量均分成你设定的段数，每按一次音量键恰好跳一格；落点稳定可预期，且不影响屏幕滑条逐档精调。
- **最小 / 最大音量**：抬高第 1 档起点（低档听不见时上调），限制最高点（保护听力、安静环境使用）。
- **三种音量控制模式**：绝对音量（AVRCP 曲线映射）/ 相对音量（手机端软件衰减）/ 默认（直通系统原生行为）。
- **按输出设备独立配置**：蓝牙、有线耳机、外放各自保存模式与范围；打开 App 自动定位到当前输出设备。
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

未 Root 的设备无法使用：档位改写与配置下发都要经过 root 通道。

## 快速上手

1. 安装 App，在 LSPosed 中启用 **HyperVolume** 模块（作用域已静态声明）。
2. 打开 App，在「媒体音量档位」卡片打开 **启用档位修改** 开关。
3. 按需调整档位、按键步进、顶部设备选项卡（蓝牙/有线耳机/外放）的模式与最小/最大范围、映射曲线——右侧曲线图与下方映射表**实时预览**。
4. 点 **保存修改**，确认框会逐条列出改动，并按需询问重启什么组件：
   - 改了**档位数** → 需「重启系统框架」（屏幕短暂黑屏属正常现象）；
   - 改了**模式 / 音量范围** → 通常「重启蓝牙」即可；
   - 音量键步进即时生效。
5. 想回到原生行为：选 **默认** 模式，或点 **恢复系统默认（关闭模块）**。

排查手段：首页「运行状态」六行检测（模块是否加载、档位数是否生效、当前输出设备等）；顶栏「查看模块日志」读取模块自写日志（支持复制/清理）。

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

版本号单一来源：`gradle.properties` 的 `appVersionName` / `appVersionCode`。

## 架构

```
app/src/main/java/com/xiefeihong/hypervolume/
├── HookEntry.kt                 # @YukiHookLibXposedEntry 模块入口（configure + registerFrameworkEvents + encase）
├── core/                        # 纯逻辑层：被 App 进程与 Hook 端（system_server / 蓝牙进程）共用，禁止引用任何 Xposed API
│   ├── Prefs.kt                 #   配置常量 + 档位/键步网格吸附算法（clampMediaSteps/keyDelta/nextKeyStepUp…）
│   ├── VolumeConfig.kt          #   不可变配置值对象 + 自描述 XML 序列化/解析（含 XXE 加固）
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
│   ├── ConfigStore.kt           #   SharedPreferences 持久化（整份配置存单键 config_xml）
│   ├── StatusProbe.kt           #   运行状态检测：逐行状态文本生成
│   ├── EditorState.kt           #   编辑态快照（模型 / 暂存 / 基线 / 弹框状态）
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
5. App 保存时以 root 写入 post-fs-data 开机脚本，system_server 启动前直接 `setprop` 真实属性（不依赖任何 Hook）。

对 AudioService 构造器与 `createStreamStates` 执行 `deoptimize`，防止调用点被内联后绕过属性拦截。

**音量键步进**：接管 `adjustStreamVolume`（仅媒体流、仅按键），按网格吸附重算目标级；拖动与按键以相邻变更间隔区分；拖后经 Binder 下发的场景回落到 `setStreamVolume` / `setStreamVolumeWithAttribution`。

**相对音量（模式B）**：Hook `VolumeStreamState#setStreamVolumeIndex` 按 min~max 曲线做软件衰减，并反射恢复 `mIndexMap` 原始档位，避免 `getStreamVolume` 读回二次映射导致音量卡死；同时 `avrcpSupportsAbsoluteVolume` 强制上报不支持、拦截 `postSetAvrcpAbsoluteVolumeIndex`。

**蓝牙进程侧**：Hook `AvrcpVolumeManager#systemToAvrcpVolume` / `avrcpToSystemVolume`（正/反函数保持双向一致）；默认模式收窄范围时改 `AvrcpNativeInterface.sendVolumeChanged` 抬升绝对音量以消除首档无声；模式B 在 `deviceConnected` 阻断协商。

## 配置格式与生效链路

配置以**自描述 XML**（不兼容任何历史的位置式 `;` 串格式）在三个通道间传递：

```xml
<config enabled="1" mediaSteps="50" keySteps="15">
  <device name="speaker" mode="2">
    <b min="2" max="48" curve="0"/>
    <d min="0" max="100" curve="0"/>
  </device>
  <device name="wired" mode="2"> ... </device>
  <device name="bt" mode="1">
    <a min="0" max="127" curve="2"/>
    <b min="2" max="100" curve="0"/>
    <d min="0" max="100" curve="0"/>
  </device>
</config>
```

| 通道 | 位置 | 用途 |
|---|---|---|
| App 暂存 | `SharedPreferences("settings")` 单键 `config_xml` | 编辑态持久化；另存 `system_default_steps` / 环境事实 |
| 主通道 | `Settings.Global["volume_steps_hook_config"]`（格式化 XML） | Hook 端实时读取，`registerContentObserver` 监听变更即时失效缓存 |
| 镜像兜底 | `/data/system/hypervolume_config` | 冷启动早期 SettingsProvider 未就绪时直读 |
| 开机脚本 | `/data/adb/post-fs-data.d/hypervolume.sh` | Magisk / KernelSU 最早阶段 `setprop ro.config.media_vol_steps` |
| 开发者选项 | `Settings.Global["bluetooth_disable_absolute_volume"]` | 模式B 时置 `1` |
| 模块日志 | `/data/system/hypervolume_sys.log`、`/data/data/com.android.bluetooth/files/hypervolume_bt.log` | App 内「查看模块日志」读取 |

`mode`：`0=默认（直通）` / `1=绝对音量`（仅蓝牙，其他设备归一为 2） / `2=相对音量`；`curve`：`0=对数` / `1=线性` / `2=平方根`。

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
build/apk/HyperVolume-v<appVersionName>-release-unsigned.apk
```

> 产物输出目录必须是独立子目录：若直接复制到工程根目录，Gradle 会把根目录推断为 copyApk 输出，而根目录同时是 `app/src` 全部编译输入的祖先目录，`compileReleaseKotlin` 会因「uses this output of task without declaring an implicit dependency」直接失败。

**发布签名**：根目录放置 `keystore.properties`（该文件含口令，已在 `.gitignore` 中，不入库）即自动启用 release 签名：

```properties
storeFile=../release.keystore
storePassword=****
keyAlias=****
keyPassword=****
```

缺失该文件时 release 退化为**未签名包**，以便 CI / 他机构建不失败。

## 测试

JVM 单元测试覆盖与界面无关的核心算法，共 **7 个测试类 / 57 个用例**（`failures=0 errors=0`）：

| 测试类 | 覆盖内容 |
|---|---|
| `PrefsTest` | 档位/键步 clamp、`keyDelta`、网格吸附 `nextKeyStepUp/Down` 的严格单调性与边界 |
| `VolumeConfigTest` | XML 往返序列化、非法/缺失字段回落、XXE 与畸形输入加固 |
| `AvrcpTest` | AVRCP 0~127 映射、曲线标签、预览与映射表文本生成 |
| `EditorStateTest` | 编辑态派生（目标物理档位、未保存判定） |
| `PreviewBuilderTest` | 曲线点列与映射表构建 |
| `SavePlanTest` | 保存计划：门控三选项、改动清单、重启判定（系统框架 / 蓝牙） |
| `StatusLinesTest` | 运行状态各行文本生成 |

界面与应用侧逻辑已在模拟器（Pixel 3a / API 34）实机验收：状态检测区、开关与禁用态滑条、设备选项卡切换改变「绝对音量」可用性、模式芯片禁用置灰、等宽预览摘要与映射表、曲线图双口径切换与轴标签刷新、日志/提示/保存确认弹框、保存失败 Toast、未保存提示行、使用说明。

> **Hook 运行时行为尚未在真机验证**：模拟器无 HyperOS 环境、无真实音频与蓝牙硬件。`AudioHooks` / `BtHooks` 的实际效果与 root 保存/重启流程需在真机启用模块后确认。

## 与旧版 volume_control 的差异

| | volume_control（Java） | HyperVolume（Kotlin） |
|---|---|---|
| 语言 / JVM | Java 21 | Kotlin 2.4.21 / JVM 17 |
| 界面 | AppCompat + Material3 + ViewBinding | Miuix 0.9.4 + Compose 1.12（HyperCeiler 风格卡片流） |
| 模块入口 | 手写 `XposedModule` + `META-INF/xposed/*` 资源 | `@YukiHookLibXposedEntry` 注解 + KSP 编译期生成 |
| 反射 | 手写 `Class.getMethod` + 缓存 | YukiHookAPI `HookChain` + KavaRef（保留方法/字段缓存与逐级回退） |
| 配置格式 | 10 字段位置式 `;` 串 | 自描述 XML（按设备分组，字段可缺失回落默认） |
| 保存流程 | `AlertDialog` + `Toast` + `Handler` 手工线程 | `SaveEngine` 先算 `SavePlan` 再执行 `suspend` root 动作，逻辑可单测 |
| 档位上限 | 127 | 100 |

功能语义与算法判据（门控条件、重启判定、按键网格、曲线映射）与旧版逐条对齐。

## 常见问题

- **改完没生效？** 档位数变化必须「重启系统框架」；只调范围/模式一般「重启蓝牙」即可。App 会在保存后按需提示。
- **一定要 Root 吗？** 是的，需要 Root + LSPosed；未 Root 无法下发配置与开机脚本。
- **会不会影响其他音频？** 只作用于媒体音量；随时可用「恢复系统默认」还原，或在 LSPosed 中停用模块后重启手机。
- **其他机型表现不同？** 档位数与原声曲线因厂商音频配置而异，App 会自动读取本机实际配置；读不到任何 ROM 文件时曲线查看器回退 AOSP 参考曲线。

## 许可

本项目以 **GNU GPLv3** 发布（见 [LICENSE](LICENSE)），主要基于同样以 GPLv3 发布的 [Miuix](https://github.com/Miuix-UI/Miuix)、[YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)、[KavaRef](https://github.com/HighCapable/KavaRef) 与 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) 的设计与实现。仅供学习与个人使用。
