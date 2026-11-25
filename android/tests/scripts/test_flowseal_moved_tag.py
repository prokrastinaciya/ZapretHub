import importlib.util
import json
from pathlib import Path
from unittest.mock import patch

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('flowseal_sync', ROOT / 'scripts/sync_upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def test_moved_locked_tag_rejected_before_archive_or_writes(tmp_path):
    lock = tmp_path / 'lock.json'
    lock.write_text(json.dumps({'tag': 'v1.2.3', 'commit': 'a' * 40}))
    with patch.object(sync, 'LOCK', lock), patch.object(sync, 'fetch', return_value=json.dumps({'sha': 'b' * 40}).encode()) as fetch:
        with pytest.raises(ValueError, match='(?i)tag.*(moved|changed)'):
            sync.sync('v1.2.3')
    assert fetch.call_count == 1
    assert json.loads(lock.read_text())['commit'] == 'a' * 40
