import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('apk_scope', Path(__file__).resolve().parents[2] / 'scripts/check_apk.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_installer_scan_describes_its_limited_dex_signature_scope():
    description = module.check_no_package_installer.__doc__.lower()
    assert 'signature' in description
    assert 'not' in description
    assert 'native' in description
