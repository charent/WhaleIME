# -*- coding: utf-8 -*-
"""检查 query_console 输出里候选的「comment 段数」分布。

用法：python inspect-candidates.py out.txt [输入串]
不带输入串时分析全部用例。用于排查「某个分段的候选有没有被生成、
是不是只排在很后面」这类问题。
"""
import io
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

INPUT_RE = re.compile(r"^input: (\S+)")
CAND_RE = re.compile(r"^\d+\. (\S+)\s+\[(.*)\]\s*$")


def main():
    path = sys.argv[1]
    only = sys.argv[2] if len(sys.argv) > 2 else None
    current = None
    stats = {}
    order = []
    with io.open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            m = INPUT_RE.match(line)
            if m:
                current = m.group(1)
                stats.setdefault(current, {"one": [], "multi": 0})
                order.append(current)
                continue
            m = CAND_RE.match(line)
            if m and current is not None:
                text, comment = m.group(1), m.group(2)
                if "'" not in comment:
                    stats[current]["one"].append((text, comment))
                else:
                    stats[current]["multi"] += 1
    for key in order:
        if only and key != only:
            continue
        item = stats[key]
        print("%s: single-segment=%d, multi-segment=%d"
              % (key, len(item["one"]), item["multi"]))
        for text, comment in item["one"][:20]:
            print("    %s  [%s]" % (text, comment))


if __name__ == "__main__":
    main()
