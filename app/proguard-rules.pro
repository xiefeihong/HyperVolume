# YukiHook / libxposed：入口类与 Hook 回调实现必须保留
-keep class com.xiefeihong.hypervolume.HookEntry { *; }
-keep class com.xiefeihong.hypervolume.HookEntry_YukiHookXposedInit { *; }
-keep class * extends com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit { *; }

# Hook 回调中通过 KavaRef 反射访问的宿主类成员按名字查找，业务侧日志亦依赖类名
-keep class com.xiefeihong.hypervolume.hook.** { *; }

# Kotlin 元数据（KavaRef 依据 KClass 扩展解析成员）
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# 常见可选依赖告警
-dontwarn org.jetbrains.annotations.**
-dontwarn io.github.libxposed.**
