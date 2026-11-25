"""Run vendor integrity, upstream runtime tests and the Android bridge tests."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
# test_h2_settings also imports the desktop tray/UI; Android does not ship it.
# HTTP/2 runtime behavior remains covered by cf_h2, h2_routing and h2_transport.
CORE_TESTS = ("bridge", "cf_h2", "config", "event_loop", "fake_tls", "h2_routing", "h2_transport", "pool", "raw_websocket", "tcp_fallback")

if __name__ == "__main__":
    subprocess.run([sys.executable, str(ROOT / "scripts/sync_upstream.py"), "--check"], check=True)
    targets = [str(ROOT / f"upstream/flowseal/tests/test_{name}.py") for name in CORE_TESTS]
    targets += [str(ROOT / "tests")]
    env = os.environ.copy()
    env["PYTHONPATH"] = os.pathsep.join([str(ROOT / "upstream/flowseal"), str(ROOT / "app/src/main/python"), env.get("PYTHONPATH", "")])
    raise SystemExit(subprocess.call([sys.executable, "-m", "pytest", "--import-mode=importlib", "-q", *targets], cwd=ROOT, env=env))
