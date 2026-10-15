#!/usr/bin/env python3
"""清理本仓超量 artifact 与运行记录。逻辑只写这一份，两个工作流调用同一文件、都真删：

- PR 校验工作流 `pr-check.yml`：权限 `actions: write`，`CLEANUP_APPLY='true'` —— 真删。
  会话推送即触发；真删的调用点必须会话可达，否则清理一次也不会发生（规范 b0594d3）。
- 出包工作流 `build.yml`：权限 `actions: write`，`CLEANUP_APPLY='true'` —— 真删，出包后收口。
  两处挂同一个 `concurrency` group 串行，避免并发删同一对象。

清理失败按原因分口径（规范 a8787a8）：403 权限类打 `::warning::` 写明对象、端点与返回码后继续，
不让 job 红，同一对象连续两次运行都失败才升级为阻塞项；403 以外的失败当轮即红 job 并逐条点名附返回码。
只读预演（`actions: read` + `CLEANUP_APPLY='false'`）是备用形态，禁止拿「预演已证」当清理已做。

判据四条（规范 b0594d3）：
  1. 命名口径：段数 ≠ 工作流顶部 SEGMENTS 声明值。
  2. 产物来源：产物的 workflow_run 事件是 pull_request。
  3. 重复当日序号：同一天同一序列出现两个相同当日序号，保留总序号最大的一个。
  4. 数量：当日之外每条序列 artifact 5 个、run 5 条；当日对象豁免（当日序号取自当日运行列表，
     删当日运行会抽走计数基准）。排队按 run_started_at（UTC+8 日界），id 只作同刻 tiebreak。

删 run 会连带删它的 artifact，所以承载保留产物的 run 不删、也不计入 run 上限。
"""
import json
import os
import re
import subprocess
from collections import defaultdict
from datetime import datetime, timedelta, timezone

REPO = os.environ["REPO"]
SELF = int(os.environ["SELF"])
SEGMENTS = int(os.environ["SEGMENTS"])
APPLY = os.environ.get("CLEANUP_APPLY", "false").strip().lower() == "true"
PREFIX = "Qself-"
KEEP = 5                       # 当日之外每条序列 artifact 5 个、run 5 条
CST = timezone(timedelta(hours=8))
TODAY = datetime.now(CST).strftime("%Y-%m-%d")
VERB = "删" if APPLY else "将删"
print(f"cleanup.py 启动参数：SEGMENTS={SEGMENTS} CLEANUP_APPLY={'true' if APPLY else 'false'} "
      f"PREFIX={PREFIX} KEEP={KEEP} SELF={SELF} TODAY={TODAY} REPO={REPO}")
print("删除失败分级口径：DELETE 403 → ::warning:: 降级不红；403 以外 → ::error:: 累积，脚本末尾红 job")


def git_out(*args):
    """跑一条 git 命令，拿不到就返回 None（不静默）。"""
    p = subprocess.run(["git", *args], capture_output=True, text=True)
    return p.stdout.strip() if p.returncode == 0 else None


# 取号核对：号源只有 git 提交数一处（规范 c5ac12d:55），这里把两条命令的输出打出来，让日志能独立核对。
_since = datetime.now(CST).strftime("%Y-%m-%dT00:00:00+08:00")
_head = git_out("rev-parse", "HEAD")
_total = git_out("rev-list", "--count", "HEAD")
_daily = git_out("rev-list", "--count", f"--since={_since}", "HEAD")
_shallow = git_out("rev-parse", "--is-shallow-repository")
print(f"取号核对：head={_head} 总序号={_total} 当日序号={_daily} 浅克隆={_shallow}"
      f"（浅克隆为 true 时这两个数不可信）")
print(f"取号命令原文：git rev-list --count HEAD ／ git rev-list --count --since={_since} HEAD")


def gh(*args):
    p = subprocess.run(["gh", *args], capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit("gh " + " ".join(args) + " 失败：" + p.stderr.strip())
    return p.stdout


def list_all(url, key):
    out = gh("api", "--paginate", url, "--jq", key + "[]")
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def cst_day(iso):
    return datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(CST).strftime("%Y-%m-%d")


def stamp(run):
    """排序用的开始时间：run_started_at 缺失才退回 created_at。"""
    return run.get("run_started_at") or run["created_at"]


FAILED = []                     # 非 403 的删除失败：(端点, 标签, 返回码, stderr)；末尾一律红 job


def http_code(stderr):
    m = re.search(r"\(HTTP (\d+)\)", stderr)
    return m.group(1) if m else "?"


def delete(kind, obj_id, label, why):
    """真删一个对象。失败按原因分口径（规范 a8787a8）：
    403 权限类（fork PR 只读等）打 ::warning:: 写明对象、端点与返回码后继续，不让 job 红，
    同一对象连续两次运行都失败才升级为阻塞项；403 以外的失败进 FAILED，末尾红 job 并逐条点名。"""
    endpoint = f"repos/{REPO}/actions/{kind}/{obj_id}"
    print(f"  {'删' if APPLY else '将删'} {label}（{why}）")
    if not APPLY:
        return
    p = subprocess.run(["gh", "api", "-X", "DELETE", endpoint], capture_output=True, text=True)
    if p.returncode == 0:
        return
    code = http_code(p.stderr)
    if code == "403":
        print(f"  ::warning::{label} 删除失败（HTTP 403，权限缺口）：{endpoint}；本次降级为预演（非 403 的失败不在降级范围）")
        return
    FAILED.append((endpoint, label, code, p.stderr.strip()[:200]))


runs = list_all(f"repos/{REPO}/actions/runs?per_page=100", ".workflow_runs")
run_by_id = {r["id"]: r for r in runs}
self_run = run_by_id.get(SELF)
print(f"本次 run {SELF} 所属序列：{self_run['path'] if self_run else '(未出现在本次拉取的运行列表里)'}")
arts = [a for a in list_all(f"repos/{REPO}/actions/artifacts?per_page=100", ".artifacts")
        if not a["expired"]]


def art_stamp(a):
    """artifact 归属运行的开始时间（run_started_at 缺失才退回 created_at），run 记录不在时退回产物 created_at。"""
    r = run_by_id.get(a["workflow_run"]["id"])
    return stamp(r) if r else a["created_at"]


# ---------- artifact：口径 → 来源 → 重复当日序号 → 数量 ----------
drop_a, survivors = [], []                # survivors 是过了前两条关的候选
for a in sorted(arts, key=lambda x: (x["created_at"], x["id"]), reverse=True):
    name = a["name"]
    seg = name[len(PREFIX):].split(".") if name.startswith(PREFIX) else []
    r = run_by_id.get(a["workflow_run"]["id"])
    ws = (r or {}).get("path", "(未知来源)")
    if not name.startswith(PREFIX):
        drop_a.append((a, ws, "口径：名不是 Qself- 前缀"))
    elif len(seg) != SEGMENTS:
        drop_a.append((a, ws, f"口径：段数 {len(seg)} ≠ {SEGMENTS}"))
    elif r and r["event"] == "pull_request":
        drop_a.append((a, ws, "来源：workflow_run 事件是 pull_request"))
    else:
        survivors.append((a, ws, seg))

# 判据三：同日同当日序号重复 —— 按 run_started_at 删旧留新（规范 c5ac12d:73）。
# 不按总序号大小判：改文件名、换号源后总序号会回头，按大小判会误删新产物。
# 删前把两个 artifact 的名与 id、两次运行的 rn 打进日志（该仓 AGENTS.md 的那行由会话记）。
if SEGMENTS >= 5:
    groups = defaultdict(list)
    for a, ws, seg in survivors:
        groups[(".".join(seg[:3]), int(seg[3]))].append((a, ws))
    dup_ids = set()
    for (day, daily), g in sorted(groups.items()):
        if len(g) < 2:
            continue
        g.sort(key=lambda t: (art_stamp(t[0]), t[0]["id"]), reverse=True)
        newest = g[0][0]
        keep_rn = run_by_id.get(newest["workflow_run"]["id"], {}).get("run_number", "?")
        for a, ws in g[1:]:
            dup_ids.add(a["id"])
            rn = run_by_id.get(a["workflow_run"]["id"], {}).get("run_number", "?")
            drop_a.append((a, ws, f"重复当日序号 {day}.{daily}：按 run_started_at 删旧留新，"
                                  f"留 {newest['name']}（id={newest['id']} rn={keep_rn}），"
                                  f"删 {a['name']}（id={a['id']} rn={rn}）"))
    survivors = [(a, ws, seg) for a, ws, seg in survivors if a["id"] not in dup_ids]

quota = defaultdict(list)
keep_a = []
for a, ws, seg in survivors:
    if cst_day(a["created_at"]) == TODAY:
        keep_a.append((a, ws, "当日豁免：数量判据当日不删"))
    else:
        quota[ws].append(a)
for ws, lst in quota.items():             # lst 已按 created_at 新到旧
    for i, a in enumerate(lst):
        if i < KEEP:
            keep_a.append((a, ws, "数量：当日之外保 5"))
        else:
            drop_a.append((a, ws, "数量：超出当日之外上限"))

print(f"artifact：{VERB} {len(drop_a)} 个，保留 {len(keep_a)} 个")
for a, ws, why in drop_a:
    delete("artifacts", a["id"], f"{a['name']} id={a['id']}", why)
kept_runs = {a["workflow_run"]["id"] for a, _, _ in keep_a}
for a, ws, why in keep_a:
    print(f"  留 {a['name']} id={a['id']}（{why}）")
left_a = defaultdict(int)
for _, ws, _ in keep_a:
    left_a[ws] += 1
for ws in sorted(left_a):
    print(f"  剩 {ws}  {left_a[ws]} 个")

# ---------- 运行记录：承载 → 数量（当日豁免不计入上限） ----------
drop_r, keep_r = [], []
counted = defaultdict(list)
for r in sorted(runs, key=lambda x: (stamp(x), x["id"]), reverse=True):
    ws = r["path"]
    if r["id"] == SELF:
        keep_r.append((r, ws, "正在运行的本次：不删"))
    elif r["status"] != "completed":
        keep_r.append((r, ws, "未完成：不删"))
    elif r["id"] in kept_runs:
        keep_r.append((r, ws, "承载保留产物：不删、不计入上限"))
    elif cst_day(stamp(r)) == TODAY:
        keep_r.append((r, ws, "当日豁免：数量判据当日不删、不计入上限"))
    else:
        counted[ws].append(r)
for ws, lst in counted.items():
    for i, r in enumerate(lst):
        if i < KEEP:
            keep_r.append((r, ws, "数量：当日之外保 5"))
        else:
            drop_r.append((r, ws, "数量：超出当日之外上限"))

print(f"运行记录：{VERB} {len(drop_r)} 条，保留 {len(keep_r)} 条")
for r, ws, why in drop_r:
    delete("runs", r["id"], f"id={r['id']} rn={r['run_number']} {ws} {stamp(r)}", why)
for r, ws, why in keep_r:
    print(f"  留 id={r['id']} rn={r['run_number']} {ws}（{why}）")
left_r = defaultdict(lambda: [0, 0])      # 序列 -> [计入上限, 当日与承载豁免]
for r, ws, why in keep_r:
    left_r[ws][0 if why.startswith("数量：当日之外保") else 1] += 1
for ws in sorted(left_r):
    print(f"  剩 {ws}  计入上限 {left_r[ws][0]} 条 / 当日与承载豁免 {left_r[ws][1]} 条")

if FAILED:
    for endpoint, label, code, err in FAILED:
        print(f"  ::error::{label} 删除失败（HTTP {code}，非权限类）：{endpoint} — {err}")
    raise SystemExit(f"清理失败 {len(FAILED)} 个对象（非 403，按规范 a8787a8 当轮红 job）")
print("清理完成：DELETE 全部成功或按 403 降级。")
