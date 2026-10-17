#!/usr/bin/env python3
"""清理本仓超量 artifact、运行记录、Release 与 tag。逻辑只写这一份，两个工作流、各清理作业都调用同一文件。

按 `CLEANUP_SCOPE` 分两档，写权限分开授，不同授（规范 R6）：

- `actions`（默认）：artifact 与运行记录。权限 `actions: write` + `contents: read`。
  PR 校验工作流 `pr-check.yml` 与出包工作流 `build.yml` 各一个作业，`CLEANUP_APPLY='true'` 真删。
  会话推送即触发；真删的调用点必须会话可达，否则清理一次也不会发生（规范 b0594d3）。
- `releases`：Release 只留最近 1 个，无保留 Release 对应的 `Qself-` tag 一并删。权限只授 `contents: write`，
  只在出包工作流 `build.yml` 里、默认分支上跑；不取号，不需要完整历史。

三个清理作业共用同一个 `concurrency` group `qself-cleanup` 串行，避免并发删同一对象。

清理失败按原因分口径（规范 a8787a8）：403 权限类打 `::warning::` 写明对象、端点与返回码后继续，
不让 job 红；403 以外的失败当轮即红 job 并逐条点名附返回码。
只读预演（`CLEANUP_APPLY='false'`）是备用形态，禁止拿「预演已证」当清理已做。

actions 档判据五条：命名、来源、重复当日序号、号源口径、数量。
artifact 全仓按 created_at 保留最新 5 个；运行记录每工作流按开始时间保留最新 5 条。
先列完整计划，再删产物，超限未完成运行先取消。运行上限优先，超限运行承载的候选保留产物也删除。

"""
import json
import os
import re
import subprocess
import time
from collections import defaultdict
from datetime import datetime, timedelta, timezone

REPO = os.environ["REPO"]
SCOPE = os.environ.get("CLEANUP_SCOPE", "actions").strip()
if SCOPE not in ("actions", "releases"):
    raise SystemExit(f"CLEANUP_SCOPE 只能是 actions 或 releases，收到 {SCOPE!r}")
SELF = int(os.environ["SELF"]) if SCOPE == "actions" else 0   # releases 档不清运行记录，不需要
SEGMENTS = int(os.environ["SEGMENTS"])
APPLY = os.environ.get("CLEANUP_APPLY", "false").strip().lower() == "true"
PREFIX = "Qself-"
KEEP = 5                       # 全仓 artifact 5 个、每工作流 run 5 条
KEEP_RELEASES = 1              # Release 1 个，tag 与 Release 同口径
NEED = "contents: write" if SCOPE == "releases" else "actions: write"
CST = timezone(timedelta(hours=8))
TODAY = datetime.now(CST).strftime("%Y-%m-%d")
VERB = "删" if APPLY else "将删"
print(f"cleanup.py 启动参数：SCOPE={SCOPE} SEGMENTS={SEGMENTS} CLEANUP_APPLY={'true' if APPLY else 'false'} "
      f"PREFIX={PREFIX} KEEP={KEEP} SELF={SELF} TODAY={TODAY} REPO={REPO}")
print("删除失败分级口径：DELETE 403 → ::warning:: 降级不红；403 以外 → ::error:: 累积，脚本末尾红 job")


def git_out(*args):
    """跑一条 git 命令，拿不到就返回 None（不静默）。"""
    p = subprocess.run(["git", *args], capture_output=True, text=True)
    return p.stdout.strip() if p.returncode == 0 else None


# 取号核对：号源只有 git 提交数一处（规范 c5ac12d:55），这里把两条命令的输出打出来，让日志能独立核对。
# 只 actions 档需要：releases 档不取号。
def check_history():
    since = datetime.now(CST).strftime("%Y-%m-%dT00:00:00+08:00")
    head = git_out("rev-parse", "HEAD")
    total = git_out("rev-list", "--count", "HEAD")
    daily = git_out("rev-list", "--count", f"--since={since}", "HEAD")
    shallow = git_out("rev-parse", "--is-shallow-repository")
    print(f"取号核对：head={head} 总序号={total} 当日序号={daily} 浅克隆={shallow}"
          f"（浅克隆为 true 时这两个数不可信）")
    print(f"取号命令原文：git rev-list --count HEAD ／ git rev-list --count --since={since} HEAD")
    if shallow != "false" or not all((head, total, daily)):
        raise SystemExit("完整历史或取号失败，停止清理")


def gh(*args):
    p = subprocess.run(["gh", *args], capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit("gh " + " ".join(args) + " 失败：" + p.stderr.strip())
    return p.stdout


def list_all(url, key):
    out = gh("api", "--paginate", url, "--jq", key + "[]")
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def stamp(run):
    """排序用的开始时间：run_started_at 缺失才退回 created_at。"""
    return run.get("run_started_at") or run["created_at"]


DENIED = []
FAILED = []                     # 非 403 的删除失败：(端点, 标签, 返回码, stderr)；末尾一律红 job


def http_code(stderr):
    m = re.search(r"\(HTTP (\d+)\)", stderr)
    return m.group(1) if m else "?"


def mutate(method, endpoint, label):
    """403 仅记录权限缺口，其它错误累积后使任务失败；返回真实执行结果。"""
    p = subprocess.run(["gh", "api", "-X", method, endpoint], capture_output=True, text=True)
    if p.returncode == 0:
        return True
    code = http_code(p.stderr)
    if code == "403":
        DENIED.append((endpoint, label))
        print(f"::warning::{label} 未完成：{endpoint} HTTP 403，缺 {NEED} 权限")
    else:
        FAILED.append((endpoint, label, code, p.stderr.strip()[:200]))
    return False


def delete(kind, obj_id, label, why):
    print(f"  {'删' if APPLY else '将删'} {label}（{why}）")
    return not APPLY or mutate("DELETE", f"repos/{REPO}/actions/{kind}/{obj_id}", label)


def finish():
    """收尾：非 403 失败红 job，403 只报缺口；两档共用。"""
    if FAILED:
        for endpoint, label, code, err in FAILED:
            print(f"  ::error::{label} 删除失败（HTTP {code}，非权限类）：{endpoint} — {err}")
        raise SystemExit(f"清理失败 {len(FAILED)} 个对象（非 403，按规范 a8787a8 当轮红 job）")
    if DENIED:
        print(f"清理未完成：{len(DENIED)} 个对象存在 HTTP 403 权限缺口")
    elif APPLY:
        print("清理完成：计划内删除全部成功")
    else:
        print("只读扫描完成：未执行删除")


def clean_releases():
    """Release 只留最近 1 个；tag 与 Release 同口径：无保留 Release 对应的 Qself- tag 一并删。"""
    rels = sorted((r for r in list_all(f"repos/{REPO}/releases?per_page=100", ".")
                   if r["tag_name"].startswith(PREFIX)),
                  key=lambda r: (r["created_at"], r["id"]), reverse=True)
    keep, drop = rels[:KEEP_RELEASES], rels[KEEP_RELEASES:]
    kept_tags = {r["tag_name"] for r in keep}
    tags = [t["ref"][len("refs/tags/"):]
            for t in list_all(f"repos/{REPO}/git/matching-refs/tags/{PREFIX}", ".")]
    drop_tags = sorted(t for t in tags if t not in kept_tags)
    print("Release 保留清单：", [r["tag_name"] for r in keep] or "（无）")
    print("Release 删除清单：", [r["tag_name"] for r in drop] or "（无）")
    print("tag 删除清单：", drop_tags or "（无）")
    stuck = set()                  # Release 没删掉的 tag 不删，避免留下指向缺失 tag 的 Release
    for r in drop:
        print(f"  {VERB} Release {r['tag_name']}（数量：超出上限 {KEEP_RELEASES}）")
        if APPLY and not mutate("DELETE", f"repos/{REPO}/releases/{r['id']}", f"Release {r['tag_name']}"):
            stuck.add(r["tag_name"])
    for tag in drop_tags:
        if tag in stuck:
            print(f"  tag {tag} 未删：对应 Release 删除失败")
            continue
        print(f"  {VERB} tag {tag}（无保留 Release 对应）")
        if APPLY:
            mutate("DELETE", f"repos/{REPO}/git/refs/tags/{tag}", f"tag {tag}")
    print(f"本项目 Release 共 {len(rels)} 个，保留 {len(keep)} 个；tag 共 {len(tags)} 个")


if SCOPE == "releases":
    clean_releases()
    finish()
    raise SystemExit(0)
check_history()

runs = list_all(f"repos/{REPO}/actions/runs?per_page=100", ".workflow_runs")
run_by_id = {r["id"]: r for r in runs}
self_run = run_by_id.get(SELF)
print(f"本次 run {SELF} 所属序列：{self_run['path'] if self_run else '(未出现在本次拉取的运行列表里)'}")
arts = [a for a in list_all(f"repos/{REPO}/actions/artifacts?per_page=100", ".artifacts")
        if not a["expired"]]



# ---------- artifact：口径 → 来源 → 重复当日序号 → 数量 ----------
drop_a, survivors = [], []                # survivors 是过了前两条关的候选
for a in sorted(arts, key=lambda x: (x["created_at"], x["id"]), reverse=True):
    name = a["name"]
    seg = name[len(PREFIX):].split(".") if name.startswith(PREFIX) else []
    r = run_by_id.get(a["workflow_run"]["id"])
    ws = (r or {}).get("path", "(未知来源)")
    if not name.startswith(PREFIX):
        drop_a.append((a, ws, "口径：名不是 Qself- 前缀"))
    elif len(seg) != SEGMENTS or not all(part.isdigit() for part in seg):
        drop_a.append((a, ws, f"口径：段数={len(seg)} 要求={SEGMENTS}，各段须为数字"))
    elif r and r["event"] == "pull_request":
        drop_a.append((a, ws, "来源：workflow_run 事件是 pull_request"))
    else:
        survivors.append((a, ws, seg))

# 判据三：同日同当日序号重复 —— 按 artifact created_at 删旧留新（规范 c5ac12d:73）。
# 不按总序号大小判：改文件名、换号源后总序号会回头，按大小判会误删新产物。
# 删前把两个 artifact 的名与 id、两次运行的 rn 打进日志（删除清单由日志保存）。
if SEGMENTS >= 5:
    groups = defaultdict(list)
    for a, ws, seg in survivors:
        groups[(ws, ".".join(seg[:3]), int(seg[3]))].append((a, ws))
    dup_ids = set()
    for (ws, day, daily), g in sorted(groups.items()):
        if len(g) < 2:
            continue
        g.sort(key=lambda t: (t[0]["created_at"], t[0]["id"]), reverse=True)
        newest = g[0][0]
        keep_rn = run_by_id.get(newest["workflow_run"]["id"], {}).get("run_number", "?")
        for a, ws in g[1:]:
            dup_ids.add(a["id"])
            rn = run_by_id.get(a["workflow_run"]["id"], {}).get("run_number", "?")
            drop_a.append((a, ws, f"重复当日序号 {day}.{daily}：按 artifact created_at 删旧留新，"
                                  f"留 {newest['name']}（id={newest['id']} rn={keep_rn}），"
                                  f"删 {a['name']}（id={a['id']} rn={rn}）"))
    survivors = [(a, ws, seg) for a, ws, seg in survivors if a["id"] not in dup_ids]

# 判据四：逐 head 核对总提交数；失败硬退出，不用现存最大号猜测。
counts = {}
quota = []
keep_a = []
for a, ws, seg in survivors:
    sha = a["workflow_run"]["head_sha"]
    if sha not in counts:
        value = git_out("rev-list", "--count", sha)
        if value is None:
            gh("api", f"repos/{REPO}/commits/{sha}")  # 明确核实该提交可达
            subprocess.run(["git", "fetch", "origin", sha], check=True)
            value = git_out("rev-list", "--count", sha)
        if not value or not value.isdigit():
            raise SystemExit(f"取不到 head {sha} 的提交数")
        counts[sha] = int(value)
    expected = counts[sha]
    print(f"五条判据扫描：{a['name']} id={a['id']} 段数={len(seg)} 来源=非PR "
          f"重复=通过 head={sha} 总提交数={expected} 名字总序号={seg[-1]}")
    if not seg[-1].isdigit() or int(seg[-1]) != expected:
        drop_a.append((a, ws, f"号源口径：总序号 {seg[-1]} ≠ head {sha} 提交数 {expected}"))
    else:
        quota.append((a, ws))
for i, (a, ws) in enumerate(sorted(quota, key=lambda t: (t[0]["created_at"], t[0]["id"]), reverse=True)):
    if i < KEEP:
        keep_a.append((a, ws, "数量：全仓最新 5 个"))
    else:
        drop_a.append((a, ws, "数量：超出全仓上限 5"))

# 运行先按完整序列排队：承载、本次、未完成运行均占名额，不再扩容。
drop_r, keep_r = [], []
counted = defaultdict(list)
for r in sorted(runs, key=lambda x: (stamp(x), x["id"]), reverse=True):
    counted[r["path"]].append(r)
for ws, lst in counted.items():
    for i, r in enumerate(lst):
        (keep_r if i < KEEP else drop_r).append((r, ws, "数量：每工作流最新 5 条" if i < KEEP else "数量：超出每工作流上限 5"))

# 运行上限优先：校正 artifact 候选保留清单，不因承载关系扩容。
excess_runs = {r["id"] for r, _, _ in drop_r}
for a, ws, why in keep_a:
    if a["workflow_run"]["id"] in excess_runs:
        drop_a.append((a, ws, "运行上限优先：承载运行已超过最新 5 条"))
keep_a = [(a, ws, why) for a, ws, why in keep_a
          if a["workflow_run"]["id"] not in excess_runs]

print(f"artifact：{VERB} {len(drop_a)} 个，保留 {len(keep_a)} 个")
for a, ws, why in drop_a:
    print(f"  待删 {a['name']} id={a['id']} run={a['workflow_run']['id']}（{why}）")
for a, ws, why in keep_a:
    print(f"  留 {a['name']} id={a['id']}（{why}）")
print(f"运行记录：{VERB} {len(drop_r)} 条，保留 {len(keep_r)} 条")
for r, ws, why in drop_r:
    carried = [(a["name"], a["id"]) for a in arts if a["workflow_run"]["id"] == r["id"]]
    print(f"  待删 run={r['id']} rn={r['run_number']} {ws} status={r['status']} 产物={carried}（{why}）")
for r, ws, why in keep_r:
    print(f"  留 run={r['id']} {ws}（{why}）")
for ws, lst in counted.items():
    print(f"扫描：{ws} 总数={len(lst)} 保留={min(KEEP, len(lst))} 超限={max(0, len(lst)-KEEP)}")

# 本清理若自身已超限，取消自己会中断清理；明确失败，由更新的清理运行收口。
if SELF in excess_runs:
    raise SystemExit(f"::error::本次 run={SELF} 已超限，由更新的清理运行取消并删除")

# 先删产物；403 或其它失败后不得借删除承载运行绕过失败。
blocked_runs = set()
for a, ws, why in drop_a:
    if not delete("artifacts", a["id"], f"{a['name']} id={a['id']}", why):
        blocked_runs.add(a["workflow_run"]["id"])
for r, ws, why in drop_r:
    if r["id"] in blocked_runs:
        print(f"run={r['id']} 未删：其产物删除失败，禁止级联删除")
        continue
    if r["status"] != "completed":
        endpoint = f"repos/{REPO}/actions/runs/{r['id']}"
        print(f"  {'取消' if APPLY else '将取消'} 超限运行 {r['id']}，完成后再删")
        if APPLY:
            if not mutate("POST", endpoint + "/cancel", f"取消 run={r['id']}"):
                continue
            for attempt in range(15):
                r = json.loads(gh("api", endpoint))
                if r["status"] == "completed":
                    break
                time.sleep(2)
            else:
                FAILED.append((endpoint, f"取消 run={r['id']}", "等待超时", "取消后尚未完成，未删除"))
                continue
            # 运行在扫描与取消之间可能上传产物，重读后再删，避免无清单级联删除。
            for a in list_all(endpoint + "/artifacts?per_page=100", ".artifacts"):
                if not delete("artifacts", a["id"], f"{a['name']} id={a['id']}", "超限运行取消后的产物复查"):
                    blocked_runs.add(r["id"])
            if r["id"] in blocked_runs:
                continue
    delete("runs", r["id"], f"run={r['id']} {ws}", why)

finish()
