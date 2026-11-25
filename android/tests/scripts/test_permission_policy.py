import importlib.util
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location('policy', Path(__file__).resolve().parents[2] / 'scripts/check_permissions.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
PACKAGE = 'com.prokrastinaciya.zaprethub'


def test_missing_mandatory_permission_rejected():
    with pytest.raises(ValueError, match='Missing'):
        policy.check(set(policy.ALLOWED) - {'android.permission.INTERNET'} | {PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}, PACKAGE)


def test_unparseable_permission_line_rejected():
    with pytest.raises(ValueError, match='permission'):
        policy.parse_permissions("uses-permission: broken\n")



def test_unrecognized_export_attribute_fails_closed():
    tree = '''E: manifest
  E: application
    E: provider
      A: android:name="evil.Provider"
      A: android:exported=(type 0x12)0xffffffff
'''
    with pytest.raises(ValueError, match='(?i)export'):
        policy.check_components(tree, PACKAGE)


def test_unrecognized_component_attribute_format_fails_closed():
    tree = '''E: manifest
  E: application
    E: provider
      A: android:name="evil.Provider"
      A: android:exported ? malformed
'''
    with pytest.raises(ValueError, match='(?i)attribute'):
        policy.check_components(tree, PACKAGE)


def test_unexpected_exported_provider_rejected():
    tree = '''E: manifest
  E: application
    E: provider
      A: android:name="evil.Provider"
      A: android:exported=true
'''
    with pytest.raises(ValueError, match='(?i)export'):
        policy.check_components(tree, PACKAGE)
