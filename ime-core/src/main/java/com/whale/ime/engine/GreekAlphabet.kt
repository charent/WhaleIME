package com.whale.ime.engine

import com.whale.ime.config.SchemaIds
import com.whale.ime.engine.schema.T14KeyUtils
import com.whale.ime.engine.schema.T9PinYinUtils
import java.util.Locale

/**
 * 希腊字母表：输入码 → 字母。
 *
 * 一个字母挂多个码，两类来源：
 * 1. **中文谐音拼音**（阿尔法 `aerfa`、贝塔 `beita`、缪 `miu`…）：中文使用者最自然的输入方式；
 * 2. **英文名**（`alpha` / `epsilon` / `omicron`…）：希腊字母的通用名称。
 *
 * 码是**完整字符串精确匹配**（[RimeEngine] 里与组合串比对），不参与拼音切分，
 * 所以 `deerta`（德尔塔）、`aomikerong`（奥密克戎）这类多音节串不需要引擎能切出音节，
 * 拼对就能出候选。
 *
 * 一个码只属于一个字母：`xi` 给 ξ（xenon 的英文名），χ 用 `kai` / `chi`；
 * `pi` 给 π，π 的中文谐音走 `pai`。新增码前先确认这里没有重复，否则后者会静默覆盖前者。
 */
object GreekAlphabet {

    /** 单个字母：小写、大写，以及触发它的全部码（小写） */
    class Letter internal constructor(
        val lower: String,
        val upper: String,
        vararg codes: String,
    ) {
        val codes: List<String> = codes.toList()
    }

    private val LETTERS = arrayOf(
        Letter("α", "Α", "aerfa", "alpha"),
        Letter("β", "Β", "beita", "beta"),
        Letter("γ", "Γ", "gama", "jiama", "gamma"),
        Letter("δ", "Δ", "deerta", "delta"),
        Letter("ε", "Ε", "yipuxilong", "aipuxilong", "epsilon"),
        Letter("ζ", "Ζ", "zeta"),
        Letter("η", "Η", "yita", "aita", "eta"),
        Letter("θ", "Θ", "xita", "theta"),
        Letter("ι", "Ι", "yueta", "yueda", "iota"),
        Letter("κ", "Κ", "kapa", "kappa"),
        Letter("λ", "Λ", "lanmuda", "lamuda", "lambda"),
        Letter("μ", "Μ", "miu", "mu"),
        Letter("ν", "Ν", "niu", "nu"),
        Letter("ξ", "Ξ", "kexi", "xi"),
        Letter("ο", "Ο", "aomikerong", "aomiga", "omicron"),
        Letter("π", "Π", "pai", "pi"),
        Letter("ρ", "Ρ", "rou", "rho"),
        Letter("σ", "Σ", "xigema", "sigma"),
        Letter("τ", "Τ", "tao", "tau"),
        Letter("υ", "Υ", "yupuxilong", "upsilon"),
        Letter("φ", "Φ", "fei", "fai", "phi"),
        Letter("χ", "Χ", "kai", "chi"),
        Letter("ψ", "Ψ", "pusai", "puxi", "psi"),
        Letter("ω", "Ω", "oumijia", "oumiga", "omega"),
    )

    private val byCode: Map<String, Letter> = HashMap<String, Letter>(64).apply {
        for (letter in LETTERS) {
            for (code in letter.codes) put(code, letter)
        }
    }

    /**
     * 键码 → 字母，按方案各建一份索引。
     *
     * 九键的一个键码串可能对应多个字母：`MT` 既是 `mu` 也是 `nu`，`MGT` 既是 `miu`
     * 也是 `niu`；14 键同理（`DA` 既是 da 也是 fa）。这类多义串整组返回，注入时
     * 按候选顺序给出。
     */
    private val byKeyCode: Map<String, Map<String, List<Letter>>> = run {
        val mappers: Map<String, (Char) -> Char> = mapOf(
            SchemaIds.SCHEMA_ZH_T9 to { c: Char -> T9PinYinUtils.pinyin2T9Key(c) },
            SchemaIds.SCHEMA_ZH_T14 to { c: Char -> T14KeyUtils.pinyin2KeyChar(c) },
        )
        mappers.mapValues { (_, mapper) ->
            val acc = HashMap<String, MutableList<Letter>>(64)
            for (letter in LETTERS) {
                for (code in letter.codes) {
                    val key = buildString(code.length) { code.forEach { append(mapper(it)) } }
                    val list = acc.getOrPut(key) { ArrayList(2) }
                    if (list.none { it === letter }) list.add(letter)
                }
            }
            acc
        }
    }

    /** 取码对应的字母；无命中返回 null。大小写不敏感（组合串本来就是小写）。 */
    fun match(code: String): Letter? = if (code.isEmpty()) null else byCode[code.lowercase(Locale.ROOT)]

    /** 取键码串（大写，如 `MGT` / `QO`）对应的全部字母，无命中返回空表。 */
    fun matchKeyCodes(keyCodes: String, schemaId: String = SchemaIds.SCHEMA_ZH_T9): List<Letter> =
        byKeyCode[schemaId]?.get(keyCodes) ?: emptyList()

    /**
     * 拼音串 → 键码串（大写），按方案选择九键或 14 键键位。非 a–z 字符原样保留，
     * 便于把引擎给的读音（`mi'u`、`xi'ge'ma`）直接转成键码串与用户敲的键位比对。
     */
    fun toKeyCode(text: String, schemaId: String = SchemaIds.SCHEMA_ZH_T9): String {
        if (text.isEmpty()) return ""
        val useT14 = schemaId == SchemaIds.SCHEMA_ZH_T14
        val sb = StringBuilder(text.length)
        for (c in text) {
            val lower = c.lowercaseChar()
            sb.append(if (useT14) T14KeyUtils.pinyin2KeyChar(lower) else T9PinYinUtils.pinyin2T9Key(lower))
        }
        return sb.toString()
    }
}
