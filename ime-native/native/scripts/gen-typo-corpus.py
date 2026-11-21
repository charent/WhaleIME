# -*- coding: utf-8 -*-
"""从 eval-corpus-pinyin.tsv 生成「单键误按」语料，用于评估纠错召回。

做法：把每个输入串的最后一个字母换成它的**键盘邻键**（邻键表与
../../scripts/rime-corrector-whale.cc 的 keyboard_map 一致），得到一个"打错一个键"
的输入，期望词保持不变。跑 query_console 后统计期望词还在不在前 N 条，
就能量化"纠错候选有没有被简拼/预测词淹没"。

用法：
  python gen-typo-corpus.py [输出文件]        # 默认 in-typo.txt
输出：
  <输出文件>           每行一个误按串（首行 #schema pinyin）
  <输出文件>.expect   误按串 \\t 期望词
"""
import io
import os
import sys

KEYBOARD = {
    "q": "wa",
    "w": "qeas",
    "e": "wrsd",
    "r": "etdf",
    "t": "ryfg",
    "y": "tugh",
    "u": "yihj",
    "i": "uojk",
    "o": "ipkl",
    "p": "ol",
    "a": "sqwzx",
    "s": "adwezx",
    "d": "sferxc",
    "f": "dgrtcv",
    "g": "fhtyvb",
    "h": "gjyubn",
    "j": "hkuinm",
    "k": "jliom",
    "l": "kop",
    "z": "xas",
    "x": "zcsd",
    "c": "xvdf",
    "v": "cbfg",
    "b": "vngh",
    "n": "bmhj",
    "m": "njk",
}


def typo_of(key):
    if len(key) < 2 or not key.isalpha():
        return None
    for ch in KEYBOARD.get(key[-1], ""):
        if ch in key:
            continue
        return key[:-1] + ch
    return None


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "in-typo.txt")
    lines = []
    with io.open(os.path.join(here, "eval-corpus-pinyin.tsv"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            typo = typo_of(parts[0])
            if typo:
                lines.append((typo, parts[4]))
    with io.open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("#schema pinyin\n")
        f.write("\n".join(t for t, _ in lines))
        f.write("\n")
    with io.open(out + ".expect", "w", encoding="utf-8", newline="\n") as f:
        for typo, expect in lines:
            f.write("%s\t%s\n" % (typo, expect))
    print("wrote %s (%d cases)" % (out, len(lines)))


if __name__ == "__main__":
    main()
