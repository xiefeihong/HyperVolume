package com.xiefeihong.hypervolume

import com.xiefeihong.hypervolume.core.AppText
import org.junit.Before
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 单测文案后端：把 `src/main/res/values/strings.xml` 解析成「资源 id → 字符串」注入 [AppText]。
 *
 * app/core 层的动态文案（预览整句、状态行、保存清单）已改为按资源取值，注入本解析器后
 * JVM 单测里仍拿到简体中文，既有的中文断言无需改写，也不需要 Robolectric。
 *
 * 解析刻意贴合 aapt2 的字符串资源规则：XML 实体解码 → 空白折叠为单个空格并去首尾 →
 * 处理 `\n` 等反斜杠转义；与 [AppText] 一致，**只有带实参时才执行 `String.format`**。
 */
object TestText {

    private val ENTRY = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    /** 资源 id → 已解码的默认（中文）文案。 */
    val defaults: Map<Int, String> by lazy {
        entries(defaultFile().readText()).mapNotNull { (name, value) ->
            R.string::class.java.fields.firstOrNull { it.name == name }?.getInt(null)?.let { it to value }
        }.toMap()
    }

    /** 注入取值器；重复调用无副作用（同一份表）。 */
    fun install() {
        val table = defaults
        AppText.installResolver { id, args ->
            val raw = table[id] ?: "<missing:$id>"
            if (args.isEmpty()) raw else String.format(Locale.getDefault(), raw, *args)
        }
    }

    /** 默认（中文）资源文件的 name → 解码后文案，供资源完整性单测使用。 */
    fun defaultEntries(): Map<String, String> = entries(defaultFile().readText())

    /** 英文资源文件的 name → 解码后文案（不存在时返回空表）。 */
    fun englishEntries(): Map<String, String> {
        val file = locate("values-en/strings.xml") ?: return emptyMap()
        return entries(file.readText())
    }

    private fun defaultFile(): File = checkNotNull(locate("values/strings.xml")) {
        "找不到 src/main/res/values/strings.xml（工作目录：${File(".").absolutePath}）"
    }

    /** 从测试工作目录向上查找 `src/main/res/<relative>`（兼容模块目录与仓库根两种 cwd）。 */
    private fun locate(relative: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(
                File(dir, "app/src/main/res/$relative"),
                File(dir, "src/main/res/$relative"),
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    /** 解析 `<string name="…">…</string>` 全集，并按 aapt 规则还原最终文案。 */
    private fun entries(xml: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val nodes = document.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            out[node.getAttribute("name")] = normalize(node.getTextContent())
        }
        // 正则仅作兜底校验用：DOM 已覆盖全部条目时二者数量一致
        check(out.size == ENTRY.findAll(xml).count()) { "字符串资源解析数量不一致" }
        return out
    }

    /** aapt 规则：空白串塌成单个空格并去首尾，再处理 `\n`、`\'` 等转义。 */
    private fun normalize(raw: String): String = raw
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\r", "\r")
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    /** 位置参数集合（用于中英双语的参数一致性断言）。 */
    fun argSpecs(text: String): List<String> = Regex("%(\\d+)\\$[ds]").findAll(text).map { it.value }.toList().sorted()

    /**
     * 值里含**真实换行**的键（按语言分组）。
     *
     * aapt2 会把未转义的多行内容折叠成单个空格，因此需要换行的文案必须写在同一物理行、
     * 用 `\n` 转义；本项用于守住这条书写约束（单测里的 [normalize] 同样会折叠）。
     */
    fun rawNewlineKeys(): Map<String, List<String>> = buildMap {
        put("default", rawNewlineKeys(defaultFile().readText()))
        locate("values-en/strings.xml")?.let { put("en", rawNewlineKeys(it.readText())) }
    }

    private fun rawNewlineKeys(xml: String): List<String> =
        ENTRY.findAll(xml)
            .filter { it.groupValues[2].contains('\n') }
            .map { it.groupValues[1] }
            .toList()
}

/**
 * 依赖资源文案的单测基类：装配 [TestText] 后，app/core 层的动态文案在 JVM 里返回简体中文。
 */
abstract class TextBackedTest {

    @Before
    fun bindTestText() {
        TestText.install()
    }
}
