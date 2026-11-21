# -*- coding: utf-8 -*-
"""统计「单键误按」语料里期望词的召回率。

用法：
  python eval-typo-recall.py out-typo.txt [in-typo.txt.expect]

输入：query_console 的输出（`input: xxx | preedit: ...` + `1. 词  [注释]`），
     以及 gen-typo-corpus.py 生成的「误按串 + 期望词」对照表。
输出：cases / top1 / top5 / top10 / missing 五个计数。调候选排序时把它当回归指标。
"""
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

INPUT_RE = re.compile(r"^input: (\S+)")
CAND_RE = re.compile(r"^\d+\. (\S+)")


def parse_runs(path):
    """解析成 {输入串: [候选文本...]}，与 query_console 的输出格式严格对应。"""
    runs = {}
    current = None
    with io.open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            m = INPUT_RE.match(line)
            if m:
                current = m.group(1)
                runs[current] = []
                continue
            m = CAND_RE.match(line)
            if m and current is not None:
                runs[current].append(m.group(1))
    return runs


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    run_file = sys.argv[1]
    expect_file = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        here, "in-typo.txt.expect")
    expect = {}
    with io.open(expect_file, encoding="utf-8") as f:
        for line in f:
            typo, word = line.rstrip("\n").split("\t")
            expect[typo] = word.rstrip("?")
    runs = parse_runs(run_file)
    total = top1 = top5 = top10 = missing = 0
    for typo, word in expect.items():
        total += 1
        cands = runs.get(typo) or []
        try:
            rank = cands.index(word) + 1
        except ValueError:
            missing += 1
            continue
        if rank == 1:
            top1 += 1
        if rank <= 5:
            top5 += 1
        if rank <= 10:
            top10 += 1
    print("cases=%d top1=%d top5=%d top10=%d missing=%d"
          % (total, top1, top5, top10, missing))


if __name__ == "__main__":
    main()
