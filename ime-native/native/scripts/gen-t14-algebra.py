#!/usr/bin/env python
"""生成 14 键拼音方案的 speller/algebra 键码段与 Kotlin 数据表。

背景
----
14 键键盘（QW/ER/TY/UI/OP · AS/DF/GH/JK/L · ZX/CV/BN/M）每个按键最多代表
两个字母，送进引擎的是「键位代表字母」串（`wo` 按 QW+OP → `QO`），而 prism 里
存的是音节拼写。与九键同理，schema 必须用 spelling algebra 把音节映射成键码：

    da → DA、fa → DA（同键码多音节一对多，14 键的歧义远小于九键）
    zhe → ZGE、bu → BA

键码取「该键首字母大写」（与 Kotlin 侧 T14KeyMap.CHAR_TO_KEY 必须一致），
这样大写 = 键码、小写 = 拼音，与九键的约定相同，`KeyRecordStack` / `replaceKey`
那套机制可以原样复用。

音节表来源
----------
不直接读 `pinyin.prism.bin`：白霜词库的细胞表里有 `asakawa` / `satoshi` 这类
日文姓名拼写，会被 prism 当成「音节」，生成一堆无意义的键码串。这里以
`t9_pinyin.schema.yaml` 已确认的 414 个拼音音节为白名单（与九键同源同覆盖），
保证 14 键与九键对同一份词库的理解一致。

模糊音（14 键独立支持）
----------------------
九键的模糊音规则写在 `pinyin.custom.yaml` 的拼音层（`derive/^([zcs])h/$1/`），
对键码拼写无效。14 键这里由本脚本额外产出**键码层**的模糊规则：

    zha → ZGA，za → ZA：derive/^ZGA$/ZA/ + derive/^ZA$/ZGA/

规则由 CorrectionSchema 按 `pinyinFuzzyEnable` 写进 `t14_pinyin.custom.yaml`，
默认关闭；开启后重部署会把新拼写编进用户目录的 prism。

用法
----
    python gen-t14-algebra.py            # 打印 algebra 段
    python gen-t14-algebra.py --write    # 改写 schema 的 GENERATED 段 + T14KeyMap.kt
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
T9_SCHEMA = os.path.join(REPO, "ime-native", "src", "main", "assets", "rime",
                         "t9_pinyin.schema.yaml")
SCHEMA = os.path.join(REPO, "ime-native", "src", "main", "assets", "rime",
                      "t14_pinyin.schema.yaml")
KOTLIN = os.path.join(REPO, "ime-core", "src", "main", "java", "com", "whale", "ime",
                      "engine", "schema", "T14KeyMap.kt")

# 14 键键位（与 Kotlin 侧 T14KeyMap.CHAR_TO_KEY / KeyboardData.layoutT14Cn 一致）：
# 键码 = 各键首字母大写，字母顺序按键盘上从左到右的排布。
T14_KEYS = [
    ("Q", "qw"), ("E", "er"), ("T", "ty"), ("U", "ui"), ("O", "op"),
    ("A", "as"), ("D", "df"), ("G", "gh"), ("J", "jk"), ("L", "l"),
    ("Z", "zx"), ("C", "cv"), ("B", "bn"), ("M", "m"),
]
CHAR_TO_KEY = {c: key for key, chars in T14_KEYS for c in chars}

# 模糊音成对定义（双向生成，与 InputPrefs 文案一致）：
# z/zh、c/ch、s/sh、n/l、f/h、r/l、an/ang、en/eng、in/ing
FUZZY_INITIALS = [("zh", "z"), ("ch", "c"), ("sh", "s"), ("n", "l"), ("f", "h"), ("r", "l")]
FUZZY_FINALS = [("ang", "an"), ("eng", "en"), ("ing", "in")]

# j/q/x/y + v 视作 u（qv → qu 去）：与 26 键 V_AS_U_RULE 等价，只是换成键码表达
V_AS_U_INITIALS = "jqxy"

BEGIN_MARK = "    # ==== GENERATED: 14 键键码映射"
END_MARK = "    # ==== /GENERATED ===="

DERIVE_RE = re.compile(r"-\s+derive/\^\(?(.+?)\)?\$/([A-Z]+)/")


def load_syllables(path):
    """从 t9 schema 的 GENERATED 段还原音节白名单（414 个纯小写拼音音节）。"""
    syllables = set()
    for line in open(path, encoding="utf-8"):
        match = DERIVE_RE.search(line)
        if not match:
            continue
        for syllable in match.group(1).split("|"):
            syllable = syllable.strip()
            if syllable and syllable.isascii() and syllable.islower() and syllable.isalpha():
                syllables.add(syllable)
    return sorted(syllables)


def key_code(syllable):
    return "".join(CHAR_TO_KEY[c] for c in syllable)


def build_algebra(syllables):
    """按键码分组，输出 derive 规则：derive/^(da|fa)$/DA/"""
    by_key = {}
    for syllable in syllables:
        by_key.setdefault(key_code(syllable), []).append(syllable)

    lines = [
        BEGIN_MARK + "（gen-t14-algebra.py 生成，勿手改）====",
        "    # 保留全拼、另派生键码；候选栏点选具体读音后 replaceKey 成小写拼音",
        "    # 仍能查到音节图。键码与 T14KeyMap.CHAR_TO_KEY 一一对应。",
    ]
    for key in sorted(by_key):
        spellings = by_key[key]
        if len(spellings) == 1:
            lines.append("    - derive/^%s$/%s/" % (spellings[0], key))
        else:
            lines.append("    - derive/^(%s)$/%s/" % ("|".join(spellings), key))
    lines.append(END_MARK)
    # 单键/前缀出候选：敲 Q 就要看到 q/w 开头的字（abbrev 拼写，
    # 可信度 log(0.5)，排在整码之后）。只匹配键码（大写开头），不影响全拼。
    lines.append("    - abbrev/^([A-Z]).+$/$1/")
    return "\n".join(lines)


def fuzzy_variants(syllable):
    """模糊音变体（拼音层），不要求变体本身也是合法音节。

    derive 的语义是「派生一条新拼写并指向同一音节」，所以 zha 派生 zua 这类
    非法拼音也是有效的输入通路（用户把 zh 打成 z 的行为本来就该命中 zha）。
    """
    out = set()
    for a, b in FUZZY_INITIALS:
        # zh/z、sh/s 这类互斥前缀用 elif：否则 "sha" 会同时派生出 "sa" 与
        # "shha"（把 s 再插一个 h），后者是一条永远不会被按到的垃圾拼写。
        if syllable.startswith(a):
            out.add(b + syllable[len(a):])
        elif syllable.startswith(b):
            out.add(a + syllable[len(b):])
    for a, b in FUZZY_FINALS:
        if syllable.endswith(a):
            out.add(syllable[:-len(a)] + b)
        if syllable.endswith(b):
            out.add(syllable[:-len(b)] + a)
    out.discard(syllable)
    return out


def v_as_u_variants(syllable):
    """j/q/x/y 后的 u 写作 v 的等价拼写：qu → qv、jue → jve、quan → qvan。"""
    if len(syllable) > 1 and syllable[0] in V_AS_U_INITIALS and syllable[1] == "u":
        return {syllable[0] + "v" + syllable[2:]}
    return set()


def build_rule_pairs(syllables, variant_fn):
    """生成 (键码 from, 键码 to) 对，去重后按键码排序。"""
    pairs = set()
    for syllable in syllables:
        source = key_code(syllable)
        for variant in variant_fn(syllable):
            pairs.add((source, key_code(variant)))
    return sorted(pairs)


def kotlin_string(name, items, per_line=6):
    """把一长串项目拼成多行 Kotlin 字符串常量（便于阅读与编译）。"""
    chunks = []
    for i in range(0, len(items), per_line):
        chunk = ";".join(items[i:i + per_line])
        suffix = ";" if i + per_line < len(items) else ""
        chunks.append('        "%s%s"' % (chunk, suffix))
    if not chunks:
        return '    const val %s = ""' % name
    return "    const val %s =\n" % name + " +\n".join(chunks)


def build_kotlin(syllables, fuzzy_pairs, v_pairs):
    char_pairs = "".join(c + k for k, chars in T14_KEYS for c in chars)
    syllable_items = ["%s %s" % (s, key_code(s)) for s in syllables]
    fuzzy_items = ["%s>%s" % (a, b) for a, b in fuzzy_pairs]
    v_items = ["%s>%s" % (a, b) for a, b in v_pairs]
    return "\n".join([
        "package com.whale.ime.engine.schema",
        "",
        "/**",
        " * 由 `ime-native/native/scripts/gen-t14-algebra.py` 生成，勿手改。",
        " *",
        " * 键码 = 14 键各键首字母大写（QW→Q、ER→E…M→M），与 schema 的 derive 段、",
        " * KeyboardData.layoutT14Cn 的键位一一对应。音节表与九键同源（414 个音节）。",
        " *",
        " * 字符串格式（运行时在 [T14KeyUtils] / [CorrectionSchema] 里解析，避免生成",
        " * 上千行 mapOf）：",
        " * - [CHAR_TO_KEY]：两字符一组「小写字母 + 键码」；",
        " * - [SYLLABLES]：`;` 分隔的「音节 键码」；",
        " * - [FUZZY] / [V_AS_U]：`;` 分隔的「键码 from>键码 to」，供",
        " *   CorrectionSchema 写入 `t14_pinyin.custom.yaml` 的 speller/algebra/+。",
        " */",
        "object T14KeyMap {",
        '    const val CHAR_TO_KEY = "%s"' % char_pairs,
        "",
        kotlin_string("SYLLABLES", syllable_items),
        "",
        kotlin_string("FUZZY", fuzzy_items),
        "",
        kotlin_string("V_AS_U", v_items),
        "}",
        "",
    ])


def main():
    syllables = load_syllables(T9_SCHEMA)
    if len(syllables) < 400:
        print("音节表异常：只读到 %d 个音节" % len(syllables), file=sys.stderr)
        return 1
    block = build_algebra(syllables)
    fuzzy_pairs = build_rule_pairs(syllables, fuzzy_variants)
    v_pairs = build_rule_pairs(syllables, v_as_u_variants)
    if "--write" not in sys.argv:
        print(block)
        print("# 模糊音键码规则 %d 条 / v 视作 u %d 条" % (len(fuzzy_pairs), len(v_pairs)),
              file=sys.stderr)
        return 0

    lines = open(SCHEMA, encoding="utf-8").read().splitlines(keepends=True)
    try:
        start = next(i for i, line in enumerate(lines) if line.startswith(BEGIN_MARK))
        end = next(i for i, line in enumerate(lines) if line.startswith(END_MARK))
    except StopIteration:
        print("没有找到 GENERATED 段，schema 未写入", file=sys.stderr)
        return 1
    # block 自带 abbrev 行，先删掉原来紧跟 END 标记的那一条，避免重复
    j = end + 1
    while j < len(lines) and not lines[j].strip():
        j += 1
    if j < len(lines) and lines[j].strip().startswith("- abbrev/^([A-Z])"):
        del lines[j]
    lines[start:end + 1] = [block + "\n"]
    open(SCHEMA, "w", encoding="utf-8", newline="\n").write("".join(lines))

    open(KOTLIN, "w", encoding="utf-8", newline="\n").write(
        build_kotlin(syllables, fuzzy_pairs, v_pairs))
    print("已写入 %s / %s（%d 个音节，%d 条 derive，%d 条模糊规则，%d 条 v→u）" %
          (SCHEMA, KOTLIN, len(syllables), block.count("- derive/"),
           len(fuzzy_pairs), len(v_pairs)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
