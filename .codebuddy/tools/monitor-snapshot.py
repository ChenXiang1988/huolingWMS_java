#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""设计基线指纹快照，供每日体检判断「有没有东西变了」。

工作区不是 git 仓库，无法用版本历史判断改动，改用内容哈希：
  --update   生成或更新快照
  --check    与快照比对，有变化退出码 1，无变化 0
  --show     打印当前快照

监护范围：校验脚本、校验规则、四个设计 agent 定义、设计基线、章节规则、
以及 openspec 下所有变更的 design.md。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SNAPSHOT = ROOT / "humandesign" / "检查脚本优化" / "_monitor-snapshot.json"

SYSDESIGN = ROOT / "humandesign" / "sysdesign"
_ptr = SYSDESIGN / "current-version.txt"
VERSION = (_ptr.read_text(encoding="utf-8").strip() if _ptr.exists() else "") or "v2"
ACTIVE = "humandesign/sysdesign/%s" % VERSION

EXPLICIT = [
    ".codebuddy/tools/check-design.py",
    ".codebuddy/rules/design-check/RULE.mdc",
    ".codebuddy/rules/version-policy/RULE.mdc",
    ACTIVE + "/00-决策字典.md",
    ACTIVE + "/00a-红线与不变量.md",
    ACTIVE + "/21-术语与枚举登记表.md",
    ACTIVE + "/22-状态机与事务边界总表.md",
    ACTIVE + "/23-权威源与引用矩阵.md",
    ACTIVE + "/index.md",
    ACTIVE + "/log.md",
    "openspec/config.yaml",
]
PATTERNS = [
    ".codebuddy/agents/*.md",
    "openspec/changes/*/design.md",
    "openspec/changes/*/tasks.md",
    "openspec/changes/*/proposal.md",
]


def collect() -> dict:
    files = []
    for rel in EXPLICIT:
        if (ROOT / rel).is_file():
            files.append(rel)
    for pattern in PATTERNS:
        for p in ROOT.glob(pattern):
            if p.is_file():
                files.append(str(p.relative_to(ROOT)))
    out = {}
    for rel in sorted(set(files)):
        fp = ROOT / rel
        out[rel] = hashlib.sha256(fp.read_bytes()).hexdigest()
    return out


def load() -> dict:
    if not SNAPSHOT.exists():
        return {}
    try:
        data = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}
    return data.get("files", {})


def save(files: dict) -> None:
    SNAPSHOT.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "checked_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "files": files,
    }
    SNAPSHOT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def diff(old: dict, new: dict) -> dict:
    added = sorted(set(new) - set(old))
    removed = sorted(set(old) - set(new))
    changed = sorted(k for k in set(old) & set(new) if old[k] != new[k])
    return {"added": added, "removed": removed, "changed": changed}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="设计基线指纹快照")
    parser.add_argument("--update", action="store_true", help="生成或更新快照")
    parser.add_argument("--check", action="store_true", help="与快照比对")
    parser.add_argument("--show", action="store_true", help="打印当前快照")
    args = parser.parse_args(argv)

    current = collect()

    if args.show:
        print(json.dumps({"files": current}, ensure_ascii=False, indent=2))
        return 0

    old = load()

    if args.update or not old:
        save(current)
        print("SNAPSHOT: UPDATED  %d files -> %s" % (len(current), SNAPSHOT))
        return 0

    result = diff(old, current)
    total = len(result["added"]) + len(result["removed"]) + len(result["changed"])

    if not args.check:
        args.check = True

    if total == 0:
        save(current)  # 刷新时间戳
        print("SNAPSHOT: SAME  受监护的 %d 个文件均无变化" % len(current))
        return 0

    print("SNAPSHOT: CHANGED  %d 处变化" % total)
    for label, key in (("新增", "added"), ("删除", "removed"), ("修改", "changed")):
        for rel in result[key]:
            print("  [%s] %s" % (label, rel))
    if args.check:
        print("\n需要深入分析。分析完成后执行 --update 更新快照。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
