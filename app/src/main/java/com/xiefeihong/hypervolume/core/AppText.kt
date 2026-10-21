package com.xiefeihong.hypervolume.core

import android.content.Context

/**
 * 文案接缝：让**非 Compose 层**（[com.xiefeihong.hypervolume.core.Avrcp] 的预览文本、
 * [com.xiefeihong.hypervolume.app.StatusProbe] 的状态行、[com.xiefeihong.hypervolume.app.SaveEngine]
 * 的确认清单等纯逻辑代码）也能取到随语言切换的字符串。
 *
 * 这些文本是「计算出来的整句」，无法在 Composable 里用 `stringResource` 替换，早先以中文
 * 字面量写在 app/core 层以便 JVM 单测直接断言。引入双语后改为在此按资源 id 取值：
 *  - **生产**：[install] 由 `MainActivity.attachBaseContext` 调用，传入的是**按语言包裹过的
 *    application context**（locale 覆盖不会透过 `getApplicationContext()` 传递），
 *    于是与界面用同一份 locale 化资源；
 *  - **单测**：[installResolver] 注入一个解析 `src/main/res/values/strings.xml` 的实现，
 *    既有的中文断言因此无需改写，也不依赖 Robolectric。
 *
 * 用资源 **id**（而非名字）寻址，好处是 `R.string.*` 编译期即可校验，不会拼错键名。
 * 与 `Context.getString` 保持一致的格式化语义：**无实参时不做 `String.format`**，
 * 因此正文里的百分号无需转义；带实参的资源中要输出字面 `%` 必须写成 `%%`。
 */
object AppText {

    /** 资源 id → 字符串的取值器（生产用 Context 实现，单测用 XML 解析实现）。 */
    fun interface Resolver {

        /** [args] 为空时按原文返回（不格式化），否则执行位置参数格式化。 */
        fun getString(id: Int, args: Array<out Any?>): String
    }

    /** 未安装时的占位实现：让「忘记 install」在界面上一眼可见，而不是静默空白。 */
    private val UNINSTALLED = Resolver { id, _ -> "<text:$id>" }

    @Volatile
    private var resolver: Resolver = UNINSTALLED

    /** 用（已按语言包裹的）Context 安装取值器；重复调用即切换到新 locale。 */
    fun install(context: Context) {
        resolver = ContextResolver(context)
    }

    /** 注入自定义取值器（单测入口）。 */
    fun installResolver(resolver: Resolver) {
        this.resolver = resolver
    }

    /** 取字符串；[args] 非空时按 `%1$s` 式位置参数格式化。 */
    fun str(id: Int, vararg args: Any?): String = resolver.getString(id, args)

    /** 生产实现：走 Context 的 locale 化资源，语义与 `getString(id)` / `getString(id, args)` 对齐。 */
    private class ContextResolver(private val context: Context) : Resolver {

        override fun getString(id: Int, args: Array<out Any?>): String =
            if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }
}
