#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""货灵 WMS 代理定义与分派规则校验器。

守住「子代理不得绕过主代理直接向用户要授权」这条约定。它目前只是文字约定：
删掉一段、漏加一段、把禁止语改软，都没有任何机制会响。这里把它翻译成可判定的断言。

覆盖四类：
  1. DISPATCH-01  带 execute_command 的代理，正文必须声明「# 执行边界」段
  2. DISPATCH-02  「执行边界」段内必须逐条出现六条禁止语（缺哪条报哪条）
  3. DISPATCH-03  分派规则必须有「## 六、授权归主代理」节，且五要素齐全
  4. DISPATCH-04  不带 execute_command 的代理不检查执行边界（防误报），只在结尾计数

判定口径：
  - 「带命令权限」的认据是 frontmatter 的 tools 声明里出现 execute_command 这个标识符；
    行内列表（tools: a, b）与 YAML 块列表（tools: 换行缩进 - a）两种写法都认。
  - 段边界按标题层级解析，不按行数截断：从「# 执行边界」起，到下一个单 # 标题为止。
    解析时跳过代码围栏内的行，避免把注释里的「# xxx」当标题。
  - 只认 frontmatter 里的 tools，正文提到 execute_command 不算授权。

用法：
  python3 .codebuddy/tools/check-agent-dispatch.py
  python3 .codebuddy/tools/check-agent-dispatch.py --repo /tmp/fixture
  python3 .codebuddy/tools/check-agent-dispatch.py --json
  python3 .codebuddy/tools/check-agent-dispatch.py --json --strict

退出码：0 全部通过；1 存在 ERROR（--strict 下 WARN 也算失败）；2 定位不到 .codebuddy/agents 目录。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

ROOT = Path(__file__).resolve().parents[2]

AGENTS_REL = Path(".codebuddy") / "agents"
RULE_REL = Path(".codebuddy") / "rules" / "agent-delegation" / "RULE.mdc"

# 命令执行权限的标识符：用词边界匹配，避免 run_execute_command 这类名字被误判成有权限
COMMAND_TOOL = "execute_command"
COMMAND_TOOL_RE = re.compile(r"\b" + re.escape(COMMAND_TOOL) + r"\b")

BOUNDARY_TITLE = "执行边界"
# DISPATCH-02 的六条禁止语：前三条要求「不找用户、不绕路、找主代理」，
# 后三条是越界命令的典型代表（删文件 / 清端口 / 装依赖），去掉任何一条都等于把口子放开。
BOUNDARY_KEYWORDS = (
    "不得向用户弹授权确认",
    "不得换一种方式自行绕过",
    "需主代理执行",
    "删文件",
    "清端口",
    "装依赖",
)

RULE_SECTION_TITLE = "授权归主代理"
RULE_SECTION_HEADING = "## 六、授权归主代理"
# DISPATCH-03 的五要素：派发带什么（权限模式 / 命令白名单 / 越界处置）、
# 派发前想什么（派发前自检三问）、交付时登记什么（收口登记）。
RULE_KEYWORDS = (
    "权限模式",
    "命令白名单",
    "越界处置",
    "派发前自检三问",
    "收口登记",
)

FENCE_RE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
TOOLS_LINE_RE = re.compile(r"^tools\s*:\s*(.*)$")
LIST_ITEM_RE = re.compile(r"^-\s*(.+?)\s*$")


@dataclass
class Issue:
    level: str  # ERROR / WARN
    code: str
    target: str  # 相对仓库根的文件路径
    message: str
    line: Optional[int] = None

    def render(self) -> str:
        """输出形态固定为「ERROR <编号> <文件>: <原因>」，行号并进原因里。"""
        reason = self.message
        if self.line is not None:
            reason = "%s（第 %d 行）" % (reason, self.line)
        return "%-5s %-11s %s: %s" % (self.level, self.code, self.target, reason)


class Report:
    def __init__(self) -> None:
        self.issues: List[Issue] = []

    def add(self, level: str, code: str, target: str, message: str,
            line: Optional[int] = None) -> None:
        self.issues.append(Issue(level, code, target, message, line))

    def error(self, code: str, target: str, message: str, line: Optional[int] = None) -> None:
        self.add("ERROR", code, target, message, line)

    def warn(self, code: str, target: str, message: str, line: Optional[int] = None) -> None:
        self.add("WARN", code, target, message, line)

    def count(self, level: str) -> int:
        return sum(1 for i in self.issues if i.level == level)

    def sorted_issues(self) -> List[Issue]:
        return sorted(self.issues, key=lambda i: (i.code, i.target, i.line or 0))


# ---------------------------------------------------------------- 解析工具

def read_lines(path: Path) -> List[str]:
    """按 UTF-8 读入并按行切分；splitlines 同时兼容 LF 与 CRLF，中文与全角标点原样保留。"""
    return path.read_text(encoding="utf-8").splitlines()


def parse_frontmatter(lines: List[str]) -> Tuple[List[str], int]:
    """取出 frontmatter 行与正文起始行号。

    只认开头第一行是 --- 的块，正文里出现 tools: 不当作权限声明。
    未闭合或不存在时返回空列表，由调用方按「没有声明工具」处理。
    """
    start = 0
    while start < len(lines) and not lines[start].strip():
        start += 1
    if start >= len(lines) or lines[start].strip() != "---":
        return [], 0
    for idx in range(start + 1, len(lines)):
        if lines[idx].strip() in ("---", "..."):
            return lines[start + 1:idx], idx + 1
    return [], 0


def extract_tools(fm_lines: List[str]) -> str:
    """把 frontmatter 里 tools 的取值拼成一个字符串，两种 YAML 写法都认。"""
    for idx, line in enumerate(fm_lines):
        m = TOOLS_LINE_RE.match(line.strip())
        if not m:
            continue
        inline = m.group(1).strip()
        if inline:
            return inline
        # tools: 后面换行，取值写成缩进列表
        items: List[str] = []
        for nxt in fm_lines[idx + 1:]:
            s = nxt.strip()
            if not s:
                continue
            item = LIST_ITEM_RE.match(s)
            if not item:
                break
            items.append(item.group(1))
        return ", ".join(items)
    return ""


def iter_headings(lines: List[str]) -> List[Tuple[int, int, str]]:
    """产出 (行索引, 级别, 标题文本)。跳过代码围栏内的行，避免把 shell 注释当标题。"""
    out: List[Tuple[int, int, str]] = []
    fence: Optional[str] = None
    for idx, line in enumerate(lines):
        fm = FENCE_RE.match(line)
        if fm:
            token = fm.group(1)
            if fence is None:
                fence = token[0]
            elif token[0] == fence:
                fence = None
            continue
        if fence is not None:
            continue
        hm = HEADING_RE.match(line)
        if hm:
            out.append((idx, len(hm.group(1)), hm.group(2).strip()))
    return out


def section_end(heads: List[Tuple[int, int, str]], pos: int, max_level: int,
                total_lines: int) -> int:
    """从 pos 之后找第一个级别 <= max_level 的标题作为段尾；没有就到文末。"""
    for idx, level, _title in heads[pos + 1:]:
        if level <= max_level:
            return idx
    return total_lines


def find_heading(heads: List[Tuple[int, int, str]], level: int, keyword: str) -> Optional[int]:
    for pos, (_idx, lv, title) in enumerate(heads):
        if lv == level and keyword in title:
            return pos
    return None


# ---------------------------------------------------------------- DISPATCH-01 / 02

def check_agents(repo: Path, report: Report) -> Dict[str, object]:
    """逐个代理定义判「有没有权限声明」与「执行边界段是否齐全」。

    不带 execute_command 的文件一律跳过段检查（DISPATCH-04），
    否则纯只读代理会被误报成缺段，门禁就变成噪声了。
    """
    agents_dir = repo / AGENTS_REL
    files = sorted(p for p in agents_dir.glob("*.md") if p.is_file())

    with_cmd: List[str] = []
    without_cmd: List[str] = []
    no_tools_line: List[str] = []

    for path in files:
        rel = path.relative_to(repo).as_posix()
        try:
            lines = read_lines(path)
        except (OSError, UnicodeDecodeError) as exc:
            report.error("INTERNAL", rel, "无法按 UTF-8 读取代理定义：%s" % exc)
            continue

        fm_lines, _body_start = parse_frontmatter(lines)
        tools = extract_tools(fm_lines)
        if not tools:
            no_tools_line.append(rel)

        if not COMMAND_TOOL_RE.search(tools):
            without_cmd.append(rel)
            continue

        with_cmd.append(rel)
        heads = iter_headings(lines)
        pos = find_heading(heads, 1, BOUNDARY_TITLE)
        if pos is None:
            report.error(
                "DISPATCH-01",
                rel,
                "声明了 %s 但正文没有「# %s」段；子代理会绕过主代理直接向用户要授权"
                "（注意必须是单个 # 的一级标题，## 不算）" % (COMMAND_TOOL, BOUNDARY_TITLE),
            )
            continue

        start = heads[pos][0]
        end = section_end(heads, pos, 1, len(lines))
        body = "\n".join(lines[start:end])
        missing = [kw for kw in BOUNDARY_KEYWORDS if kw not in body]
        if missing:
            report.error(
                "DISPATCH-02",
                rel,
                "「%s」段缺少禁止语：%s" % (
                    BOUNDARY_TITLE,
                    "、".join("「%s」" % kw for kw in missing),
                ),
                start + 1,
            )

    return {
        "total": len(files),
        "with_command": with_cmd,
        "without_command": without_cmd,
        "no_tools_line": no_tools_line,
    }


# ---------------------------------------------------------------- DISPATCH-03

def check_rule(repo: Path, report: Report) -> Dict[str, object]:
    """分派规则第六节必须存在且五要素齐全。

    节的识别用关键词「授权归主代理」匹配二级标题，编号前缀「六、」不参与匹配：
    插一节导致序号顺延不该算违规，但整节删掉必须报出来。
    """
    path = repo / RULE_REL
    rel = RULE_REL.as_posix()
    info: Dict[str, object] = {"path": rel, "section_present": False, "missing": []}

    if not path.is_file():
        report.error(
            "DISPATCH-03",
            rel,
            "分派规则文件不存在，缺少「%s」节；子代理的授权出口无人定义" % RULE_SECTION_HEADING,
        )
        info["missing"] = list(RULE_KEYWORDS)
        return info

    try:
        lines = read_lines(path)
    except (OSError, UnicodeDecodeError) as exc:
        report.error("INTERNAL", rel, "无法按 UTF-8 读取规则文件：%s" % exc)
        info["missing"] = list(RULE_KEYWORDS)
        return info

    heads = iter_headings(lines)
    pos = find_heading(heads, 2, RULE_SECTION_TITLE)
    if pos is None:
        report.error(
            "DISPATCH-03",
            rel,
            "缺少「%s」节；授权归主代理的约束一旦整节删掉，派发规则就只剩谁来写代码" % RULE_SECTION_HEADING,
        )
        info["missing"] = list(RULE_KEYWORDS)
        return info

    info["section_present"] = True
    start = heads[pos][0]
    end = section_end(heads, pos, 2, len(lines))
    body = "\n".join(lines[start:end])
    missing = [kw for kw in RULE_KEYWORDS if kw not in body]
    if missing:
        info["missing"] = missing
        report.error(
            "DISPATCH-03",
            rel,
            "「%s」节缺少要素：%s" % (
                RULE_SECTION_HEADING,
                "、".join("「%s」" % kw for kw in missing),
            ),
            start + 1,
        )
    return info


# ---------------------------------------------------------------- 输出

def emit_text(report: Report, agents: Dict[str, object], rule: Dict[str, object]) -> None:
    with_cmd = agents["with_command"]
    without_cmd = agents["without_command"]

    print("▶ 代理定义  %s（DISPATCH-01 / DISPATCH-02）" % AGENTS_REL.as_posix())
    print("  文件 %d 个：带命令权限 %d 个 / 不带 %d 个（DISPATCH-04）"
          % (agents["total"], len(with_cmd), len(without_cmd)))
    if with_cmd:
        print("  带 %s：%s" % (COMMAND_TOOL, "、".join(Path(r).stem for r in with_cmd)))
    if without_cmd:
        print("  不带（跳过执行边界检查）：%s" % "、".join(Path(r).stem for r in without_cmd))
    if agents["no_tools_line"]:
        print("  frontmatter 未声明 tools：%s" % "、".join(agents["no_tools_line"]))

    print("▶ 分派规则  %s（DISPATCH-03）" % rule["path"])
    if rule["section_present"]:
        print("  「%s」节存在，五要素齐全" % RULE_SECTION_HEADING)
    else:
        print("  「%s」节缺失" % RULE_SECTION_HEADING)

    print("")
    if report.issues:
        for issue in report.sorted_issues():
            print(issue.render())
    else:
        print("无问题。")
    print("")
    print("ERROR %d   WARN %d" % (report.count("ERROR"), report.count("WARN")))


def emit_json(report: Report, agents: Dict[str, object], rule: Dict[str, object]) -> None:
    print(json.dumps({
        "status": "FAIL" if report.count("ERROR") else ("FAIL" if report.count("WARN") else "OK"),
        "summary": {
            "error": report.count("ERROR"),
            "warn": report.count("WARN"),
        },
        "agents": agents,
        "rule": rule,
        "issues": [
            {
                "level": i.level,
                "code": i.code,
                "file": i.target,
                "line": i.line,
                "message": i.message,
            }
            for i in report.sorted_issues()
        ],
    }, ensure_ascii=False, indent=2))


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="货灵 WMS 代理定义与分派规则校验器")
    parser.add_argument("--repo", help="仓库根路径，默认按脚本位置上溯两级")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--strict", action="store_true", help="WARN 也视为失败")
    args = parser.parse_args(argv)

    repo = Path(args.repo).expanduser().resolve() if args.repo else ROOT
    agents_dir = repo / AGENTS_REL
    if not agents_dir.is_dir():
        msg = "定位不到代理定义目录：%s" % agents_dir
        if args.json:
            print(json.dumps({"status": "NEED_TARGET", "message": msg}, ensure_ascii=False))
        else:
            print(msg, file=sys.stderr)
        return 2

    report = Report()
    agents = check_agents(repo, report)
    rule = check_rule(repo, report)

    if args.json:
        emit_json(report, agents, rule)
    else:
        emit_text(report, agents, rule)

    if report.count("ERROR"):
        return 1
    if args.strict and report.count("WARN"):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
