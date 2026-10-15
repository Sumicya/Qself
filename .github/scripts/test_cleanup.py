"""清理计划与删除边界的离线回归，不访问 GitHub。"""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name('cleanup.py')


def run(i, path='build.yml', status='completed'):
    return dict(id=i, path=path, status=status, run_number=i, head_sha=str(i),
                event='push', created_at=f'2026-10-15T00:00:{i:02d}Z')


def artifact(i, name=None):
    return dict(id=i, name=name or f'Qself-26.10.15.{i}.{i}', expired=False,
                created_at=f'2026-10-15T00:00:{i:02d}Z', workflow_run=dict(id=i, head_sha=str(i)))


class CleanupTest(unittest.TestCase):
    def execute(self, runs, arts, apply=False, failures=None):
        calls, output = [], io.StringIO()
        failures = failures or {}

        def command(args, **kwargs):
            calls.append(args)
            text, code, err = '', 0, ''
            if args[0] == 'git':
                if '--is-shallow-repository' in args:
                    text = 'false'
                elif '--count' in args:
                    text = args[-1] if args[-1].isdigit() else '100'
                else:
                    text = 'head'
            elif '-X' in args:
                endpoint = args[-1]
                if endpoint in failures:
                    code, err = 1, f'failure (HTTP {failures[endpoint]})'
            elif '--paginate' in args:
                endpoint = args[3]
                objects = arts if '/artifacts?' in endpoint else runs
                text = '\n'.join(json.dumps(x) for x in objects)
            else:
                text = json.dumps(dict(run(1), status='completed'))
            return subprocess.CompletedProcess(args, code, stdout=text, stderr=err)

        env = dict(REPO='Sumicya/Qself', SELF='999', SEGMENTS='5', CLEANUP_APPLY=str(apply).lower())
        with patch.dict(os.environ, env), patch('subprocess.run', side_effect=command), contextlib.redirect_stdout(output):
            try:
                runpy.run_path(str(SCRIPT), run_name='__main__')
            except SystemExit as e:
                return calls, output.getvalue(), str(e)
        return calls, output.getvalue(), None

    def test_global_artifact_and_strict_run_limits(self):
        runs = [run(i, 'a.yml' if i % 2 else 'b.yml') for i in range(1, 15)]
        calls, output, error = self.execute(runs, [artifact(i) for i in range(5, 15)])
        self.assertIsNone(error)
        self.assertIn('artifact：将删 5 个，保留 5 个', output)
        self.assertIn('运行记录：将删 4 条，保留 10 条', output)
        self.assertFalse(any('-X' in c for c in calls))

    def test_all_invalid_artifact_criteria(self):
        runs = [run(i) for i in range(1, 6)]
        runs[1]['event'] = 'pull_request'
        arts = [artifact(1, 'Qself-1.2'), artifact(2),
                artifact(3, 'Qself-26.10.15.3.99'),
                artifact(4, 'Qself-26.10.15.5.4'), artifact(5)]
        _, output, error = self.execute(runs, arts)
        self.assertIsNone(error)
        for why in ['口径：', '来源：', '重复当日序号', '号源口径：']:
            self.assertIn(why, output)
        self.assertIn('artifact：将删 4 个，保留 1 个', output)

    def test_failed_artifact_does_not_cascade_to_run(self):
        runs = [run(i) for i in range(1, 7)]
        endpoint = 'repos/Sumicya/Qself/actions/artifacts/1'
        for code in ['403', '500']:
            calls, output, error = self.execute(runs, [artifact(1, 'bad')], True, {endpoint: code})
            self.assertFalse(any('-X' in c and c[-1].endswith('/runs/1') for c in calls))
            if code == '403':
                self.assertIsNone(error)
                self.assertIn('清理未完成', output)
            else:
                self.assertIsNotNone(error)

    def test_retained_artifact_conflict_stops_deletion(self):
        calls, _, error = self.execute([run(i) for i in range(1, 7)], [artifact(1)], True)
        self.assertIn('保留冲突', error)
        self.assertFalse(any('-X' in c for c in calls))

    def test_cancel_before_delete(self):
        runs = [run(i) for i in range(1, 7)]
        runs[0]['status'] = 'in_progress'
        calls, _, error = self.execute(runs, [], True)
        self.assertIsNone(error)
        mutations = [c for c in calls if '-X' in c]
        self.assertEqual(mutations[0][-1], 'repos/Sumicya/Qself/actions/runs/1/cancel')
        self.assertEqual(mutations[-1][-1], 'repos/Sumicya/Qself/actions/runs/1')


if __name__ == '__main__':
    unittest.main()
