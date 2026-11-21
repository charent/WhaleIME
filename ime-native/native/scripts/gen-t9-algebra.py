#!/usr/bin/env python
"""生成 t9_pinyin.schema.yaml 的 speller/algebra 键码映射段。

背景
----
九键键盘送进引擎的是「按键代表字母」串（94328426 → WGDATGAM），
而 prism 里存的是音节拼写。要让两者对得上，schema 必须用 spelling algebra
把音节映射成键码：

    zhe → WGD、xie → WGD（同键码多音节一对多）
    bu  → AT、hao → GAM

为什么用 derive 而不是 xlit
--------------------------
librime 的 `xlit` 是「就地改写」拼写（双拼就是这么用的，改完只剩双拼码，
全拼不再存在）。九键需要两套拼写并存：

  * 键码（大写）：键盘直接送进来的形式；
  * 全拼（小写）：候选栏拼音栏点选具体读音后，客户端会用
    `Rime.replaceKey()` 把键码段换成小写拼音（zhe'bu），此时引擎必须
    还能用全拼查到音节图，否则点完拼音就没有候选了。

`derive` 是「从当前拼写派生一条新的、并保留原本」，正好符合要求 ——
每个音节既保留 zhe，又派生 WGD。

用法
----
    python gen-t9-algebra.py            # 打印 algebra 段
    python gen-t9-algebra.py --write    # 直接改写 schema 里的 GENERATED 段

音节表取自 assets/rime/build/pinyin.prism.bin（与词典同源的 414 个音节）。
每次词库音节表变动后重跑一次。
"""

import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PRISM = os.path.abspath(
    os.path.join(HERE, "..", "..", "assets", "rime", "build", "pinyin.prism.bin"))
SCHEMA = os.path.abspath(
    os.path.join(HERE, "..", "..", "assets", "rime", "t9_pinyin.schema.yaml"))

# 九键键位（与 Kotlin 侧 T9PinYinUtils.t9KeyMap 必须一致）：
# 2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz
T9_KEYS = [
    ("A", "abc"),
    ("D", "def"),
    ("G", "ghi"),
    ("J", "jkl"),
    ("M", "mno"),
    ("P", "pqrs"),
    ("T", "tuv"),
    ("W", "wxyz"),
]
CHAR_TO_KEY = {c: key for key, chars in T9_KEYS for c in chars}


def load_syllables(path):
    """从 prism 里还原 syllable id -> 音节拼写。"""
    data = open(path, "rb").read()
    _dc, _sc, _ns, _nsp, _das = struct.unpack("<IIIII", data[32:52])
    da_off, sp_off = struct.unpack("<II", data[52:60])
    units = list(struct.unpack("<%dI" % _das,
                               data[52 + da_off:52 + da_off + _das * 4]))

    def unit_off(u):
        return (u >> 10) << ((u & 0x200) >> 6)

    keys = []

    def walk(pos, pref):
        u = units[pos]
        off = unit_off(u)
        if (u >> 8) & 1:
            leaf = pos ^ off
            if 0 <= leaf < len(units):
                keys.append((pref, units[leaf] & 0x7FFFFFFF))
        base = pos ^ off
        for c in range(1, 256):
            child = base ^ c
            if 0 <= child < len(units) and (units[child] & 0xFF) == c:
                walk(child, pref + bytes([c]))

    walk(0, b"")

    sm = 56 + sp_off  # SpellingMap 的 OffsetPtr 位于 metadata 偏移 56

    def descriptors(spelling_id):
        size = struct.unpack("<I", data[sm:sm + 4])[0]
        if spelling_id >= size:
            return []
        lo = sm + 4 + spelling_id * 8
        count, at = struct.unpack("<II", data[lo:lo + 8])
        base = lo + 4 + at
        return [struct.unpack("<i", data[base + i * 16:base + i * 16 + 4])[0]
                for i in range(count)]

    candidates = {}
    for key, spelling_id in keys:
        text = key.decode("latin1")
        # 只要全拼（纯小写字母）；abbrev 派生出来的单字母拼写（z/zh…）也是
        # 小写，所以对同一 syllable id 取**最长**的那条作为音节本身。
        if not text.isalpha() or not text.islower():
            continue
        for sid in descriptors(spelling_id):
            if len(text) > len(candidates.get(sid, "")):
                candidates[sid] = text
    return [candidates[k] for k in sorted(candidates)]


def build_algebra(syllables):
    """按键码分组，输出 derive 规则：derive/^(xie|zhe)$/WGD/"""
    by_key = {}
    for syllable in syllables:
        key = "".join(CHAR_TO_KEY[c] for c in syllable)
        by_key.setdefault(key, []).append(syllable)

    lines = ["  algebra:",
             "    # ==== GENERATED: 九键键码映射（gen-t9-algebra.py 生成，勿手改）====",
             "    # 保留全拼、另派生键码；候选栏点选具体读音后 replaceKey 成小写拼音",
             "    # 仍能查到音节图。键码与 T9PinYinUtils.t9KeyMap 一一对应。"]
    for key in sorted(by_key):
        spellings = by_key[key]
        if len(spellings) == 1:
            lines.append("    - derive/^%s$/%s/" % (spellings[0], key))
        else:
            lines.append("    - derive/^(%s)$/%s/" % ("|".join(spellings), key))
    lines.append("    # ==== /GENERATED ====")
    # 单键/前缀出候选：敲 9 就要看到 w/x/y/z 开头的字（abbrev 拼写，
    # 可信度 log(0.5)，排在整码之后）。只匹配键码（大写开头），不影响全拼。
    lines.append("    - abbrev/^([A-Z]).+$/$1/")
    return "\n".join(lines)


def main():
    syllables = load_syllables(PRISM)
    if len(syllables) < 400:
        print("音节表异常：只读到 %d 个音节" % len(syllables), file=sys.stderr)
        return 1
    block = build_algebra(syllables)
    if "--write" not in sys.argv:
        print(block)
        return 0

    text = open(SCHEMA, encoding="utf-8").read()
    section = ("speller:\n"
               "  alphabet: \"zyxwvutsrqponmlkjihgfedcba"
               "ZYXWVUTSRQPONMLKJIHGFEDCBA`/\"\n"
               "  delimiter: \"''\"\n" + block + "\n")
    new_text, n = re.subn(r"speller:\n(?:[ \t].*\n|\n)*?(?=\S)", section, text,
                          count=1)
    if n != 1:
        print("没有找到 speller 段，未写入", file=sys.stderr)
        return 1
    open(SCHEMA, "w", encoding="utf-8", newline="\n").write(new_text)
    print("已写入 %s（%d 个音节，%d 条 derive）" %
          (SCHEMA, len(syllables), block.count("- derive/")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
