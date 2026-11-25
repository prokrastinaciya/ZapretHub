from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from plan_byebyedpi_update import plan_update

LOCK = {"tag": "v.1.8.2", "commit": "a" * 40}
NEW = {"tag_name": "v.1.8.3", "prerelease": False, "draft": False}


def test_same_release_is_noop():
    assert plan_update(LOCK, LOCK["commit"], {**NEW, "tag_name": LOCK["tag"]}) is None


@pytest.mark.parametrize("flag", ["draft", "prerelease"])
def test_unpublished_candidate_is_skipped(flag):
    assert plan_update(LOCK, LOCK["commit"], {**NEW, flag: True}) is None


def test_new_stable_has_deterministic_branch_and_no_duplicate():
    branch = plan_update(LOCK, LOCK["commit"], NEW)
    assert branch == "bot/byebyedpi-v.1.8.3"
    assert plan_update(LOCK, LOCK["commit"], NEW, {branch}) is None


def test_moved_pin_rejected_even_when_new_release_exists():
    with pytest.raises(ValueError, match="moved"):
        plan_update(LOCK, "b" * 40, NEW)


def test_invalid_tag_never_becomes_branch():
    with pytest.raises(ValueError, match="tag"):
        plan_update(LOCK, LOCK["commit"], {**NEW, "tag_name": "v.1.8.3;echo bad"})


def test_older_latest_does_not_roll_back_lock():
    assert plan_update(LOCK, LOCK["commit"], {**NEW, "tag_name": "v.1.8.1"}) is None
