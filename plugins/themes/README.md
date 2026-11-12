# Theme package schema v1

This directory is declaration-only. A package is a ZIP with `manifest.json` plus one
`themes/<id>.json` per declared theme. Theme JSON accepts only the complete, whitelisted
token set in `ThemePackage.kt` and literal `#RRGGBB` / `#AARRGGBB` values; scripts,
resources, images and executable code are not part of the format.

## Validation boundary

`ThemePackageReader` reads ZIP entries in memory and never extracts an archive path to
disk. It rejects:

- more than 25 entries, an entry expanding past 128 KB, a total expansion past 512 KB,
  or an archive file larger than 512 KB;
- directory entries, absolute/backslash/dot-segment names, non-`.json` entries, duplicate
  entry names, undeclared or missing files;
- duplicate JSON object keys, including keys that only become equal after escapes
  (`"background"` vs `"back\u0067round"`);
- invalid UTF-8, a leading byte-order mark, unknown fields, incomplete token maps,
  non-`#RRGGBB`/`#AARRGGBB` colours, and shape values outside `0..32dp`.

## The three delivered packages

`tools/build_local_theme_packages.py` reads the four literal colours of the ice, sakura and
lavender branches of `interactionColors()` in `RichChatCards.kt` and writes:

```
build/local-theme-packages/ice-1.0.0.zip
build/local-theme-packages/sakura-1.0.0.zip
build/local-theme-packages/lavender-1.0.0.zip
```

The script also refuses to build if a required token is missing or if any audited
text/background pair falls below 4.5:1 (3:1 for outlines/borders). Run it with
`--check` to audit existing ZIPs without rebuilding. These packages are generated build
artifacts; they are not committed to this directory.

`theme-schema-v1/` is a complete, loadable template with neutral placeholder colours. Its
values are not OrangeChat presets; do not ship it as a theme. The script regenerates
`themes/token-template.json` so the template stays in sync with the token schema.

## What is wired

- Package colours are applied app-wide through `CustomTheme.packageTokens` when the package
  theme is selected and dynamic colour is off; `Theme.kt` already passes
  `ThemeTokens.toShapes()` to `MaterialExpressiveTheme`, and the import preview applies the
  same colour/shape mapping.
- Background image, chat background colour, explicit primary colour and explicit global text
  colour keep priority because they are applied after the package scheme.

- `LocalPackageSurfaceTokens` applies `cardBackground`, `cardBorder` and `mutedText` to shared
  settings cards. Drawer and conversation list surfaces consume card/selection/border tokens;
  the drawer panel consumes the static shadow tint. Explicit text and opacity settings retain priority.

## Remaining coverage

- `RichChatCards` still draws its glass card gradients from its own hardcoded palette; the
  packages reuse those exact colours, but the cards are not re-coloured by the selected theme.
