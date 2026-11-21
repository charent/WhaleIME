package com.whale.ime.engine.schema

import android.content.Context
import android.util.Log
import com.whale.ime.boot.rime.RimePaths
import com.whale.ime.preference.AppPrefs
import com.whale.ime.rime.Rime
import java.io.File

/**
 * 拼音纠错 / 模糊音 / v 键等同 u 的 schema patch 落地。
 *
 * 这几个开关是 schema 里的配置（`translator/enable_correction` 与 `speller/algebra`），
 * 运行时改不了已加载的 schema，只能把偏好写成 user 目录的 `<schema>.custom.yaml` patch，
 * 再重部署一次（部署期把 patch 编进 `build/` 产物、并按新 algebra 重建 prism）。
 *
 * 两套目标（同一个开关，规则形态不同）：
 * - `pinyin.custom.yaml`：26 键全键，规则是**拼音层**的 derive（`zh → z`、`jqxy + v`）；
 * - `t14_pinyin.custom.yaml`：14 键，输入串是**大写键码**，模糊音 / v→u 必须写成
 *   键码层的等价 derive（`ZGA → ZA`、`QU → QC`），见 [T14KeyUtils.fuzzyRules]。
 * 九键没有开原生纠错（键码是 3~4 字母一键、歧义过大），不写 patch。
 *
 * 部署能在设备上跑通，依赖 `cpp/scripts/rime-dict-compiler-whale.cc` 那处 librime 补丁：
 * 本项目 assets 只有预编译 `*.table.bin` / `*.prism.bin`（没有词库源），上游在这里会
 * (a) 因 reverse.bin 缺失而用空词表重建，写出 3.5KB 的空表盖掉真的 62MB 词库、
 * (b) 单表词典取不到音节表导致 prism 重建失败。两者都会让键盘「有拼音串、没候选」。
 * 补丁修好后：patch 变化 → 重部署只重建 prism（词表沿用预编译的那份）。
 *
 * patch 键用 `speller/algebra/+` 追加而不是 `speller/algebra:`：librime 的
 * ConfigCompiler 对不带 `/+` 的列表键是整段替换（`config_compiler.cc` 的 `EditNode`），
 * 写成覆盖会把 schema 自带的 `abbrev`（超级简拼）一起顶掉。
 */
object CorrectionSchema {

    private const val TAG = "PinyinCorrection"

    /** 我们生成的 patch 首行标记：只有带这行的文件才允许删/改 */
    private const val HEADER = "# 由输入法设置自动生成，勿手改（见 CorrectionSchema）"

    /** `rime-user/build/`：部署产物目录 */
    private const val BUILD_DIR = "build"

    /** 完成标记内容（只为可读性，存在与否才是语义） */
    private const val DEPLOY_MARK_CONTENT = "deploy completed\n"

    /** 一个需要同步 patch 的方案：文件名（不含 `.custom.yaml`）与规则生成器 */
    private class Target(
        val schemaFile: String,
        val rules: (fuzzy: Boolean, vAsU: Boolean) -> List<String>,
    )

    private val TARGETS = listOf(
        Target("pinyin") { fuzzy, vAsU -> pinyinAlgebraRules(fuzzy, vAsU) },
        Target("t14_pinyin") { fuzzy, vAsU -> t14AlgebraRules(fuzzy, vAsU) },
    )

    /**
     * 把当前偏好同步到各方案的 custom patch，必要时重部署。
     *
     * - 偏好等于 schema 默认（纠错开、模糊关、v 键关）→ 不需要 patch：有我们生成的则删掉；
     * - 偏好非默认 → 写 patch；内容没变且部署产物在 → 直接返回（绝大多数启动零开销）；
     * - 部署产物（`rime-user/build`）缺失（首装、词库版本变更被清、异常被清理）→ 即使 patch
     *   内容没变也补一次部署，否则 patch 不会生效。
     *
     * 必须在引擎启动后、后台线程调用。
     */
    fun syncFromPrefs(context: Context) {
        val prefs = AppPrefs.getInstance().input
        val correction = prefs.pinyinCorrectionEnable.getValue()
        val fuzzy = prefs.pinyinFuzzyEnable.getValue()
        val vAsU = prefs.pinyinVAsUEnable.getValue()
        val userDir = RimePaths.userDir(context)
        var needsRedeploy = false
        for (target in TARGETS) {
            needsRedeploy = syncTarget(userDir, target, correction, fuzzy, vAsU) || needsRedeploy
        }
        if (needsRedeploy) redeployAndReselect(context)
    }

    /** @return true 表示 patch 有变化，需要重部署 */
    private fun syncTarget(
        userDir: File,
        target: Target,
        correction: Boolean,
        fuzzy: Boolean,
        vAsU: Boolean,
    ): Boolean {
        val file = File(userDir, "${target.schemaFile}.custom.yaml")
        val existing = runCatching { file.readText() }.getOrNull()
        if (existing != null && !existing.startsWith(HEADER)) {
            // 用户自己维护的定制文件：我们的语义（追加而非覆盖）与它冲突，静默改写会毁掉用户配置
            Log.w(TAG, "${file.name} 非本程序生成，跳过同步")
            return false
        }
        val rules = target.rules(fuzzy, vAsU)
        if (correction && rules.isEmpty()) {
            // schema 默认状态：没有 patch 才是对的
            if (existing == null) return false
            runCatching { file.delete() }.onFailure { Log.w(TAG, "delete ${file.name} failed: $it") }
            Log.i(TAG, "${file.name} removed, restoring schema defaults")
            return true
        }
        val patch = buildPatch(correction, rules)
        val deployed = File(userDir, "build").isDirectory
        if (existing == patch && deployed) return false
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(patch)
        }.onFailure {
            Log.w(TAG, "write ${file.name} failed: $it")
            return false
        }
        Log.i(TAG, "${file.name} updated, redeploying")
        return true
    }

    /** 引擎异常（如 .so 未提供 redeploy 符号）绝不能带崩进程：开关退化为本次不生效 */
    private fun redeployAndReselect(context: Context) {
        val redeployed = runCatching { Rime.redeploy() }
            .onFailure { Log.w(TAG, "redeploy failed: $it") }
            .getOrDefault(false)
        if (!redeployed) return
        markDeployCompleted(context)
        // schema 配置在部署期编译进 build/，重选当前方案才会用新配置重建翻译器
        val current = Rime.getCurrentRimeSchema()
        if (current.isNotEmpty()) Rime.selectSchema(current)
    }

    /**
     * 部署跑完才写完成标记（`rime-user/build/.deployed`）。
     *
     * 部署要重编上百 MB 的 prism / 词表，耗时数秒；这期间 IME 进程被回收是很常见的。
     * 半套产物会让运行时读到残缺的 prism（候选变少甚至只剩编码），且目录还在，
     * `syncFromPrefs` 下次不会重编 —— 死循环只有清 App 数据能解。标记缺失即视为被打断，
     * 由 `RimeDataInstaller` 在下次启动时丢弃整个 build 目录。
     */
    private fun markDeployCompleted(context: Context) {
        runCatching {
            val buildDir = File(RimePaths.userDir(context), BUILD_DIR)
            if (buildDir.isDirectory) {
                File(buildDir, RimePaths.DEPLOY_COMPLETED_MARK).writeText(DEPLOY_MARK_CONTENT)
            }
        }.onFailure { Log.w(TAG, "write deploy mark failed: $it") }
    }

    internal fun buildPatch(correction: Boolean, rules: List<String>): String =
        buildString {
            append(HEADER).append('\n')
            append("patch:\n")
            // schema 默认已开纠错，显式写值是为了开关关掉后能覆盖回来
            append("  translator/enable_correction: ")
            append(if (correction) "true" else "false")
            append("\n")
            if (rules.isNotEmpty()) {
                // `/+` 追加而非覆盖：schema 自带的 abbrev（超级简拼）必须保留
                append("  speller/algebra/+:\n")
                for (rule in rules) append("    - ").append(rule).append('\n')
            }
        }.trimEnd() + "\n"

    /** 26 键：拼音层的规则（与 vAsU / 模糊音设置一一对应） */
    private fun pinyinAlgebraRules(fuzzy: Boolean, vAsU: Boolean): List<String> = buildList {
        if (vAsU) add(V_AS_U_RULE)
        if (fuzzy) addAll(FUZZY_RULES)
    }

    /**
     * 14 键：键码层的规则。
     *
     * 输入串是大写键码（`ZGA` = zha），拼音层的 `derive/^([zcs])h/$1/` 永远不匹配，
     * 所以由 `gen-t14-algebra.py` 预先算出「每个音节的模糊变体对应的键码」，
     * 这里只负责在开关打开时把整段规则追加进 `t14_pinyin.custom.yaml`。
     */
    private fun t14AlgebraRules(fuzzy: Boolean, vAsU: Boolean): List<String> = buildList {
        if (vAsU) addAll(T14KeyUtils.vAsURules)
        if (fuzzy) addAll(T14KeyUtils.fuzzyRules)
    }

    /**
     * j/q/x/y + v 等价于 + u（qv → qu 去、jve → jue 觉、qvan → quan 全）。
     *
     * 拼音里 j/q/x/y 后面的 ü 写作 u，用户按 v（ü 的常用替代键）时应当命中同一个音节。
     * 由 schema 派生额外拼写而不是改按键：引擎收到的仍是用户原始按键，组合串原样显示
     * `qv`/`jve`，候选注释里的读音仍是 `qu`/`jue`，候选集与直接打 qu 完全一致。
     *
     * 规则只锚定首字母 jqxy：`lv / nv / lve / nve` 里的 v 是真正的 ü（绿/女/略/虐），
     * 全局 `v → u` 会把「绿」打成「路」。不锚定结尾，一条规则覆盖全部 16 个音节
     * （ju/jue/jun/juan、qu/…、xu/…、yu/…）。derive 保留原拼写。
     */
    private const val V_AS_U_RULE = "derive/^([jqxy])u/\$1v/"

    /**
     * 模糊音规则（derive：在原拼写之外追加变形拼写，不替换原拼写）。
     * 与设置页文案一致：z/zh、c/ch、s/sh、n/l、f/h、r/l、an/ang、en/eng、in/ing。
     */
    private val FUZZY_RULES = listOf(
        "derive/^([zcs])h/\$1/",
        "derive/^([zcs])/\$1h/",
        "derive/^n/l/",
        "derive/^l/n/",
        "derive/^f/h/",
        "derive/^h/f/",
        "derive/^r/l/",
        "derive/ang\$/an/",
        "derive/an\$/ang/",
        "derive/eng\$/en/",
        "derive/en\$/eng/",
        "derive/ing\$/in/",
        "derive/in\$/ing/",
    )

    /** 设置页开关变化时调用：后台线程重写 patch 并重部署，不阻塞 UI */
    fun onSettingChanged(context: Context) {
        com.whale.ime.util.thread.ThreadPoolUtils.executeSingleton {
            syncFromPrefs(context)
        }
    }
}
