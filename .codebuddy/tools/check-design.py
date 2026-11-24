#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""货灵 WMS 设计文档校验器。

对 openspec/changes/<change>/design.md 做机器可判定的检查，覆盖五类：
  1. 结构   三章是否齐全、顺序是否正确、占位是否残留
  2. 编号   API / INV / D 编号是否存在、是否有孤儿引用
  3. 禁用项 红线词、越界内容（契约写表名、前端写 SQL 等）
  4. 特征词 把 00 的硬决策翻译成必须搜到的字符串
  5. 跨章对账 契约 ↔ 后端 ↔ 前端 ↔ 任务 的引用闭合

用法：
  python3 .codebuddy/tools/check-design.py
  python3 .codebuddy/tools/check-design.py <change-name>
  python3 .codebuddy/tools/check-design.py --change-dir /tmp/x
  python3 .codebuddy/tools/check-design.py --design path/to/design.md
  python3 .codebuddy/tools/check-design.py --init <change-name>
  python3 .codebuddy/tools/check-design.py --list-checks
  python3 .codebuddy/tools/check-design.py --json --strict

退出码：0 通过；1 存在 ERROR（--strict 下 WARN 也算失败）；2 无法定位检查对象。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional

ROOT = Path(__file__).resolve().parents[2]
BASELINE_DECISIONS = ROOT / "humandesign" / "sysdesign" / "00-decisions.md"
CHANGES_DIR = ROOT / "openspec" / "changes"

# key, 显示名, 标题识别关键词
CHAPTERS = [
    ("front", "前端设计", ("前端设计",)),
    ("backend", "后端设计", ("后端设计",)),
    ("api", "API 接口契约", ("接口契约",)),
]
CHAPTER_ORDER = [c[0] for c in CHAPTERS]
CHAPTER_LABEL = {c[0]: c[1] for c in CHAPTERS}

# ---------------------------------------------------------------- 基础结构

HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
API_HEAD_RE = re.compile(r"^(#{2,6})\s+(API-\d{3})\b[ \t]*(.*)$")
API_REF_RE = re.compile(r"\bAPI-(\d{3})\b")
INV_REF_RE = re.compile(r"\bINV-(\d{2})\b")
DEC_REF_RE = re.compile(r"\bD(\d{2})\b")
FLYWAY_RE = re.compile(r"\bV(\d+)(?:\.\d+)?__[A-Za-z0-9_]+")
ERRCODE_RE = re.compile(r"\b([A-Z][A-Z0-9]{2,}(?:_[A-Z0-9]+)+)\b")

EXEMPT_WORDS = ("禁止", "不得", "不允许", "严禁", "不含", "不要", "without", "avoid")


@dataclass
class Issue:
    level: str  # ERROR / WARN / INFO
    code: str
    msg: str
    line: Optional[int] = None
    source: str = ""  # 归属文档，多文档校验时分段输出


@dataclass
class Section:
    key: str
    start: int  # 标题行索引（0-based）
    end: int  # 结束行索引（exclusive）
    heading_level: int = 2


class Report:
    def __init__(self) -> None:
        self.issues: List[Issue] = []
        self.checked: List[str] = []  # 已检查文档，用于输出时显示「无问题」

    def add(self, level: str, code: str, msg: str, line: Optional[int] = None,
            source: str = "") -> None:
        self.issues.append(Issue(level, code, msg, line, source))

    def error(self, code: str, msg: str, line: Optional[int] = None, source: str = "") -> None:
        self.add("ERROR", code, msg, line, source)

    def warn(self, code: str, msg: str, line: Optional[int] = None, source: str = "") -> None:
        self.add("WARN", code, msg, line, source)

    def info(self, code: str, msg: str, line: Optional[int] = None, source: str = "") -> None:
        self.add("INFO", code, msg, line, source)


class Ctx:
    def __init__(self, design_path: Path, lines: List[str], sections: Dict[str, Section],
                 inv_set: set, dec_set: set, change_dir: Optional[Path]) -> None:
        self.path = design_path
        self.lines = lines
        self.sections = sections
        self.inv_set = inv_set
        self.dec_set = dec_set
        self.change_dir = change_dir
        self.api_entries: List[dict] = []
        self.api_ids: List[str] = []
        self.pending: set = set()  # 仍是占位未填写的章


# ---------------------------------------------------------------- 解析工具

def split_row(line: str) -> List[str]:
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [c.strip() for c in s.split("|")]


def parse_tables(lines: List[str], start: int, end: int) -> List[dict]:
    tables = []
    i = start
    while i < end:
        if lines[i].lstrip().startswith("|"):
            block = []
            j = i
            while j < end and lines[j].lstrip().startswith("|"):
                block.append((j, lines[j]))
                j += 1
            if len(block) >= 2:
                tables.append({
                    "line": block[0][0],
                    "header": split_row(block[0][1]),
                    "rows": [(ln, split_row(t)) for ln, t in block[2:]],
                })
            i = j
        else:
            i += 1
    return tables


def section_text(ctx: Ctx, key: str) -> str:
    sec = ctx.sections.get(key)
    if not sec:
        return ""
    return "\n".join(ctx.lines[sec.start + 1:sec.end])


def section_lines(ctx: Ctx, key: str) -> List[int]:
    sec = ctx.sections.get(key)
    if not sec:
        return []
    return list(range(sec.start + 1, sec.end))


def is_exempt(line: str) -> bool:
    return any(w in line for w in EXEMPT_WORDS)


def split_sections(lines: List[str], report: Report) -> Dict[str, Section]:
    marks: List[tuple] = []
    for idx, line in enumerate(lines):
        m = HEADING_RE.match(line)
        if not m:
            continue
        title = m.group(2)
        for key, _label, kws in CHAPTERS:
            if any(kw in title for kw in kws):
                marks.append((idx, key, len(m.group(1))))
                break

    seen: Dict[str, int] = {}
    level: Dict[str, int] = {}
    for idx, key, lv in marks:
        if key in seen:
            report.warn("STRUCT-06", "章节「%s」标题重复出现，请只保留一处" % CHAPTER_LABEL[key], idx + 1)
            continue
        seen[key] = idx
        level[key] = lv

    ordered = sorted(seen.items(), key=lambda kv: kv[1])
    sections: Dict[str, Section] = {}
    for i, (key, start) in enumerate(ordered):
        end = ordered[i + 1][1] if i + 1 < len(ordered) else len(lines)
        sections[key] = Section(key, start, end, level[key])
    return sections


def load_baseline(report: Report) -> tuple:
    if not BASELINE_DECISIONS.exists():
        report.warn("BASE-01", "未找到决策基线 %s，编号合法性检查降级为跳过" % BASELINE_DECISIONS)
        return set(), set()
    text = BASELINE_DECISIONS.read_text(encoding="utf-8")
    inv_set = set("INV-" + m for m in INV_REF_RE.findall(text))
    dec_set = set()
    for m in re.finditer(r"^###\s+D(\d{2})\b", text, re.MULTILINE):
        dec_set.add("D" + m.group(1))
    return inv_set, dec_set


def extract_api_entries(ctx: Ctx, report: Report) -> None:
    sec = ctx.sections.get("api")
    if not sec:
        return
    entries: List[dict] = []
    cur: Optional[dict] = None
    for idx in range(sec.start + 1, sec.end):
        m = API_HEAD_RE.match(ctx.lines[idx])
        if m:
            if cur:
                cur["end"] = idx
                entries.append(cur)
            cur = {
                "id": m.group(2),
                "line": idx + 1,
                "title": m.group(3).strip(),
                "start": idx,
                "end": sec.end,
                "body": [],
                "table_mode": False,
            }
        elif cur is not None:
            cur["body"].append(ctx.lines[idx])
    if cur:
        cur["end"] = sec.end
        entries.append(cur)

    if not entries:
        for t in parse_tables(ctx.lines, sec.start + 1, sec.end):
            hdr = t["header"]
            idx_col = next((i for i, h in enumerate(hdr) if "编号" in h), None)
            if idx_col is None:
                continue
            for ln, row in t["rows"]:
                if idx_col >= len(row):
                    continue
                m = re.match(r"(API-\d{3})", row[idx_col])
                if m:
                    entries.append({
                        "id": m.group(1),
                        "line": ln + 1,
                        "title": " / ".join(c for c in row if c)[:60],
                        "start": ln,
                        "end": ln + 1,
                        "body": [],
                        "table_mode": True,
                    })
        if entries:
            report.warn("API-10", "接口条目以表格定义，建议统一改为「### API-NNN 动作名」标题，便于定位与解析")

    ctx.api_entries = entries
    ctx.api_ids = [e["id"] for e in entries]


def find_table(ctx: Ctx, key: str, must_contain: tuple) -> List[dict]:
    """在指定章内找表头同时含 must_contain 全部关键词的表格。"""
    sec = ctx.sections.get(key)
    if not sec:
        return []
    out = []
    for t in parse_tables(ctx.lines, sec.start + 1, sec.end):
        joined = " ".join(t["header"])
        if all(k in joined for k in must_contain):
            out.append(t)
    return out


# ---------------------------------------------------------------- 结构检查

def check_structure(ctx: Ctx, r: Report) -> None:
    missing = [k for k in CHAPTER_ORDER if k not in ctx.sections]
    for k in missing:
        r.error("STRUCT-02", "缺少一级章节「%s」" % CHAPTER_LABEL[k], 1)

    present = [(k, ctx.sections[k].start) for k in CHAPTER_ORDER if k in ctx.sections]
    present.sort(key=lambda kv: kv[1])
    actual = [k for k, _ in present]
    expected = [k for k in CHAPTER_ORDER if k in ctx.sections]
    if actual != expected:
        r.error("STRUCT-03", "章节顺序错误，应为 前端设计 → 后端设计 → API 接口契约，实际为 %s"
                % " → ".join(CHAPTER_LABEL[k] for k in actual))

    covered = set()
    for k, sec in ctx.sections.items():
        body_idx = [i for i in range(sec.start + 1, sec.end) if ctx.lines[i].strip()]
        if not body_idx:
            r.error("STRUCT-04", "章节「%s」正文为空" % CHAPTER_LABEL[k], sec.start + 1)
            ctx.pending.add(k)
        elif len(body_idx) == 1 and "_待补充_" in ctx.lines[body_idx[0]]:
            r.error("STRUCT-05", "章节「%s」仍为占位未填写" % CHAPTER_LABEL[k], sec.start + 1)
            ctx.pending.add(k)
            covered.add(body_idx[0])

    for i, line in enumerate(ctx.lines):
        if "_待补充_" in line and i not in covered:
            r.error("STRUCT-07", "残留占位符「_待补充_」", i + 1)


def check_structure_extra(ctx: Ctx, r: Report) -> None:
    """章标题层级必须比 H1 深，且三章同级。"""
    secs = list(ctx.sections.values())
    if not secs:
        return
    levels = {s.heading_level for s in secs}
    if len(levels) > 1:
        r.error("STRUCT-08", "三个章节标题层级不一致：%s"
                % "、".join("%s=H%d" % (CHAPTER_LABEL[s.key], s.heading_level) for s in secs),
                secs[0].start + 1)


# ---------------------------------------------------------------- 编号检查

def check_api_ids(ctx: Ctx, r: Report) -> None:
    if not ctx.api_entries:
        if "api" in ctx.sections:
            r.error("API-01", "接口契约章未发现任何接口条目（约定格式：### API-001 动作名）")
        return

    seen: Dict[str, int] = {}
    for e in ctx.api_entries:
        if e["id"] in seen:
            r.error("API-02", "接口编号 %s 重复定义" % e["id"], e["line"])
        seen[e["id"]] = e["line"]

    nums = sorted(int(i.split("-")[1]) for i in set(ctx.api_ids))
    if nums:
        expect = list(range(min(nums), max(nums) + 1))
        gaps = [n for n in expect if n not in nums]
        if gaps:
            r.warn("API-03", "接口编号不连续，缺号：%s"
                   % "、".join("API-%03d" % n for n in gaps))


def check_api_reference_closure(ctx: Ctx, r: Report) -> None:
    defined = set(ctx.api_ids)
    if not defined:
        return
    for idx, line in enumerate(ctx.lines):
        heads = API_HEAD_RE.match(line)
        for ref in API_REF_RE.findall(line):
            full = "API-" + ref
            if full in defined:
                continue
            # 接口定义行本身不算引用
            if heads and heads.group(2) == full:
                continue
            r.error("API-04", "引用了未定义的接口编号 %s" % full, idx + 1)


def check_api_entry_completeness(ctx: Ctx, r: Report) -> None:
    REQUIRED = [
        ("方法与路径", ("路径", "GET ", "POST ", "PUT ", "PATCH ", "DELETE ")),
        ("端与角色", ("端", "角色")),
        ("鉴权", ("鉴权", "权限")),
        ("请求", ("请求",)),
        ("响应", ("响应",)),
        ("业务错误码", ("错误码",)),
        ("幂等", ("幂等",)),
        ("关联不变量", ("INV-",)),
    ]
    for e in ctx.api_entries:
        if e["table_mode"]:
            continue
        body = "\n".join(e["body"])
        absent = [name for name, kws in REQUIRED if not any(k in body for k in kws)]
        if absent:
            r.error("API-05", "接口 %s 缺少：%s" % (e["id"], "、".join(absent)), e["line"])


def check_write_api_idempotent(ctx: Ctx, r: Report) -> None:
    for e in ctx.api_entries:
        if e["table_mode"]:
            continue
        body = "\n".join(e["body"])
        is_write = any(m in body for m in ("POST ", "PUT ", "PATCH ", "DELETE "))
        if not is_write:
            continue
        if "幂等" not in body and "外部单号" not in body:
            r.error("API-06", "写接口 %s 未写幂等键与重复提交行为（D21）" % e["id"], e["line"])


def check_contract_common(ctx: Ctx, r: Report) -> None:
    if "api" not in ctx.sections:
        return
    text = section_text(ctx, "api")
    REQUIRED = [
        ("基础路径与端分组", ("基础路径", "路径前缀", "/api")),
        ("认证方式", ("认证", "token", "Token")),
        ("错误响应结构", ("错误响应", "code", "traceId")),
        ("幂等约定", ("幂等",)),
        ("分页约定", ("分页",)),
        ("时间与金额口径", ("时间", "金额")),
        ("枚举约定", ("枚举",)),
        ("限流或超时", ("限流", "超时")),
    ]
    absent = [name for name, kws in REQUIRED if not any(k in text for k in kws)]
    if absent:
        r.warn("API-07", "契约通用约定未见：%s（若分散在各接口条目中可忽略）" % "、".join(absent))

    if not all(k in text for k in ("code", "message", "traceId")):
        r.error("API-08", "错误响应结构必须同时包含 code / message / traceId")


def check_contract_no_storage(ctx: Ctx, r: Report) -> None:
    if "api" not in ctx.sections:
        return
    BAD = ("CREATE TABLE", "ALTER TABLE", "SELECT ", "INSERT INTO",
           "bigint", "timestamptz", "varchar", "numeric(")
    for idx in section_lines(ctx, "api"):
        line = ctx.lines[idx]
        for token in BAD:
            if token in line and not is_exempt(line):
                r.error("API-09", "契约章不应出现存储细节「%s」" % token.strip(), idx + 1)


# ---------------------------------------------------------------- 后端检查

def check_backend_invariants(ctx: Ctx, r: Report) -> None:
    if "backend" not in ctx.sections:
        return
    for idx in section_lines(ctx, "backend"):
        for ref in INV_REF_RE.findall(ctx.lines[idx]):
            full = "INV-" + ref
            if ctx.inv_set and full not in ctx.inv_set:
                r.error("BE-01", "引用了 00 中不存在的编号 %s" % full, idx + 1)


def check_forbidden_terms(ctx: Ctx, r: Report) -> None:
    RULES = [
        ("BE-02", r"\bVIRTUAL\b", "出现 VIRTUAL，禁止虚拟库位（INV-01 / D26）", ("backend",)),
        ("GLOBAL-01", r"\bisInternal\b|\bownerType\b|\bINTERNAL\b",
         "出现内部/外部货主分支表述，货主唯一隔离（D02 / D03）", None),
        ("BE-10", r"\bSeata\b|\bSpring Cloud\b|\bEureka\b|\bNacos\b|\bFeign\b|\bDubbo\b|\bZookeeper\b",
         "引入了 01 之外的技术组件（D09）", None),
        ("DB-01", r"\bfloat4\b|\bfloat8\b|\bdouble precision\b|\bfloat\b",
         "数量或重量使用浮点类型，应使用 numeric 定点", ("backend",)),
    ]
    for code, pattern, msg, scope in RULES:
        rx = re.compile(pattern)
        idx_range = []
        if scope:
            for key in scope:
                idx_range.extend(section_lines(ctx, key))
        else:
            idx_range = list(range(len(ctx.lines)))
        for idx in idx_range:
            line = ctx.lines[idx]
            if rx.search(line) and not is_exempt(line):
                r.error(code, msg, idx + 1)


def check_location_not_null(ctx: Ctx, r: Report) -> None:
    text = section_text(ctx, "backend")
    if not text:
        return
    if "location_id" not in text:
        r.error("BE-03", "后端设计未见 location_id，库存必须绑定真实物理库位（INV-01）")
        return
    if not re.search(r"location_id[^\n]{0,40}NOT\s+NULL", text, re.IGNORECASE):
        r.error("BE-03", "location_id 未标注 NOT NULL（INV-01）")


def check_stock_unique_key(ctx: Ctx, r: Report) -> None:
    text = section_text(ctx, "backend")
    if not text:
        return
    FIELDS = ["owner_id", "location_id", "sku_id", "batch_no", "stock_status", "serial_no"]
    missing = [f for f in FIELDS if f not in text]
    if missing:
        r.error("BE-04", "库存唯一键六字段缺失：%s（D29）" % "、".join(missing))
        return
    if "serial_no" in text:
        ok = ("NOT NULL DEFAULT ''" in text or "NOT NULL DEFAULT \"\"" in text
              or "部分唯一索引" in text or "partial index" in text.lower())
        if not ok:
            r.error("BE-05", "serial_no 空值策略未写明（需在「NOT NULL DEFAULT ''」与「部分唯一索引」中定死一条）")


def check_check_constraints(ctx: Ctx, r: Report) -> None:
    text = section_text(ctx, "backend")
    if not text:
        return
    if not re.search(r"qty_allocated\s*<=\s*qty_on_hand", text):
        r.error("BE-06", "缺少 CHECK 约束 qty_allocated <= qty_on_hand（INV-08）")
    if "CHECK" not in text.upper():
        r.warn("BE-06", "后端设计未见 CHECK 约束，若全部在应用层校验需在强制手段表说明")


def check_table_list(ctx: Ctx, r: Report) -> None:
    if "backend" not in ctx.sections:
        return
    tables = find_table(ctx, "backend", ("表名",))
    if not tables:
        r.warn("BE-07", "未找到字段含「表名」的表清单，无法自动核对表与脚本编号")
        return
    seen_script: Dict[str, int] = {}
    for t in tables:
        hdr = t["header"]
        name_col = next((i for i, h in enumerate(hdr) if "表名" in h), None)
        script_col = next((i for i, h in enumerate(hdr) if "脚本" in h or "Flyway" in h), None)
        if script_col is None:
            r.warn("BE-08", "表清单缺少「脚本编号」列，无法核对 Flyway 脚本", t["line"] + 1)
            return
        for ln, row in t["rows"]:
            cell = row[script_col] if script_col < len(row) else ""
            if not cell.strip():
                r.error("BE-08", "表清单存在未填脚本编号的行", ln + 1)
                continue
            for m in FLYWAY_RE.finditer(cell):
                ver = m.group(1)
                if ver in seen_script:
                    r.error("BE-09", "Flyway 脚本版本号 V%s 重复（另见第 %d 行）" % (ver, seen_script[ver]), ln + 1)
                else:
                    seen_script[ver] = ln + 1
            if name_col is not None and name_col < len(row) and row[name_col].strip().startswith("_"):
                r.error("BE-07", "表清单存在占位表名", ln + 1)


def check_migration_safety(ctx: Ctx, r: Report) -> None:
    text = section_text(ctx, "backend")
    if not text:
        return
    if "NOT NULL" in text.upper() and "回填" not in text:
        r.warn("BE-11", "出现新增 NOT NULL 但未见「回填」策略，迁移前需确认三步走")
    if "Flyway" not in text:
        r.warn("BE-12", "后端设计未见 Flyway，表结构变更须走迁移脚本（01）")


# ---------------------------------------------------------------- 前端检查

def check_frontend_no_ddl(ctx: Ctx, r: Report) -> None:
    if "front" not in ctx.sections:
        return
    BAD = ("CREATE TABLE", "ALTER TABLE", "bigint", "timestamptz", "varchar",
           "numeric(", "NOT NULL", "SELECT ")
    for idx in section_lines(ctx, "front"):
        line = ctx.lines[idx]
        for token in BAD:
            if token in line and not is_exempt(line):
                r.error("FE-01", "前端章不应出现存储细节「%s」" % token.strip(), idx + 1)


def check_frontend_no_api_def(ctx: Ctx, r: Report) -> None:
    if "front" not in ctx.sections:
        return
    RX = re.compile(r"(GET|POST|PUT|PATCH|DELETE)\s+/[A-Za-z0-9_/{}\-]+")
    for idx in section_lines(ctx, "front"):
        m = RX.search(ctx.lines[idx])
        if m:
            r.error("FE-02", "前端章不应定义接口路径「%s」，只引用接口编号" % m.group(0), idx + 1)


def check_frontend_features(ctx: Ctx, r: Report) -> None:
    text = section_text(ctx, "front")
    if not text:
        return
    RULES = [
        ("FE-03", "受理号", "写入交互必须按异步受理设计，需出现「受理号」（D20）"),
        ("FE-04", "数据时间戳", "降级数据必须显示数据时间戳（D19）"),
        ("FE-05", "待并入权限矩阵", "新增权限点需标注「待并入权限矩阵」"),
    ]
    for code, kw, msg in RULES:
        if kw not in text:
            r.error(code, "前端设计未见「%s」：%s" % (kw, msg))

    absent_states = [kw for kw in ("加载", "空", "无权限", "失败", "降级") if kw not in text]
    if absent_states:
        r.warn("FE-06", "前端设计未见这些态的说明：%s（异常与空态需覆盖五类）" % "、".join(absent_states))


def check_page_mapping(ctx: Ctx, r: Report) -> None:
    if "front" not in ctx.sections:
        return
    tables = find_table(ctx, "front", ("页面", "接口编号"))
    if not tables:
        r.warn("FE-07", "未找到含「页面 + 接口编号」的映射表")
        return
    defined = set(ctx.api_ids)
    for t in tables:
        hdr = t["header"]
        col = next((i for i, h in enumerate(hdr) if "接口" in h), None)
        if col is None:
            continue
        for ln, row in t["rows"]:
            if col >= len(row):
                continue
            ids = API_REF_RE.findall(row[col])
            if not ids:
                r.error("FE-08", "映射表存在未填接口编号的行", ln + 1)
                continue
            for ref in ids:
                full = "API-" + ref
                if defined and full not in defined:
                    r.error("FE-09", "页面映射引用了契约中不存在的接口 %s" % full, ln + 1)


# ---------------------------------------------------------------- 跨章对账

def check_tasks_reference(ctx: Ctx, r: Report) -> None:
    if not ctx.change_dir:
        return
    tasks = ctx.change_dir / "tasks.md"
    if not tasks.exists():
        r.info("X-01", "未找到 tasks.md，跳过联调任务引用核对")
        return
    defined = set(ctx.api_ids)
    if not defined:
        return
    for i, line in enumerate(tasks.read_text(encoding="utf-8").splitlines()):
        for ref in API_REF_RE.findall(line):
            full = "API-" + ref
            if full not in defined:
                r.error("X-01", "tasks.md 第 %d 行引用了契约中不存在的接口 %s" % (i + 1, full))


def check_errorcode_alignment(ctx: Ctx, r: Report) -> None:
    api_text = section_text(ctx, "api")
    be_text = section_text(ctx, "backend")
    if not api_text or not be_text:
        return

    def codes(text: str) -> set:
        out = set()
        for c in ERRCODE_RE.findall(text):
            if c.startswith(("INV_", "API_", "D_")):
                continue
            out.add(c)
        return out

    api_codes = codes(api_text)
    be_codes = codes(be_text)
    if not api_codes or not be_codes:
        r.info("X-02", "未采用常量式错误码，跳过错误码自动对账（人工核对契约与映射表）")
        return
    extra = sorted(be_codes - api_codes)
    if extra:
        r.error("X-02", "后端设计出现契约外错误码：%s（不得新增契约外错误码）" % "、".join(extra))
    orphan = sorted(api_codes - be_codes)
    if orphan:
        r.warn("X-02", "契约错误码在后端映射表中未见：%s" % "、".join(orphan))


def check_backend_landing(ctx: Ctx, r: Report) -> None:
    if not ctx.api_ids or "backend" not in ctx.sections:
        return
    be_text = section_text(ctx, "backend")
    orphan = [i for i in ctx.api_ids if i not in be_text]
    if orphan:
        r.warn("X-03", "以下接口在「后端设计」中未见落点引用：%s" % "、".join(orphan))


def check_invariant_flow(ctx: Ctx, r: Report) -> None:
    if not ctx.api_entries or "backend" not in ctx.sections:
        return
    be_text = section_text(ctx, "backend")
    declared = set()
    for e in ctx.api_entries:
        body = "\n".join(e["body"])
        declared.update("INV-" + x for x in INV_REF_RE.findall(body))
    missing = sorted(i for i in declared if i not in be_text)
    if missing:
        r.warn("X-04", "契约声明的不变量在后端设计中未见强制手段：%s" % "、".join(missing))


def check_decision_reference(ctx: Ctx, r: Report) -> None:
    if not ctx.dec_set:
        return
    for idx, line in enumerate(ctx.lines):
        for ref in DEC_REF_RE.findall(line):
            full = "D" + ref
            if full not in ctx.dec_set:
                r.error("REF-01", "引用了 00 中不存在的决策编号 %s" % full, idx + 1)


# ---------------------------------------------------------------- 规范文档检查
# 12 号界面规范与 13 号移动作业端规范不在 design.md 三章内，用另一套检查：
# 四段骨架、编号定义唯一、引用闭合、必备条目、色值约束、特征词、禁用词。

NORM_SPECS = [
    {
        "code": "UI",
        "path": "humandesign/sysdesign/12-ui-design-system.md",
        "label": "界面与交互设计规范",
        "optional": False,
        "id_re": r"\bUI-([A-Z])(\d{2})\b",
        "required_prefixes": ["UI-I", "UI-T", "UI-P", "UI-C", "UI-S", "UI-F", "UI-A"],
        "required_ids": [
            "UI-P01", "UI-P02", "UI-P03",
            "UI-S01", "UI-S02", "UI-S03", "UI-S04", "UI-S05",
            "UI-F01", "UI-C04", "UI-A04", "UI-T01",
        ],
        "required_phrases": [
            ("已受理", "写入必须经过受理环（D20）"),
            ("受理号", "提交后必须展示受理号（D20）"),
            ("错误码", "失败态必须展示错误码（UI-S06）"),
            ("数据时间", "降级数据必须展示数据时间（D19）"),
            ("库位", "货主端数据粒度只到库区（D17）"),
        ],
        "hex_only_in": r"UI-T\d{2}",
        "forbidden": [],
    },
    {
        "code": "PDA",
        "path": "humandesign/sysdesign/13-pda-interaction-design.md",
        "label": "移动作业端交互设计规范",
        "optional": True,
        "id_re": r"\bPDA-([A-Z])(\d{2})\b",
        "required_prefixes": ["PDA-W", "PDA-C", "PDA-I", "PDA-F", "PDA-E", "PDA-A"],
        "required_ids": [],
        "required_phrases": [
            ("扫码", "扫码是主路径（D25）"),
            ("留痕", "手输必须打标记留痕（D24、D25）"),
            ("48", "主操作最小点击区域不小于 48dp"),
            ("受理号", "写入必须经过受理环（D20）"),
        ],
        "hex_only_in": None,
        "forbidden": [
            (r"离线可用|稍后同步|断网继续|本地缓存", "不做离线能力（D16）"),
        ],
    },
]

NORM_REQUIRED_SECTIONS = ("结论", "问题", "设计", "约束与验证")
NORM_INDEX_CACHE: Dict[str, set] = {}


def _norm_ids(spec: dict, text: str) -> List[str]:
    rx = re.compile(spec["id_re"])
    return ["%s-%s%s" % (spec["code"], a, b) for a, b in rx.findall(text)]


def _is_definition(line: str, full_id: str) -> bool:
    s = line.strip()
    return any(s.startswith(p) for p in (
        "**" + full_id, "| " + full_id, "|" + full_id,
        "### " + full_id, "#### " + full_id, full_id + " ",
    ))


def check_one_norm(spec: dict, r: Report) -> None:
    rel = spec["path"]
    label = spec["label"]
    path = ROOT / rel
    if rel not in r.checked:
        r.checked.append(rel)

    if not path.exists():
        NORM_INDEX_CACHE[spec["code"]] = set()
        if spec["optional"]:
            r.info("NORM-01", "%s 尚未建立（%s），相关检查跳过" % (label, rel), source=rel)
        else:
            r.error("NORM-01", "缺少%s：%s" % (label, rel), source=rel)
        return

    lines = path.read_text(encoding="utf-8").splitlines()
    text = "\n".join(lines)

    heads = [m.group(1) for m in (re.match(r"^##\s+(.+?)\s*$", l) for l in lines) if m]
    for sec in NORM_REQUIRED_SECTIONS:
        if not any(sec in h for h in heads):
            r.error("NORM-02", "缺少二级标题「%s」（四段骨架）" % sec, source=rel)

    defs: Dict[str, List[int]] = {}
    for idx, line in enumerate(lines):
        for full in _norm_ids(spec, line):
            if _is_definition(line, full):
                defs.setdefault(full, []).append(idx + 1)
    for full, where in defs.items():
        if len(where) > 1:
            r.error("NORM-03", "编号 %s 重复定义（第 %s 行）"
                    % (full, "、".join(str(x) for x in where)), line=where[0], source=rel)

    defined = set(defs)
    for idx, line in enumerate(lines):
        for full in _norm_ids(spec, line):
            if full not in defined:
                r.error("NORM-04", "引用了未定义的编号 %s" % full, line=idx + 1, source=rel)

    range_re = re.compile(spec["code"] + r"-([A-Z])(\d{2})\s*[~〜至]\s*"
                          + spec["code"] + r"-([A-Z])(\d{2})")
    for idx, line in enumerate(lines):
        for m in range_re.finditer(line):
            p1, n1, p2, n2 = m.group(1), int(m.group(2)), m.group(3), int(m.group(4))
            if p1 != p2 or n2 <= n1:
                r.error("NORM-05", "编号范围写法异常：%s" % m.group(0), line=idx + 1, source=rel)
                continue
            for n in range(n1 + 1, n2):
                full = "%s-%s%02d" % (spec["code"], p1, n)
                if full not in defined:
                    r.error("NORM-05", "范围 %s 覆盖的 %s 未定义" % (m.group(0), full),
                            line=idx + 1, source=rel)

    for full in spec["required_ids"]:
        if full not in defined:
            r.error("NORM-06", "缺少必备条目 %s" % full, source=rel)

    all_ids = _norm_ids(spec, text)
    for prefix in spec["required_prefixes"]:
        if not any(i.startswith(prefix) for i in all_ids):
            r.error("NORM-06", "缺少 %s 类条目" % prefix, source=rel)

    hex_only = spec.get("hex_only_in")
    if hex_only:
        token_rx = re.compile(hex_only)
        color_rx = re.compile(r"#[0-9A-Fa-f]{6}\b")
        for idx, line in enumerate(lines):
            if color_rx.search(line) and not token_rx.search(line):
                r.error("NORM-07", "十六进制色值只能出现在令牌条目内", line=idx + 1, source=rel)

    for kw, why in spec.get("required_phrases", []):
        if kw not in text:
            r.error("NORM-08", "缺少「%s」：%s" % (kw, why), source=rel)

    for pattern, why in spec.get("forbidden", []):
        frx = re.compile(pattern)
        for idx, line in enumerate(lines):
            m = frx.search(line)
            if m and not is_exempt(line):
                r.error("NORM-09", "%s：出现「%s」" % (why, m.group(0)), line=idx + 1, source=rel)

    NORM_INDEX_CACHE[spec["code"]] = defined


def check_index_registration(r: Report) -> None:
    idx_path = ROOT / "humandesign" / "sysdesign" / "index.md"
    if not idx_path.exists():
        return
    text = idx_path.read_text(encoding="utf-8")
    rel = "humandesign/sysdesign/index.md"
    for num, filename in (("12", "12-ui-design-system.md"), ("13", "13-pda-interaction-design.md")):
        if not (ROOT / "humandesign" / "sysdesign" / filename).exists():
            continue
        if not re.search(r"\|\s*" + num + r"\s*\|", text):
            r.error("NORM-10", "%s 已存在，但 index.md 文档清单未登记编号 %s" % (filename, num), source=rel)


def check_pda_change_docs(r: Report) -> None:
    changes = ROOT / "openspec" / "changes"
    if not changes.exists():
        return
    ui_ids = NORM_INDEX_CACHE.get("UI", set())
    pda_ids = NORM_INDEX_CACHE.get("PDA", set())
    ui_spec = {"code": "UI", "id_re": r"\bUI-([A-Z])(\d{2})\b"}
    pda_spec = {"code": "PDA", "id_re": r"\bPDA-([A-Z])(\d{2})\b"}

    for f in sorted(changes.glob("*/pda.md")):
        rel = str(f.relative_to(ROOT))
        text = f.read_text(encoding="utf-8")
        if ui_ids:
            for full in _norm_ids(ui_spec, text):
                if full not in ui_ids:
                    r.error("NORM-11", "变更差异引用了 12 号规范中不存在的 %s" % full, source=rel)
        for full in _norm_ids(pda_spec, text):
            if not pda_ids:
                r.error("NORM-12", "变更差异引用了 %s，但 13 号常驻规范尚未建立" % full, source=rel)
            elif full not in pda_ids:
                r.error("NORM-12", "变更差异引用了 13 号规范中不存在的 %s" % full, source=rel)


def check_norms(r: Report) -> None:
    for spec in NORM_SPECS:
        check_one_norm(spec, r)
    check_index_registration(r)
    check_pda_change_docs(r)


# ---------------------------------------------------------------- 注册与执行

CHECKS = [
    ("STRUCT-02..08", "三章齐全、顺序正确、无占位残留", check_structure, None),
    ("STRUCT-08", "章节标题层级一致", check_structure_extra, None),
    ("API-01..03", "接口编号格式、唯一、连续", check_api_ids, ("api",)),
    ("API-04", "全文接口编号引用闭合", check_api_reference_closure, ("api",)),
    ("API-05", "接口条目八项要素齐全", check_api_entry_completeness, ("api",)),
    ("API-06", "写接口必须有幂等（D21）", check_write_api_idempotent, ("api",)),
    ("API-07..08", "契约通用约定与错误响应结构", check_contract_common, ("api",)),
    ("API-09", "契约章不得出现存储细节", check_contract_no_storage, ("api",)),
    ("BE-01 / REF-01", "INV 与 D 编号必须存在于 00", check_backend_invariants, ("backend",)),
    ("BE-02 / GLOBAL-01 / BE-10 / DB-01", "红线禁用词", check_forbidden_terms, None),
    ("BE-03", "location_id NOT NULL（INV-01）", check_location_not_null, ("backend",)),
    ("BE-04..05", "库存唯一键六字段与 serial_no 策略（D29）", check_stock_unique_key, ("backend",)),
    ("BE-06", "CHECK 约束（INV-08）", check_check_constraints, ("backend",)),
    ("BE-07..09", "表清单与 Flyway 脚本编号", check_table_list, ("backend",)),
    ("BE-11..12", "迁移回填与 Flyway 纪律", check_migration_safety, ("backend",)),
    ("FE-01", "前端章不得出现存储细节", check_frontend_no_ddl, ("front",)),
    ("FE-02", "前端章不得定义接口路径", check_frontend_no_api_def, ("front",)),
    ("FE-03..06", "异步受理、时间戳、权限点、五类态", check_frontend_features, ("front",)),
    ("FE-07..09", "页面与接口映射闭合", check_page_mapping, ("front",)),
    ("X-01", "tasks.md 联调任务引用闭合", check_tasks_reference, None),
    ("X-02", "契约与后端错误码对账", check_errorcode_alignment, ("api", "backend")),
    ("X-03", "契约接口在后端有落点", check_backend_landing, ("api", "backend")),
    ("X-04", "契约不变量在后端有手段", check_invariant_flow, ("api", "backend")),
    ("REF-01", "决策编号存在性", check_decision_reference, None),
]


def resolve_target(args) -> tuple:
    """返回 (design_path, change_dir)。"""
    if args.design:
        p = Path(args.design).expanduser().resolve()
        return p, p.parent if p.exists() else None
    if args.change_dir:
        d = Path(args.change_dir).expanduser().resolve()
        return d / "design.md", d
    if args.change:
        d = CHANGES_DIR / args.change
        return d / "design.md", d

    if not CHANGES_DIR.exists():
        return None, None
    candidates = [d for d in CHANGES_DIR.iterdir()
                  if d.is_dir() and d.name != "archive" and (d / "design.md").exists()]
    if len(candidates) == 1:
        return candidates[0] / "design.md", candidates[0]
    if not candidates:
        active = [d for d in CHANGES_DIR.iterdir() if d.is_dir() and d.name != "archive"]
        if len(active) == 1:
            return active[0] / "design.md", active[0]
    return None, None


SKELETON = """# {name} 设计

## 前端设计

_待补充_

## 后端设计

_待补充_

## API 接口契约

_待补充_
"""


def do_init(change: str) -> int:
    d = CHANGES_DIR / change
    d.mkdir(parents=True, exist_ok=True)
    target = d / "design.md"
    if target.exists() and target.stat().st_size > 0:
        print("拒绝覆盖已存在的 %s" % target, file=sys.stderr)
        return 2
    target.write_text(SKELETON.format(name=change), encoding="utf-8")
    print("已生成骨架 %s" % target)
    print("约定：接口条目统一用三级标题「### API-001 动作名」，表清单含「表名」「脚本编号」列。")
    return 0


def print_list_checks() -> None:
    print("检查项清单（code 可作为规则引用）\n")
    print("[变更三章 design.md]")
    for code, desc, _fn, guard in CHECKS:
        dep = ("依赖：" + "/".join(CHAPTER_LABEL[g] for g in guard)) if guard else ""
        print("  %-32s %-34s %s" % (code, desc, dep))
    print("\n[规范文档]")
    for code, desc in (
        ("NORM-01", "规范文件存在性（13 号缺省为可跳过）"),
        ("NORM-02", "四段骨架：结论 / 问题 / 设计 / 约束与验证"),
        ("NORM-03", "编号重复定义"),
        ("NORM-04", "编号引用闭合"),
        ("NORM-05", "编号范围写法与覆盖"),
        ("NORM-06", "必备编号与必备前缀类别"),
        ("NORM-07", "十六进制色值只能出现在令牌条目内"),
        ("NORM-08", "硬约束特征词（受理号、错误码、数据时间、留痕、48 等）"),
        ("NORM-09", "禁用词（离线能力、本地缓存、稍后同步）"),
        ("NORM-10", "12/13 号已存在必须登记进 index.md"),
        ("NORM-11", "变更差异 pda.md 引用 UI 编号闭合"),
        ("NORM-12", "变更差异 pda.md 引用 PDA 编号闭合"),
    ):
        print("  %-32s %s" % (code, desc))
    print("\n格式依赖：")
    print("  1. 三个二级标题：## 前端设计 / ## 后端设计 / ## API 接口契约")
    print("  2. 每个接口一个三级标题：### API-001 动作名")
    print("  3. 表清单表头含「表名」，并有一列含「脚本」")
    print("  4. 页面映射表头含「页面」与「接口编号」")
    print("  5. 规范条目定义行以编号开头：表格首列、**加粗**或标题")
    print("  6. 规范条目编号两位数字：UI-I01、PDA-W01")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="货灵 WMS 设计文档校验器")
    parser.add_argument("change", nargs="?", help="变更名（openspec/changes/<name>）")
    parser.add_argument("--change-dir", help="直接指定变更目录")
    parser.add_argument("--design", help="直接指定 design.md 路径")
    parser.add_argument("--baseline", help="决策基线文件路径")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--strict", action="store_true", help="WARN 也视为失败")
    parser.add_argument("--only", choices=["design", "norms", "all"], default="all",
                        help="只校验变更三章、只校验 12/13 号规范、或全部（默认）")
    parser.add_argument("--init", metavar="CHANGE", help="生成 design.md 三段骨架")
    parser.add_argument("--list-checks", action="store_true", help="列出全部检查项")
    args = parser.parse_args(argv)

    if args.list_checks:
        print_list_checks()
        return 0
    if args.init:
        return do_init(args.init)

    global BASELINE_DECISIONS
    if args.baseline:
        BASELINE_DECISIONS = Path(args.baseline).expanduser().resolve()

    report = Report()
    run_design = args.only in ("design", "all")
    run_norms = args.only in ("norms", "all")

    design_path = None
    change_dir = None
    ctx = None

    if run_design:
        design_path, change_dir = resolve_target(args)
        if design_path is None:
            msg = ("未定位到检查对象：openspec/changes 下没有唯一未归档变更。"
                   "请显式传入变更名、--change-dir 或 --design。")
            if args.only == "design":
                if args.json:
                    print(json.dumps({"status": "NEED_TARGET", "message": msg}, ensure_ascii=False))
                else:
                    print(msg, file=sys.stderr)
                return 2
            report.info("SKIP", "未定位到变更文档，跳过三章校验", source="变更三章")
        elif not design_path.exists():
            msg = "找不到 %s" % design_path
            if args.only == "design":
                if args.json:
                    print(json.dumps({"status": "NEED_TARGET", "message": msg}, ensure_ascii=False))
                else:
                    print(msg, file=sys.stderr)
                return 2
            report.info("SKIP", "未找到 %s，跳过三章校验" % design_path)

    if design_path is not None and design_path.exists():
        lines = design_path.read_text(encoding="utf-8").splitlines()
        inv_set, dec_set = load_baseline(report)
        sections = split_sections(lines, report)
        ctx = Ctx(design_path, lines, sections, inv_set, dec_set, change_dir)
        extract_api_entries(ctx, report)

        skipped: List[str] = []
        for _code, _desc, fn, guard in CHECKS:
            if guard and all(g in ctx.pending for g in guard):
                skipped.append(_code)
                continue
            try:
                fn(ctx, report)
            except Exception as exc:  # 单个检查器失败不影响其余
                report.error("INTERNAL", "检查器 %s 执行失败：%s" % (fn.__name__, exc))
        if skipped:
            report.info("SKIP", "以下检查因依赖章尚未填写而跳过：%s" % "、".join(skipped))

    if run_norms:
        try:
            check_norms(report)
        except Exception as exc:
            report.error("INTERNAL", "规范文档检查执行失败：%s" % exc)

    design_src = str(design_path) if design_path else ""
    groups: Dict[str, List[Issue]] = {}
    if run_design:
        report.checked.insert(0, design_src or "变更三章")
    for src in report.checked:
        groups.setdefault(src, [])
    for i in report.issues:
        groups.setdefault(i.source or design_src or "(通用)", []).append(i)

    errors = sum(1 for i in report.issues if i.level == "ERROR")
    warns = sum(1 for i in report.issues if i.level == "WARN")
    infos = sum(1 for i in report.issues if i.level == "INFO")

    def sort_key(x: Issue):
        return ({"ERROR": 0, "WARN": 1, "INFO": 2}[x.level], x.line or 0)

    if args.json:
        print(json.dumps({
            "scope": args.only,
            "summary": {"error": errors, "warn": warns, "info": infos},
            "groups": {
                src: [{"level": i.level, "code": i.code, "line": i.line, "message": i.msg}
                      for i in sorted(items, key=sort_key)]
                for src, items in groups.items()
            },
        }, ensure_ascii=False, indent=2))
    else:
        if not report.issues:
            print("无问题。")
        for src, items in groups.items():
            print("\n▶ %s" % src)
            if ctx and src == design_src and ctx.api_ids:
                print("  接口：%s" % "、".join(ctx.api_ids))
            if not items:
                print("  无问题")
            for i in sorted(items, key=sort_key):
                loc = ("第 %d 行" % i.line) if i.line else "-"
                print("[%-5s] %-9s %-8s %s" % (i.level, i.code, loc, i.msg))
        print("")
        print("ERROR %d   WARN %d   INFO %d" % (errors, warns, infos))

    if errors:
        return 1
    if args.strict and warns:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
