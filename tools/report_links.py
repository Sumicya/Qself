"""汇报用的链接块：提交、CI 运行、产物、对比、规范 SHA —— 全是点得开的完整 URL。

主人不用猜「他说的是哪个提交／哪次 CI／哪个包」，Agent 也不用凭记忆拼 URL。
`links()` 是纯函数，`python3 tools/report_links.py [sha]` 从 git/gh 取数直接打印。
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

SPEC_REPO = 'Sumicya/selfs'
SPEC_FILE = 'GLOBAL.md'


def blob(repo: str, sha: str, path: str = '') -> str:
    return f'https://github.com/{repo}/blob/{sha}/{path}'.rstrip('/')


def links(repo: str, sha: str, *, run: int = 0, artifacts=(), base: str = '',
          spec: str = '', upstream=()) -> list[str]:
    """把一次汇报该给的链接排成行；没有的项就不出现，不印空链接。"""
    root = f'https://github.com/{repo}'
    out = [f'提交：{root}/commit/{sha}']
    if run:
        out.append(f'CI：{root}/actions/runs/{run}')
    for artifact_id, name, size in artifacts:
        out.append(f'产物：{root}/actions/runs/{run}/artifacts/{artifact_id}（{name}，{size} 字节）')
    if base:
        out.append(f'对比：{root}/compare/{base}...{sha}')
    if spec:
        out.append(f'规范：{blob(SPEC_REPO, spec, SPEC_FILE)}')
    for r, s, path in upstream:
        out.append(f'上游：{blob(r, s, path)}')
    return out


def _git(*args: str) -> str:
    done = subprocess.run(['git', *args], capture_output=True, text=True)
    return done.stdout.strip() if done.returncode == 0 else ''


def _gh(path: str) -> dict | list:
    return json.loads(subprocess.run(['gh', 'api', path], capture_output=True, text=True, check=True).stdout)


def collect(sha: str = '') -> list[str]:
    repo = re.sub(r'^.*github\.com[:/]|\.git$', '', _git('remote', 'get-url', 'origin'))
    sha = sha or _git('rev-parse', 'HEAD')
    run, artifacts = 0, []
    for r in _gh(f'repos/{repo}/actions/runs?head_sha={sha}&per_page=1')['workflow_runs']:
        run = r['id']
        artifacts = [(a['id'], a['name'], a['size_in_bytes'])
                     for a in _gh(f'repos/{repo}/actions/runs/{run}/artifacts')['artifacts']]
    base = _git('merge-base', 'origin/main', sha) or _git('merge-base', 'origin/main', 'HEAD')
    return links(repo, sha, run=run, artifacts=artifacts, base=base)


if __name__ == '__main__':
    for line in collect(sys.argv[1] if len(sys.argv) > 1 else ''):
        print(line)
