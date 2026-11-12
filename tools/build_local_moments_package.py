"""Build and audit the deterministic OrangeChat local-moments plugin ZIP.

The plugin is a normal OrangeChat plugin ZIP: manifest.json at the root,
main.js as the QuickJS entry, and a real WebView UI under ui/. It declares
only the localdata.* permissions it uses and no network hosts.

This script is deliberately stdlib-only and never performs network access,
never reads user API keys, and never executes plugin JavaScript.

Output:
  build/local-moments-plugin/local-moments-<version>.zip

Usage:
  python tools/build_local_moments_package.py            # build + audit
  python tools/build_local_moments_package.py --check    # audit existing ZIP
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / 'plugins/local-moments'
DEFAULT_OUTPUT = ROOT / 'build/local-moments-plugin'

FIXED_DATE = (2026, 1, 1, 0, 0, 0)
MAX_ENTRY_BYTES = 512_000
MAX_TOTAL_BYTES = 1_500_000
MAX_ENTRIES = 16

ENTRIES: Sequence[str] = (
    'manifest.json',
    'main.js',
    'README.md',
    'ui/index.html',
    'ui/styles.css',
    'ui/core.js',
    'ui/bridge.js',
    'ui/app.js',
)

ALLOWED_MANIFEST_KEYS = {
    'id', 'name', 'description', 'version', 'author', 'icon',
    'entry', 'permissions', 'allowedHosts', 'customPageWebView',
}

REQUIRED_PERMISSIONS = {
    'localdata.read',
    'localdata.write',
    'localdata.publish',
    'localdata.comment',
    'localdata.like',
    'localdata.media',
    'localdata.chat_snapshot',
    'localdata.backup',
}

EXPECTED_PLUGIN_ID = 'com.orangechat.localmoments'
SEMVER_RE = re.compile(r'^\d+\.\d+\.\d+$')
PLUGIN_ID_RE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')
URL_RE = re.compile(r'https?://[^\s"\'<>)]+')
PERMISSION_RE = re.compile(r"'([^']+)'")

FORBIDDEN_JS = (
    ('localStorage usage', re.compile(r'(?:window\.)?localStorage\s*[.\[]')),
    ('sessionStorage usage', re.compile(r'(?:window\.)?sessionStorage\s*[.\[]')),
    ('IndexedDB usage', re.compile(r'\bindexedDB\s*[.\[]')),
    ('fetch call', re.compile(r'\bfetch\s*\(')),
    ('XMLHttpRequest', re.compile(r'\bXMLHttpRequest\b')),
    ('WebSocket', re.compile(r'\bWebSocket\b')),
    ('sendBeacon', re.compile(r'\bsendBeacon\s*\(')),
    ('callAI call', re.compile(r'\bcallAI\s*\(')),
    ('eval', re.compile(r'\beval\s*\(')),
    ('new Function', re.compile(r'\bnew\s+Function\s*\(')),
    ('innerHTML', re.compile(r'\.innerHTML\b')),
    ('outerHTML', re.compile(r'\.outerHTML\b')),
    ('insertAdjacentHTML', re.compile(r'\.insertAdjacentHTML\b')),
    ('document.write', re.compile(r'\bdocument\.write\s*\(')),
    ('API key material', re.compile(r'(?:api[_-]?key|sk-[A-Za-z0-9]{12,})', re.IGNORECASE)),
)

ACTOR_ASSERTION_RE = re.compile(
    r'["\'](?:actorId|actor_id|actorKind|viewerId|asAssistant|isAssistant|actingAssistantId)["\']\s*:'
)
MOCK_RE = re.compile(r'\b(?:mock|fake|sample)(?:Reply|Post|Comment|Data|List)?\b', re.IGNORECASE)


class BuildError(RuntimeError):
    pass


def fail(message: str) -> 'None':
    raise BuildError(message)


def _no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail(f'duplicate JSON key: {key!r}')
        result[key] = value
    return result


def load_json(data: bytes, label: str):
    try:
        return json.loads(data.decode('utf-8-sig'), object_pairs_hook=_no_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f'{label}: invalid UTF-8 JSON ({error})')


def read_source(relative: str) -> bytes:
    path = (PLUGIN / relative).resolve()
    try:
        path.relative_to(PLUGIN.resolve())
    except ValueError:
        fail(f'{relative}: path escapes the plugin directory')
    if not path.is_file():
        fail(f'{relative}: missing source file')
    data = path.read_bytes()
    if len(data) > MAX_ENTRY_BYTES:
        fail(f'{relative}: {len(data)} bytes exceeds {MAX_ENTRY_BYTES}')
    if b'\x00' in data:
        fail(f'{relative}: contains NUL bytes')
    return data


def text_of(data: bytes, relative: str) -> str:
    try:
        return data.decode('utf-8-sig')
    except UnicodeDecodeError as error:
        fail(f'{relative}: not UTF-8 ({error})')


def validate_manifest(raw: bytes) -> Dict:
    manifest = load_json(raw, 'manifest.json')
    if not isinstance(manifest, dict):
        fail('manifest.json: root must be an object')
    unknown = set(manifest) - ALLOWED_MANIFEST_KEYS
    if unknown:
        fail(f'manifest.json: unsupported fields: {sorted(unknown)}')
    for key in ('id', 'name', 'description', 'version', 'author', 'icon', 'entry'):
        if not isinstance(manifest.get(key), str) or not manifest[key].strip():
            fail(f'manifest.json: {key} must be a non-empty string')
    if manifest['id'] != EXPECTED_PLUGIN_ID:
        fail(f'manifest.json: id must be {EXPECTED_PLUGIN_ID}')
    if not PLUGIN_ID_RE.match(manifest['id']):
        fail('manifest.json: invalid id')
    if not SEMVER_RE.match(manifest['version']):
        fail('manifest.json: version must be x.y.z')
    if manifest['entry'] != 'main.js':
        fail('manifest.json: entry must be main.js')
    if manifest.get('allowedHosts') != []:
        fail('manifest.json: allowedHosts must be [] (no network)')
    if 'tools' in manifest or 'config' in manifest or 'hooks' in manifest:
        fail('manifest.json: this package must not register model tools/config/hooks')
    if 'promptTemplate' in manifest:
        fail('manifest.json: this package must not inject a prompt template')
    page = manifest.get('customPageWebView')
    if page != {'entry': 'ui/index.html'}:
        fail('manifest.json: customPageWebView.entry must be ui/index.html')
    permissions = manifest.get('permissions')
    if not isinstance(permissions, list) or not all(isinstance(item, str) for item in permissions):
        fail('manifest.json: permissions must be a string array')
    if len(set(permissions)) != len(permissions):
        fail('manifest.json: duplicate permissions')
    if set(permissions) != REQUIRED_PERMISSIONS:
        missing = sorted(REQUIRED_PERMISSIONS - set(permissions))
        extra = sorted(set(permissions) - REQUIRED_PERMISSIONS)
        fail(f'manifest.json: permission mismatch missing={missing} extra={extra}')
    return manifest


def validate_sources(manifest: Dict) -> Dict[str, bytes]:
    sources: Dict[str, bytes] = {}
    for relative in ENTRIES:
        sources[relative] = read_source(relative)

    html = text_of(sources['ui/index.html'], 'ui/index.html')
    css = text_of(sources['ui/styles.css'], 'ui/styles.css')
    core = text_of(sources['ui/core.js'], 'ui/core.js')
    bridge = text_of(sources['ui/bridge.js'], 'ui/bridge.js')
    app = text_of(sources['ui/app.js'], 'ui/app.js')
    main = text_of(sources['main.js'], 'main.js')
    readme = text_of(sources['README.md'], 'README.md')

    if "default-src 'none'" not in html:
        fail('ui/index.html: missing strict default-src none CSP')
    for required in ("script-src 'self'", "connect-src 'none'", "img-src 'self' https://localdata.invalid data:", 'frame-src bridge:'):
        if required not in html:
            fail(f'ui/index.html: missing CSP fragment {required!r}')
    if re.search(r'<script\b(?![^>]*\bsrc=)', html, re.IGNORECASE):
        fail('ui/index.html: inline script tags are not allowed')
    if re.search(r'\son[a-z]+\s*=', html, re.IGNORECASE):
        fail('ui/index.html: inline event handlers are not allowed')
    scripts = re.findall(r'<script src="([^"]+)"', html)
    if scripts != ['core.js', 'bridge.js', 'app.js']:
        fail(f'ui/index.html: unexpected script order {scripts}')
    if '<link rel="stylesheet" href="styles.css">' not in html:
        fail('ui/index.html: styles.css is not linked')

    if re.search(r'\b(const|let)\s', main):
        fail('main.js: QuickJS entry must use var, not const/let')
    if '=>' in main or '`' in main:
        fail('main.js: QuickJS entry must not use arrows or template literals')
    if 'exports.MOMENTS_PLUGIN' not in main:
        fail('main.js: missing exports.MOMENTS_PLUGIN')
    main_version = re.search(r"version:\s*'([^']+)'", main)
    main_id = re.search(r"id:\s*'([^']+)'", main)
    if not main_version or main_version.group(1) != manifest['version']:
        fail('main.js: version does not match manifest.json')
    if not main_id or main_id.group(1) != manifest['id']:
        fail('main.js: plugin id does not match manifest.json')

    for label, source in (('ui/core.js', core), ('ui/bridge.js', bridge), ('ui/app.js', app)):
        for name, pattern in FORBIDDEN_JS:
            if pattern.search(source):
                fail(f'{label}: forbidden {name}')
        for url in URL_RE.findall(source):
            if url != 'https://localdata.invalid' and not url.startswith('https://localdata.invalid/'):
                fail(f'{label}: unexpected URL {url!r}')
    for url in URL_RE.findall(html):
        if url != 'https://localdata.invalid' and not url.startswith('https://localdata.invalid/'):
            fail(f'ui/index.html: unexpected URL {url!r}')
    for url in URL_RE.findall(css):
        if url != 'https://localdata.invalid' and not url.startswith('https://localdata.invalid/'):
            fail(f'ui/styles.css: unexpected URL {url!r}')
    if ACTOR_ASSERTION_RE.search(bridge) or ACTOR_ASSERTION_RE.search(app):
        fail('bridge/app: actor-assertion key is not allowed')
    if MOCK_RE.search(core) or MOCK_RE.search(bridge) or MOCK_RE.search(app):
        fail('plugin UI: mock/sample/fake identifiers are not allowed')

    version = re.search(r"PLUGIN_VERSION\s*=\s*'([^']+)'", app)
    plugin_id = re.search(r"PLUGIN_ID\s*=\s*'([^']+)'", app)
    if not version or version.group(1) != manifest['version']:
        fail('ui/app.js: PLUGIN_VERSION does not match manifest.json')
    if not plugin_id or plugin_id.group(1) != manifest['id']:
        fail('ui/app.js: PLUGIN_ID does not match manifest.json')

    declared_block = re.search(r'var DECLARED_PERMISSIONS\s*=\s*\[(.*?)\];', app, re.DOTALL)
    if not declared_block:
        fail('ui/app.js: DECLARED_PERMISSIONS block missing')
    declared = set(PERMISSION_RE.findall(declared_block.group(1)))
    if declared != set(manifest['permissions']):
        fail(f'ui/app.js: permissions do not match manifest: {sorted(declared)}')

    if 'localdata.read' not in html and 'localdata.read' not in app:
        fail('plugin UI: no localdata.read declaration')
    if 'Host method' not in bridge and 'localDataTask' not in bridge:
        fail('ui/bridge.js: host bridge adapter is missing')
    if len(readme.strip()) < 200:
        fail('README.md: too short to document the host contract and limits')
    return sources


def zip_bytes(sources: Dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED, allowZip64=False) as archive:
        for name in sorted(sources):
            data = sources[name]
            info = zipfile.ZipInfo(name, FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compresslevel=9)
    return buffer.getvalue()


def validate_zip(payload: bytes, sources: Dict[str, bytes], manifest: Dict) -> None:
    if len(payload) > MAX_TOTAL_BYTES:
        fail(f'ZIP is {len(payload)} bytes, above {MAX_TOTAL_BYTES}')
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            fail(f'ZIP has {len(infos)} entries, above {MAX_ENTRIES}')
        names = [info.filename for info in infos]
        if any(info.is_dir() for info in infos):
            fail('ZIP must not contain directory entries')
        if names != sorted(ENTRIES):
            fail(f'ZIP entries are not the exact sorted allowlist: {names}')
        total = 0
        for info in infos:
            if info.date_time != FIXED_DATE:
                fail(f'{info.filename}: timestamp is not fixed')
            if info.compress_type != zipfile.ZIP_DEFLATED:
                fail(f'{info.filename}: not deflated')
            if info.flag_bits & 0x1:
                fail(f'{info.filename}: encrypted entries are not allowed')
            mode = (info.external_attr >> 16) & 0o777
            if mode != 0o644:
                fail(f'{info.filename}: mode {oct(mode)} is not 0644')
            if info.file_size > MAX_ENTRY_BYTES:
                fail(f'{info.filename}: compressed payload too large')
            total += info.file_size
            if info.file_size != len(sources[info.filename]):
                fail(f'{info.filename}: size mismatch')
            if archive.read(info.filename) != sources[info.filename]:
                fail(f'{info.filename}: content mismatch')
        if total > MAX_TOTAL_BYTES:
            fail('ZIP expands above the total byte limit')
        if archive.testzip() is not None:
            fail('ZIP CRC test failed')
    if load_json(sources['manifest.json'], 'manifest.json') != manifest:
        fail('manifest changed between validation and packaging')


def default_path(manifest: Dict) -> Path:
    return DEFAULT_OUTPUT / f"local-moments-{manifest['version']}.zip"


def report(payload: bytes, path: Path, sources: Dict[str, bytes]) -> None:
    digest = hashlib.sha256(payload).hexdigest()
    print(f'OK {path.relative_to(ROOT) if path.is_relative_to(ROOT) else path}')
    print(f'   entries: {len(sources)}  bytes: {len(payload)}  sha256: {digest}')
    for name in sorted(sources):
        print(f'   {name:24} {len(sources[name]):8} bytes')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='audit an existing ZIP without rebuilding')
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    raw_manifest = read_source('manifest.json')
    manifest = validate_manifest(raw_manifest)
    sources = validate_sources(manifest)
    payload = zip_bytes(sources)
    if zip_bytes(sources) != payload:
        fail('ZIP build is not deterministic in this Python runtime')
    validate_zip(payload, sources, manifest)

    path = (args.output_dir / f"local-moments-{manifest['version']}.zip").resolve()
    if args.check:
        if not path.is_file():
            fail(f'missing ZIP: {path}')
        existing = path.read_bytes()
        if existing != payload:
            fail(f'{path} is stale or not byte-identical to the current sources; rebuild it')
        validate_zip(existing, sources, manifest)
        report(existing, path, sources)
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    validate_zip(path.read_bytes(), sources, manifest)
    report(path.read_bytes(), path, sources)


if __name__ == '__main__':
    try:
        main()
    except BuildError as error:
        print(f'error: {error}', file=sys.stderr)
        raise SystemExit(1)
