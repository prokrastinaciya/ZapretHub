import os
import runpy
import subprocess
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def test_runner_collects_all_project_tests():
    with patch.object(subprocess, 'run'), patch.object(subprocess, 'call', return_value=0) as call:
        try:
            runpy.run_path(str(ROOT / 'scripts/run_python_tests.py'), run_name='__main__')
        except SystemExit as error:
            assert error.code == 0
    command = call.call_args.args[0]
    assert str(ROOT / 'tests') in command
