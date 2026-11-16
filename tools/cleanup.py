#!/usr/bin/env python3
"""Qself 清理：只处理 Actions 产物与运行记录，逻辑与 Selffont 版一致。

管 Release 和 tag 的那一半已经整段删掉：不发版、不打 tag，删别人的 Release 与 tag 同样是
对外动作，不能由 CI 自己干。读取 GH_TOKEN、REPO、RUN_ID、CLEANUP_APPLY。

artifact 只有一条判据，照规范 15（产物）：每仓总数 ≤5，按 created_at 留最新 5 个，超出当轮删。
不按名字、段数、前缀、事件来源或号源做任何分类——那五条自造判据在 Selffont 上删掉了母版
基准件（Selffont-mother-97e9dd4-5）和五份探针包，同一份脚本搬过来不重犯。
run 只有数量判据：每个工作流保留最新 5 条，未完成且超限的先取消；工作流文件已删除的，一条不留。

失败分级：403 权限缺口打印对象、端点与返回码的 warning 后继续；403 以外的失败打印 error 并让
本轮失败，不把权限不足描述成清理成功。
"""
import json
import os
import subprocess
import sys
import time
from collections import defaultdict
from datetime import datetime, timedelta, timezone

KEEP_RUNS = 5
KEEP_ARTIFACTS = 5

# 规范 10 的例外只开给「等远端接口最终一致性」这一种：单次 ≤5 秒、最多 3 次、总计 ≤15 秒。
# 等的是 GET /repos/{owner}/{repo}/actions/artifacts：刚对某个产物发过 DELETE，这个列表接口
# 可能还返回旧快照。不等就会把已经删掉的算成没删，复核随机报红，而报红的原因是接口延迟，
# 不是清理失败。等构建另有替代品（盯运行状态），那是被禁的那一类，不适用本条。
RETRY_WAIT_SECONDS = 5
RETRY_MAX_TIMES = 3
RETRY_TOTAL_BUDGET = 15
assert RETRY_WAIT_SECONDS * RETRY_MAX_TIMES <= RETRY_TOTAL_BUDGET, \
    "规范 10 硬刻度：单次秒数 × 次数不得超过总预算"
CLOCK = timezone(timedelta(hours=8))


def env_value(name: str, default: str | None = None) -> str:
    raw = (os.environ.get(name) or default or "").strip()
    if not raw:
        sys.exit(f"缺少环境变量 {name}")
    return raw


def env_int(name: str, minimum: int = 1) -> int:
    raw = env_value(name)
    if not raw.isdigit() or int(raw) < minimum:
        sys.exit(f"{name} 必须是 ≥{minimum} 的十进制整数，收到 {raw!r}")
    return int(raw)


# 不写就按预演（false）走；只有写了一个既不是 true 也不是 false 的值才算配错。
# 上一版要求必须显式写 true/false，于是「默认预演」这条路在本地根本走不通：不设就报错退出。
APPLY_RAW = (os.environ.get("CLEANUP_APPLY") or "").strip().lower()
if APPLY_RAW not in ("", "true", "false"):
    sys.exit("CLEANUP_APPLY 必须是 true/false")
APPLY = APPLY_RAW == "true"
REPO = env_value("REPO")
PROJECT = REPO.rsplit("/", 1)[-1]
RUN_ID = env_int("RUN_ID")

MODEL = "真删" if APPLY else "预演（只打印清单）"
PENDING: list[dict] = []                            # 每项：类型/端点/对象/方法/动作词
PERMISSION_GAPS: list[tuple[str, str, str]] = []     # (类型, 端点, 对象)
FAILURES: list[tuple[str, str, str]] = []            # (类型, 对象, 原因)


def api_list(endpoint: str, expression: str) -> list[dict]:
    """`gh api --paginate` 读列表端点，逐行 JSON 解析。

    读不到直接退出：清单不完整时宁可不动手，也不删半个仓库。
    """
    result = subprocess.run(["gh", "api", "--paginate", endpoint, "--jq", expression],
                            capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"读取 {endpoint} 失败（gh 退出码 {result.returncode}）：{result.stderr.strip()}")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def workflow_file_exists(path: str) -> bool:
    """这条运行所属的工作流，yml 还在不在**本次运行的提交**上；取不到再看默认分支。

    2026-11-07 的现场：main 上只剩 `.github/workflows/build.yml`，`pr-check.yml` 早从 main 删掉，
    却仍被 `/actions/workflows` 列成第二个工作流、5 条运行挂在名下——历史不会自己消失。

    只看默认分支会误杀：工作流文件只活在会话分支上时 main 查不到，于是**正在跑的那条也会被
    当成「只剩历史」删掉**（Selffont 实测 `42136626218` 直接 404、`42136626046` 被标 cancelled）。
    所以先按本次运行的 `GITHUB_SHA` 查：文件在，就是活着的工作流，按常规保留 5 条；两个 ref
    都查不到，才算真的只剩历史——本次提交删掉了 `pr-check.yml`，那 5 条就按这条规则清零。
    """
    refs = []
    sha = (os.environ.get("GITHUB_SHA") or "").strip()
    if sha:
        refs.append(sha)
    refs.append(None)                     # 默认分支兜底
    for ref in refs:
        endpoint = f"repos/{REPO}/contents/{path}" + (f"?ref={ref}" if ref else "")
        result = subprocess.run(["gh", "api", endpoint, "--jq", ".sha"],
                                capture_output=True, text=True)
        if result.returncode == 0:
            return True
        if "HTTP 404" not in result.stderr and "Not Found" not in result.stderr:
            sys.exit(f"读取 {endpoint} 失败（gh 退出码 {result.returncode}）：{result.stderr.strip()}")
    return False


def run_time(row: dict) -> str:
    """排序统一用 run_started_at；一直排队没真正开始过时该字段缺失，退回 created_at。"""
    return row.get("run_started_at") or row.get("created_at") or ""


def stamp(row: dict) -> str:
    """把运行时刻转成 UTC+8 的可读时间，日志里不出现零时区后缀。"""
    raw = run_time(row)
    if not raw:
        return "未知时间"
    return datetime.fromisoformat(raw.replace("Z", "+00:00")).astimezone(CLOCK).strftime(
        "%Y-%m-%d %H:%M:%S")





def plan(kind: str, endpoint: str, label: str, method: str = "DELETE", verb: str = "删除") -> None:
    PENDING.append({"kind": kind, "endpoint": endpoint, "label": label,
                    "method": method, "verb": verb})


def permission_gap(stderr: str) -> bool:
    return "HTTP 403" in stderr or "Resource not accessible by integration" in stderr


def already_gone(stderr: str) -> bool:
    """409/404：对象已被另一个并发任务删掉（PR check 与 Build 共用同一把锁，
    但上一轮排队中的清理可能与本轮重叠）。这个不算失败，也不该把整轮标红。"""
    return "HTTP 404" in stderr or "HTTP 409" in stderr or "Not Found" in stderr


def execute() -> None:
    print("=== 待处理清单（以下对象均已判定，清单打印完才动手） ===")
    if not PENDING:
        print("无")
    for item in PENDING:
        print(f"{item['verb']}[{item['kind']}] {item['method']} {item['endpoint']} 对象={item['label']}")
    if not APPLY:
        print("=== 预演结束：未执行任何删除（CLEANUP_APPLY=false） ===")
        return
    for item in PENDING:
        kind, endpoint, label = item["kind"], item["endpoint"], item["label"]
        method, verb = item["method"], item["verb"]
        result = subprocess.run(["gh", "api", "-X", method, endpoint], capture_output=True, text=True)
        if result.returncode == 0:
            print(f"已{verb}[{kind}] {label}")
            continue
        stderr = result.stderr.strip()
        if already_gone(stderr):
            print(f"跳过[{kind}] {label}（已不存在；端点={method} {endpoint}；{stderr[:120]}）")
            continue
        if permission_gap(stderr):
            PERMISSION_GAPS.append((kind, endpoint, label))
            print(f"::warning::{verb}失败（403 权限缺口，跳过）：对象={label}；端点={method} {endpoint}；"
                  f"gh 返回码={result.returncode}；{stderr[:180]}")
            continue
        print(f"::error::{verb}失败：对象={label}；端点={method} {endpoint}；gh 返回码={result.returncode}；"
              f"{stderr[:220]}")
        FAILURES.append((kind, label, f"端点={method} {endpoint}；gh 返回码={result.returncode}"))
    if PERMISSION_GAPS:
        print(f"=== {len(PERMISSION_GAPS)} 项因 403 权限缺口未执行，不计为清理成功 ===")
    if FAILURES:
        sys.exit(f"{len(FAILURES)} 项删除失败（非 403），详见上方 ::error:: 行")


# ------------------------------------------------------------------ artifact / run

def clean_actions() -> None:
    """规范 15 只有一条判据：每仓 artifact 总数 ≤5，按 created_at 留最新 5 个。

    不按名字、段数、前缀、事件来源、号源做任何分类。分类只会让「最新 5 个」失效。
    """
    runs = api_list(f"repos/{REPO}/actions/runs?per_page=100",
                    ".workflow_runs[] | {id,path,event,status,run_number,run_started_at,created_at}")
    by_workflow: dict[str, list[dict]] = defaultdict(list)
    for row in runs:
        by_workflow[row.get("path") or ""].append(row)

    print(f"=== {PROJECT} 清理：{MODEL}；artifact 全仓最多 {KEEP_ARTIFACTS} 个（只按 created_at）；"
          f"run 每工作流最多 {KEEP_RUNS} 条；本次运行 {RUN_ID} ===")

    # run：工作流文件还在 → 保留最新 5 条；文件已从默认分支删除 → 一条不留（只剩历史的不能装成活着的）。
    for path, rows in sorted(by_workflow.items()):
        keep = KEEP_RUNS
        if not path:
            keep = KEEP_RUNS          # 取不到路径时不猜，按常规上限处理
        elif not workflow_file_exists(path):
            keep = 0
            print(f"工作流文件已删除：{path}（默认分支上取不到）→ 该工作流的运行一条不留")
        ordered = sorted(rows, key=lambda row: (run_time(row), row["id"]), reverse=True)
        for index, row in enumerate(ordered):
            label = (f"{path} {stamp(row)} run_number={row['run_number']} id={row['id']} "
                     f"status={row['status']}")
            if index < keep:
                print(f"保留[run] {label}（最新 {index + 1}/{keep}）")
                continue
            if row["id"] == RUN_ID:
                print(f"保留[run] {label}（本次运行自己）")
                continue
            if row["status"] != "completed":
                plan("run", f"repos/{REPO}/actions/runs/{row['id']}/cancel", label,
                     method="POST", verb="取消")
            else:
                plan("run", f"repos/{REPO}/actions/runs/{row['id']}", label)

    # artifact：全仓口径，一条判据。
    artifacts = api_list(f"repos/{REPO}/actions/artifacts?per_page=100",
                         ".artifacts[] | {id,name,created_at,expired,workflow_run}")
    live = [row for row in artifacts if row.get("expired") is not True]
    mine = [row for row in live if (row.get("workflow_run") or {}).get("id") == RUN_ID]
    pool = [row for row in live if (row.get("workflow_run") or {}).get("id") != RUN_ID]
    pool.sort(key=lambda row: (row["created_at"], row["id"]), reverse=True)
    room = max(KEEP_ARTIFACTS - len(mine), 0)
    kept, victims = pool[:room], pool[room:]

    for row in mine:
        print(f"保留[artifact] {row['created_at']} {row['name']} id={row['id']}（本次运行刚产出）")
    for row in kept:
        print(f"保留[artifact] {row['created_at']} {row['name']} id={row['id']}")
    for row in victims:
        plan("artifact", f"repos/{REPO}/actions/artifacts/{row['id']}",
             f"{row['created_at']} {row['name']} id={row['id']} 判据=按 created_at 最旧")

    print(f"artifact：全仓未过期 {len(live)} 个，保留 {len(kept) + len(mine)} 个，"
          f"{'删除' if APPLY else '将删'} {len(victims)} 个")
    print(f"run：{'删除' if APPLY else '将删'} "
          f"{sum(1 for i in PENDING if i['kind'] == 'run' and i['verb'] == '删除')} 条，"
          f"{'取消' if APPLY else '将取消'} "
          f"{sum(1 for i in PENDING if i['kind'] == 'run' and i['verb'] == '取消')} 条")


def verify() -> None:
    """清理后复核：数量仍超限且本轮没有 403 缺口，就当轮失败，不把没删成说成清理成功。"""
    if not APPLY:
        return
    artifacts = api_list(f"repos/{REPO}/actions/artifacts?per_page=100",
                         ".artifacts[] | {id,name,workflow_run}")
    left = [row for row in artifacts if row.get("expired") is not True]
    for attempt in range(RETRY_MAX_TIMES):
        if len(left) <= KEEP_ARTIFACTS or PERMISSION_GAPS:
            break
        print(f"复核：读到 {len(left)} 个，超过 {KEEP_ARTIFACTS}；等 {RETRY_WAIT_SECONDS} 秒重读 artifacts 列表"
              f"（第 {attempt + 1}/{RETRY_MAX_TIMES} 次，总计上限 {RETRY_TOTAL_BUDGET} 秒）")
        time.sleep(RETRY_WAIT_SECONDS)
        artifacts = api_list(f"repos/{REPO}/actions/artifacts?per_page=100",
                             ".artifacts[] | {id,name,created_at,expired,workflow_run}")
        left = [row for row in artifacts if row.get("expired") is not True]
    gap = any(kind == "artifact" for kind, _, _ in PERMISSION_GAPS)
    print(f"复核：artifact 全仓剩 {len(left)} 个（上限 {KEEP_ARTIFACTS}，不分前缀）")
    if gap:
        print("复核未完成：artifact 清理遇到 403 权限缺口，未把未删对象描述成清理成功。")
    elif len(left) > KEEP_ARTIFACTS:
        FAILURES.append(("artifact", "清理后数量", f"实际剩 {len(left)} 个，超过 {KEEP_ARTIFACTS}"))
    runs = api_list(f"repos/{REPO}/actions/runs?per_page=100",
                    ".workflow_runs[] | {id,path,status}")
    per_workflow: dict[str, list[dict]] = defaultdict(list)
    for row in runs:
        per_workflow[row.get("path") or ""].append(row)
    cancel_gap = any(kind == "run" for kind, _, _ in PERMISSION_GAPS)
    for path, rows in sorted(per_workflow.items()):
        active = [row for row in rows if row["status"] != "completed" or row["id"] == RUN_ID]
        print(f"复核：{path} 当前剩 {len(rows)} 条（上限 {KEEP_RUNS}；其中未完成/本次运行 "
              f"{len(active)} 条）")
        if cancel_gap:
            print(f"复核未完成：{path} 的运行清理遇到 403 权限缺口。")
        elif len(rows) > KEEP_RUNS + len(active):
            FAILURES.append(("run", path, f"清理后仍有 {len(rows)} 条，超过 {KEEP_RUNS}"))
    if FAILURES:
        for kind, label, reason in FAILURES:
            print(f"::error::清理后仍超限[{kind}] {label}：{reason}")
        sys.exit(f"{len(FAILURES)} 项清理复核未通过")


def main() -> None:
    clean_actions()
    execute()
    verify()


if __name__ == "__main__":
    main()
