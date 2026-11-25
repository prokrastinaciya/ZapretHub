import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_android_reports_do_not_persist_raw_diagnostics():
    for name in ('run_android_tests.py', 'smoke_release.py'):
        source = (ROOT / 'scripts' / name).read_text(encoding='utf-8')
        assert '"-b", "all"' not in source
        assert '"-b", "all"' not in source.replace('(', '[')
        tree = ast.parse(source)
        for call in (node for node in ast.walk(tree) if isinstance(node, ast.Call)):
            if isinstance(call.func, ast.Attribute) and call.func.attr == 'write_text':
                assert not any(isinstance(node, ast.Attribute) and node.attr == 'stdout' for node in ast.walk(call))
