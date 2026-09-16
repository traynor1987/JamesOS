"""Static gates only. Does not replace Android compilation or device testing."""
from pathlib import Path
import json, re, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
modules=[root/'app/src/main',root/'wear/src/main']
files=[p for module in modules for p in module.rglob('*')]
source='\n'.join(p.read_text() for p in files if p.is_file() and p.suffix in {'.kt','.xml'})
for forbidden in ['android.webkit','WebView(', 'navigator.', 'serviceWorker','manifest.webmanifest','localStorage','indexedDB','fallbackToDestructiveMigration']:
    assert forbidden not in source, forbidden
for required in ['Room.databaseBuilder','OpenDocument','CreateDocument','withTransaction','SavedStateHandle','HealthConnectClient','GeofencingRequest','CoroutineWorker','FileProvider','GET_SIGNING_CERTIFICATES','WearableListenerService','passiveMonitoringClient','TileService','ComplicationDataSourceService']:
    assert required in source, required
for module in modules:
    for p in module.rglob('*.xml'): ET.parse(p)
assert len(json.loads((root/'app/src/main/assets/rut-defaults.json').read_text()))==72
settings=(root/'settings.gradle.kts').read_text()
assert 'include(":app", ":wear")' in settings
assert 'legacy-web' not in (root/'app/build.gradle.kts').read_text()
validation=(root/'.github/workflows/android.yml').read_text()
release=(root/'.github/workflows/release.yml').read_text()
for required in [':app:testDebugUnitTest',':app:assembleRelease',':app:connectedDebugAndroidTest']:
    assert required in validation, required
# Public/fork validation must not receive Samsung's proprietary standalone AAR.
for forbidden in [':wear:testDebugUnitTest', ':wear:assembleRelease']:
    assert forbidden not in validation, forbidden
for required in ['james-wear.apk.sha256','gh release create','environment: james-release']:
    assert required in release, required
for required in ['patch="${VERSION_NAME##*.}"', 'test "$VERSION_CODE" = "$patch"']:
    assert required in release, required
assert 'gh release create' not in validation
try:
    from tree_sitter import Language, Parser
    import tree_sitter_kotlin
except ImportError:
    print('Kotlin syntax parser unavailable; Gradle compilation remains the CI gate.')
else:
    parser=Parser(Language(tree_sitter_kotlin.language()))
    kotlin=list((root/'app').rglob('*.kt'))+list((root/'wear').rglob('*.kt'))+list(root.glob('*.kts'))+[root/'app/build.gradle.kts',root/'wear/build.gradle.kts']
    for p in kotlin:
        tree=parser.parse(p.read_bytes())
        assert not tree.root_node.has_error, f'Kotlin syntax error: {p}'
    print('Kotlin syntax trees: PASS')
print('Native phone + Wear architecture, resources, 72 RUT definitions: PASS (static only)')
