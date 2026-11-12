"""Build and audit the three declaration-only OrangeChat theme ZIPs.

Colour source of truth is ``interactionColors()`` in ``RichChatCards.kt``: the
four literal colours of the ice / sakura / lavender card branches. The script
fails loudly if those branches change shape, if a required token is missing, or
if any text/background pair drops below WCAG contrast thresholds.

Output:
  build/local-theme-packages/{ice,sakura,lavender}-1.0.0.zip
  plugins/themes/theme-schema-v1/themes/token-template.json  (complete template)

Usage:
  python tools/build_local_theme_packages.py            # build + audit
  python tools/build_local_theme_packages.py --check    # audit existing ZIPs
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/me/rerere/rikkahub/ui/components/message/RichChatCards.kt'
SCHEMA = ROOT / 'app/src/main/java/me/rerere/rikkahub/theme/packageio/ThemePackage.kt'
DEFAULT_OUTPUT = ROOT / 'build/local-theme-packages'
TEMPLATE = ROOT / 'plugins/themes/theme-schema-v1/themes/token-template.json'

THEMES: Sequence[Tuple[str, str, Tuple[int, int, int]]] = (
    ('ice', '浮光冰蓝', (8, 12, 18)),
    ('sakura', '流光樱粉', (12, 18, 24)),
    ('lavender', '星河浅紫', (10, 14, 20)),
)

MAX_ARCHIVE_BYTES = 512_000
MAX_ENTRY_BYTES = 128_000
MAX_ENTRIES = 25
COLOR_RE = re.compile(r'^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$')
ID_RE = re.compile(r'^[a-z0-9][a-z0-9._-]{0,63}$')

WHITE = '#FFFFFFFF'
BLACK = '#FF000000'
DARK_TEXT = '#FF14161B'
SECONDARY_INK_WEIGHT = 0.50
DARK_PRIMARY_WHITE_WEIGHT = 0.10

TEXT_PAIRS = (
    ('onPrimary', 'primary'),
    ('onSecondary', 'secondary'),
    ('onTertiary', 'tertiary'),
    ('onBackground', 'background'),
    ('onSurface', 'surface'),
    ('onSurfaceVariant', 'surfaceVariant'),
    ('onSurfaceVariant', 'background'),
    ('onSurfaceVariant', 'surfaceContainer'),
    ('onPrimaryContainer', 'primaryContainer'),
    ('onSecondaryContainer', 'secondaryContainer'),
    ('onTertiaryContainer', 'tertiaryContainer'),
    ('onError', 'error'),
    ('onErrorContainer', 'errorContainer'),
    ('inverseOnSurface', 'inverseSurface'),
    ('mutedText', 'background'),
    ('mutedText', 'cardBackground'),
)

UI_PAIRS = (
    ('outline', 'background'),
    ('outline', 'surfaceVariant'),
    ('outline', 'cardBackground'),
    ('cardBorder', 'cardBackground'),
    ('cardBorder', 'background'),
)


def fail(message: str) -> None:
    raise SystemExit(message)


def display_path(path: Path) -> Path:
    try:
        return path.relative_to(ROOT)
    except ValueError:
        return path


def required_names() -> List[str]:
    text = SCHEMA.read_text(encoding='utf-8-sig')
    block = text.split('val requiredNames = setOf(', 1)[1].split(')', 1)[0]
    names = re.findall(r'"([A-Za-z0-9_]+)"', block)
    if len(names) < 30 or len(set(names)) != len(names):
        fail('Could not read a unique required token list from ThemePackage.kt')
    return names


def _rgb(color: str) -> Tuple[int, int, int]:
    value = color.lstrip('#')
    if len(value) == 8:
        value = value[2:]
    if len(value) != 6 or not re.fullmatch(r'[0-9A-Fa-f]{6}', value):
        fail(f'Unsupported colour {color!r}')
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


def _hex(rgb: Sequence[float]) -> str:
    return '#FF' + ''.join('%02X' % max(0, min(255, int(round(channel)))) for channel in rgb)


def mix(a: str, b: str, weight_of_b: float) -> str:
    """Blend two colours; weight_of_b=0 returns a, weight_of_b=1 returns b."""
    left, right = _rgb(a), _rgb(b)
    return _hex([x + (y - x) * weight_of_b for x, y in zip(left, right)])


def _luminance(color: str) -> float:
    channels = []
    for channel in _rgb(color):
        value = channel / 255
        channels.append(value / 12.92 if value <= 0.03928 else ((value + 0.055) / 1.055) ** 2.4)
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast(a: str, b: str) -> float:
    high, low = sorted((_luminance(a), _luminance(b)), reverse=True)
    return (high + 0.05) / (low + 0.05)


def best_text_on(background: str, candidates: Iterable[str] = (WHITE, DARK_TEXT)) -> str:
    return max(candidates, key=lambda candidate: contrast(candidate, background))


def palette(ident: str) -> Tuple[str, str, str, str]:
    """Return (top, bottom, ink, accent) from the real RichChatCards branch."""
    lines = SOURCE.read_text(encoding='utf-8-sig').splitlines()
    explicit = f'"{ident}" -> InteractionColors'
    matches = [line for line in lines if explicit in line]
    if not matches and ident == 'ice':
        # Ice is currently the default branch; prefer an explicit branch if one is added later.
        matches = [line for line in lines if 'else -> InteractionColors' in line]
    if len(matches) != 1:
        fail(f'Expected exactly one interactionColors branch for {ident!r}, found {len(matches)}')
    line = matches[0]
    values = re.findall(r'Color\(0x([0-9A-Fa-f]{8})\)', line)
    if len(values) != 4:
        fail(f'Expected four literal colours in the {ident!r} branch, found {len(values)}')
    return tuple('#' + value.upper() for value in values)  # type: ignore[return-value]


def tokens_for(top: str, bottom: str, ink: str, accent: str) -> Dict[str, Dict[str, str]]:
    """Map the four real card colours onto the complete v1 token schema.

    Light: card top is the page surface, card ink is the primary/body colour,
    card bottom is the container, card accent is the secondary/outline source.
    Dark: the same hues tint a near-black surface so all three packages stay
    distinguishable in dark mode instead of sharing one neutral background.
    """
    light: Dict[str, str] = {
        'primary': ink,
        'secondary': mix(accent, ink, SECONDARY_INK_WEIGHT),
        'tertiary': mix(bottom, ink, 0.75),
        'background': top,
        'surface': top,
        'surfaceVariant': bottom,
        'error': '#FFBA1A1A',
        'errorContainer': '#FFFFDAD6',
        'onErrorContainer': '#FF410002',
        'scrim': BLACK,
        'inverseSurface': ink,
        'inverseOnSurface': top,
        'surfaceDim': mix(top, ink, 0.10),
        'surfaceBright': mix(top, WHITE, 0.55),
        'surfaceContainerLowest': mix(top, WHITE, 0.60),
        'surfaceContainerLow': mix(top, WHITE, 0.30),
        'surfaceContainer': mix(top, bottom, 0.50),
        'surfaceContainerHigh': mix(bottom, top, 0.30),
        'surfaceContainerHighest': mix(bottom, ink, 0.08),
        'primaryContainer': bottom,
        'secondaryContainer': mix(accent, WHITE, 0.78),
        'tertiaryContainer': mix(bottom, WHITE, 0.50),
        'onPrimaryContainer': ink,
        'onSecondaryContainer': mix(accent, ink, 0.72),
        'onTertiaryContainer': ink,
        'onBackground': ink,
        'onSurface': ink,
        'onSurfaceVariant': ink,
        'outline': mix(accent, ink, 0.45),
        'outlineVariant': mix(accent, top, 0.30),
        'cardBackground': mix(top, bottom, 0.50),
        'cardBorder': mix(accent, ink, 0.40),
        'mutedText': mix(ink, top, 0.10),
        'selected': mix(bottom, ink, 0.18),
        'shadow': '#33000000',
    }
    light['onPrimary'] = best_text_on(light['primary'])
    light['onSecondary'] = best_text_on(light['secondary'])
    light['onTertiary'] = best_text_on(light['tertiary'])
    light['onError'] = best_text_on(light['error'])
    light['inversePrimary'] = mix(bottom, WHITE, 0.20)

    dark_background = mix('#FF0C0E13', top, 0.08)
    dark: Dict[str, str] = {
        'primary': mix(top, WHITE, DARK_PRIMARY_WHITE_WEIGHT),
        'secondary': mix(accent, WHITE, 0.35),
        'tertiary': mix(bottom, WHITE, 0.20),
        'background': dark_background,
        'surface': dark_background,
        'surfaceVariant': mix('#FF1D2028', top, 0.14),
        'error': '#FFFFB4AB',
        'errorContainer': '#FF93000A',
        'onErrorContainer': '#FFFFDAD6',
        'scrim': BLACK,
        'inverseSurface': top,
        'inverseOnSurface': ink,
        'inversePrimary': ink,
        'surfaceDim': mix(dark_background, BLACK, 0.30),
        'surfaceBright': mix(dark_background, top, 0.20),
        'surfaceContainerLowest': dark_background,
        'surfaceContainerLow': mix(dark_background, top, 0.05),
        'surfaceContainer': mix(dark_background, top, 0.09),
        'surfaceContainerHigh': mix(dark_background, top, 0.13),
        'surfaceContainerHighest': mix(dark_background, top, 0.17),
        'primaryContainer': mix('#FF262A34', top, 0.22),
        'secondaryContainer': mix(dark_background, accent, 0.18),
        'tertiaryContainer': mix(dark_background, bottom, 0.16),
        'onPrimaryContainer': mix(top, WHITE, 0.70),
        'onSecondaryContainer': mix(accent, WHITE, 0.55),
        'onTertiaryContainer': mix(bottom, WHITE, 0.45),
        'onBackground': mix(top, WHITE, 0.55),
        'onSurface': mix(top, WHITE, 0.55),
        'onSurfaceVariant': mix(top, WHITE, 0.40),
        'outline': mix(accent, WHITE, 0.30),
        'outlineVariant': mix(dark_background, top, 0.30),
        'cardBackground': mix(dark_background, top, 0.09),
        'cardBorder': mix(accent, WHITE, 0.35),
        'mutedText': mix(mix(top, WHITE, 0.55), dark_background, 0.30),
        'selected': mix(dark_background, top, 0.17),
        'shadow': '#66000000',
    }
    dark['onPrimary'] = best_text_on(dark['primary'])
    dark['onSecondary'] = best_text_on(dark['secondary'])
    dark['onTertiary'] = best_text_on(dark['tertiary'])
    dark['onError'] = best_text_on(dark['error'])
    return {'light': light, 'dark': dark}


def check_contrast(tokens: Dict[str, str], mode: str) -> Tuple[float, float]:
    text_min = min(contrast(tokens[fg], tokens[bg]) for fg, bg in TEXT_PAIRS)
    ui_min = min(contrast(tokens[fg], tokens[bg]) for fg, bg in UI_PAIRS)
    if text_min < 4.5:
        worst = min(TEXT_PAIRS, key=lambda pair: contrast(tokens[pair[0]], tokens[pair[1]]))
        fail(f'{mode}: text contrast {text_min:.2f} below 4.5 for {worst[0]} on {worst[1]}')
    if ui_min < 3.0:
        worst = min(UI_PAIRS, key=lambda pair: contrast(tokens[pair[0]], tokens[pair[1]]))
        fail(f'{mode}: UI contrast {ui_min:.2f} below 3.0 for {worst[0]} on {worst[1]}')
    return text_min, ui_min


def verify_tokens(tokens: Dict[str, str], names: Sequence[str], mode: str) -> Tuple[float, float]:
    if set(tokens) != set(names):
        missing = sorted(set(names) - set(tokens))
        extra = sorted(set(tokens) - set(names))
        fail(f'{mode}: token set mismatch, missing={missing} extra={extra}')
    for key, value in tokens.items():
        if not COLOR_RE.match(value):
            fail(f'{mode}: token {key}={value!r} is not #RRGGBB or #AARRGGBB')
    return check_contrast(tokens, mode)


def _no_duplicate_object(pairs):
    seen = set()
    for key, _ in pairs:
        if key in seen:
            raise ValueError(f'duplicate JSON key {key!r}')
        seen.add(key)
    return dict(pairs)


def load_json(data: bytes):
    return json.loads(data.decode('utf-8'), object_pairs_hook=_no_duplicate_object)


def validate_zip(path: Path, names: Sequence[str]) -> Dict[str, Tuple[float, float]]:
    if not path.is_file():
        fail(f'Missing theme package {path}')
    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        fail(f'{path.name}: archive exceeds {MAX_ARCHIVE_BYTES} bytes')
    stats: Dict[str, Tuple[float, float]] = {}
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            fail(f'{path.name}: too many entries')
        if any(info.is_dir() for info in infos):
            fail(f'{path.name}: directory entries are not allowed')
        total = 0
        for info in infos:
            if info.file_size > MAX_ENTRY_BYTES:
                fail(f'{path.name}: {info.filename} exceeds {MAX_ENTRY_BYTES} bytes')
            total += info.file_size
        if total > MAX_ARCHIVE_BYTES:
            fail(f'{path.name}: expanded size exceeds {MAX_ARCHIVE_BYTES} bytes')
        if 'manifest.json' not in archive.namelist():
            fail(f'{path.name}: missing manifest.json')
        manifest = load_json(archive.read('manifest.json'))
        if set(manifest) - {'schemaVersion', 'id', 'name', 'version', 'author', 'themes'}:
            fail(f'{path.name}: unsupported manifest field')
        if manifest.get('schemaVersion') != 1:
            fail(f'{path.name}: unsupported package schema')
        if not ID_RE.match(str(manifest.get('id', ''))):
            fail(f'{path.name}: invalid package id')
        themes = manifest.get('themes')
        if not isinstance(themes, list) or not themes or len(themes) > 24 or len(set(themes)) != len(themes):
            fail(f'{path.name}: invalid theme list')
        expected = {'manifest.json'} | {f'themes/{theme_id}.json' for theme_id in themes}
        if set(archive.namelist()) != expected:
            fail(f'{path.name}: undeclared or missing archive entries')
        for theme_id in themes:
            if not ID_RE.match(theme_id):
                fail(f'{path.name}: invalid theme id {theme_id!r}')
            theme = load_json(archive.read(f'themes/{theme_id}.json'))
            if set(theme) - {'schemaVersion', 'id', 'name', 'light', 'dark', 'shape'}:
                fail(f'{path.name}: unsupported theme field')
            if theme.get('schemaVersion') != 1 or theme.get('id') != theme_id:
                fail(f'{path.name}: theme id/schema mismatch')
            if not isinstance(theme.get('name'), str) or not theme['name'].strip():
                fail(f'{path.name}: invalid theme name')
            shape = theme.get('shape', {})
            for key, value in shape.items():
                if key not in {'corner_small_dp', 'corner_medium_dp', 'corner_large_dp'}:
                    fail(f'{path.name}: unsupported shape token {key!r}')
                if not isinstance(value, (int, float)) or not 0 <= value <= 32:
                    fail(f'{path.name}: shape token {key} outside 0..32dp')
            light_stats = verify_tokens(theme['light'], names, f'{theme_id}/light')
            dark_stats = verify_tokens(theme['dark'], names, f'{theme_id}/dark')
            stats[f'{theme_id}/light'] = light_stats
            stats[f'{theme_id}/dark'] = dark_stats
    return stats


def write_zip(path: Path, manifest: dict, theme: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, content in (('manifest.json', manifest), (f"themes/{theme['id']}.json", theme)):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, json.dumps(content, ensure_ascii=False, indent=2).encode('utf-8'))


def write_template(names: Sequence[str]) -> None:
    neutral = tokens_for('#FFF7F7FA', '#FFE8E8EE', '#FF27272A', '#FF767688')
    verify_tokens(neutral['light'], names, 'template/light')
    verify_tokens(neutral['dark'], names, 'template/dark')
    template = {
        'schemaVersion': 1,
        'id': 'token-template',
        'name': 'Theme Token Template',
        'light': neutral['light'],
        'dark': neutral['dark'],
        'shape': {'corner_small_dp': 8, 'corner_medium_dp': 12, 'corner_large_dp': 20},
    }
    TEMPLATE.parent.mkdir(parents=True, exist_ok=True)
    TEMPLATE.write_text(json.dumps(template, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def build(output: Path, names: Sequence[str]) -> None:
    for ident, label, shape in THEMES:
        top, bottom, ink, accent = palette(ident)
        colors = tokens_for(top, bottom, ink, accent)
        verify_tokens(colors['light'], names, f'{ident}/light')
        verify_tokens(colors['dark'], names, f'{ident}/dark')
        manifest = {
            'schemaVersion': 1,
            'id': f'me.orangechat.theme.{ident}',
            'name': label,
            'version': '1.0.0',
            'author': 'OrangeChat',
            'themes': [ident],
        }
        theme = {
            'schemaVersion': 1,
            'id': ident,
            'name': label,
            'light': colors['light'],
            'dark': colors['dark'],
            'shape': {
                'corner_small_dp': shape[0],
                'corner_medium_dp': shape[1],
                'corner_large_dp': shape[2],
            },
        }
        path = output / f'{ident}-1.0.0.zip'
        write_zip(path, manifest, theme)
        print(f'built {display_path(path)}')
    write_template(names)
    print(f'wrote {display_path(TEMPLATE)}')


def check_palette_source(path: Path, ident: str) -> None:
    """The ZIP must carry the exact RichChatCards colours, not a lookalike preset."""
    top, bottom, ink, accent = palette(ident)
    with zipfile.ZipFile(path) as archive:
        theme = load_json(archive.read(f'themes/{ident}.json'))
    expected = {
        'light.background': (theme['light']['background'], top),
        'light.primary': (theme['light']['primary'], ink),
        'light.surfaceVariant': (theme['light']['surfaceVariant'], bottom),
        'light.secondary': (theme['light']['secondary'], mix(accent, ink, SECONDARY_INK_WEIGHT)),
        'dark.primary': (theme['dark']['primary'], mix(top, WHITE, DARK_PRIMARY_WHITE_WEIGHT)),
    }
    for label, (actual, wanted) in expected.items():
        if actual != wanted:
            fail(f'{path.name}: {label}={actual} does not match RichChatCards palette ({wanted})')


def report(paths: Sequence[Path], names: Sequence[str]) -> None:
    known = {ident for ident, _, _ in THEMES}
    for path in paths:
        stats = validate_zip(path, names)
        ident = path.name.split('-', 1)[0]
        if ident in known:
            check_palette_source(path, ident)
        for key, (text_min, ui_min) in stats.items():
            print(f'{path.name:22} {key:18} text {text_min:4.2f}  ui {ui_min:4.2f}')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='audit existing ZIPs without rebuilding')
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    names = required_names()
    if args.check:
        report(sorted(args.output_dir.glob('*.zip')), names)
        return
    build(args.output_dir, names)
    report([args.output_dir / f'{ident}-1.0.0.zip' for ident, _, _ in THEMES], names)


if __name__ == '__main__':
    try:
        main()
    except (AttributeError, KeyError, TypeError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f'error: {error}', file=sys.stderr)
        raise SystemExit(1)




