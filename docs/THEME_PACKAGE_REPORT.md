# 主题包增量报告

日期：2026-11-11。工作树保持未提交状态；本增量未运行 Gradle、未提交、未重置。

## 结论

- `ThemePackageReader` 现在在 kotlinx.serialization 解码前做严格 JSON 扫描，拒绝任意层级的重复
  key，包括转义后相同的 key（如 `"background"` 与 `"back\u0067round"`、`"A"` 与 `"\u0041"`）。
- reader 原有纯内存、bounded ZIP 逻辑未重写；补了 UTF-8/BOM、超限、解压炸弹、路径攻击、重复/缺失
  entry 的回归。
- 三套主题包已重新生成，颜色严格取自 `RichChatCards.interactionColors()` 的 ice/sakura/lavender
  分支，并经过完整 reader 读取验证与对比度审计（正文 ≥4.5:1，描边/边框 ≥3:1）。
- 形状 token 已在现有未提交的 `Theme.kt` 中接线（`shapes = packageTokens?.toShapes() ?: Shapes()`）；
  本增量补上了其引用的 `ThemeTokens.toShapes()`，因此这条接线现在可编译。预览也套用同一形状映射。
- 背景图、聊天背景色、显式主色、显式全局文字色仍由 `Theme.kt` 在包配色之后覆盖，优先级不变。

## 改动的文件

- `app/src/main/java/me/rerere/rikkahub/theme/packageio/StrictJson.kt`（新增）
- `app/src/main/java/me/rerere/rikkahub/theme/packageio/ThemePackageReader.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/theme/PackageThemeColors.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/ThemePackageImport.kt`
- `app/src/test/java/me/rerere/rikkahub/theme/packageio/StrictJsonTest.kt`（新增）
- `app/src/test/java/me/rerere/rikkahub/theme/packageio/ThemePackageReaderTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/theme/PackageThemeColorsTest.kt`（新增）
- `tools/build_local_theme_packages.py`
- `plugins/themes/README.md`
- `plugins/themes/theme-schema-v1/themes/token-template.json`（由脚本补全为完整 token 模板）

未改动的共享文件：`Theme.kt`、`CustomTheme.kt`、`SettingThemePage.kt`、`RichChatCards.kt`、`Color.kt`。

## 生成物与对比度

脚本输出：

```
build/local-theme-packages/ice-1.0.0.zip
build/local-theme-packages/sakura-1.0.0.zip
build/local-theme-packages/lavender-1.0.0.zip
```

本次审计结果（最小正文/最小 UI 对比度）：

| 包 | light | dark |
| --- | --- | --- |
| ice | 4.58 / 3.11 | 6.38 / 5.98 |
| sakura | 4.66 / 3.06 | 6.39 / 5.49 |
| lavender | 5.48 / 3.22 | 6.34 / 5.29 |

每个包的 light/dark 都由同一组真实卡片色派生；dark 背景带各自色相，不再是三套完全相同的
`#17191F`。脚本在构建时会断言 ZIP 中的 `light.background`、`light.primary`、
`light.surfaceVariant`、`light.secondary`、`dark.primary` 与 `RichChatCards` 源颜色一致，
不一致直接失败。

## 已验证

未运行 Gradle（用户要求禁止）。用 `android-build-tools/kotlin-jvm` 的独立 Kotlin 编译器对
theme packageio 与 Compose 颜色映射做了 JVM 编译和 JUnit 运行：

- `StrictJsonTest` + `ThemePackageReaderTest`：50 项通过。
- `PackageThemeColorsTest`：9 项通过。
- 用真实 `ThemePackageReader` 读取三套生成的 ZIP 与 `plugins/themes/theme-schema-v1` 模板，
  全部通过并输出预期 light/dark/shape 值。

## 待接线 / 需 lead 决定的共享文件

1. **恢复默认主题应恢复导入前偏好**（`SettingThemePage.kt`，共享文件，未改）。
   当前恢复固定写 `themeId = "sakura", dynamicColor = false`，会覆盖用户导入前的显式动态色选择。
   建议在 `SettingThemePage` 增加快照：

   ```kotlin
   var restoreThemeId by remember { mutableStateOf<String?>(null) }
   var restoreDynamicColor by remember { mutableStateOf(false) }
   // onApply 内、首次导入时：
   if (restoreThemeId == null) {
       restoreThemeId = settings.themeId
       restoreDynamicColor = settings.dynamicColor
   }
   // onRestore：
   val target = restoreThemeId
   if (target != null) {
       vm.updateSettings(settings.copy(themeId = target, dynamicColor = restoreDynamicColor))
       restoreThemeId = null
   } else {
       vm.updateSettings(settings.copy(themeId = "sakura", dynamicColor = false))
   }
   ```

2. **预览状态位于 LazyColumn item 内**（`SettingThemePage.kt` / `ThemePackageImport.kt`）。
   滚动把 import item 移出组合区时，`rememberCoroutineScope` 会取消正在进行的校验，`preview`
   也会丢失。建议把 `ThemePackageImport` 移到 `LazyColumn` 之外，或把预览/读取状态提升到
   ViewModel（低内存方案：只保存缓存 ZIP 路径，不保存 token 大对象）。本增量只修了：
   换包时重置深色开关、失败时清空预览、预览/应用/取消不写设置。

3. **扩展 token 仍未被任何 UI 消费**（`PackageThemeColors.kt` 已完整校验并保留在
   `CustomTheme.packageTokens`）。`cardBackground`、`cardBorder`、`mutedText`、`selected`、
   `shadow` 目前没有 Material 3 槽位。建议下一步在 `Theme.kt` 增加
   `LocalPackageSurfaces`，由 `Color.kt` 的 `CustomColors`/通用 Card 消费；在此之前不应声称
   “全局卡片配色”已由包驱动。

4. **聊天卡片仍是硬编码玻璃色**（`RichChatCards.kt`，共享文件）。三套包的配色与卡片实际色值
   完全一致，但 `interactionColors()` 仍按卡片自身 theme 取硬编码颜色，不读取当前全局主题。
   若要求“切主题即改卡片”，需要把该 palette 提升为共享 Kotlin 常量并让卡片从主题包取色；
   这会改变卡片现有语义，需 lead 决定。

5. **预览的显式覆盖逻辑与 `Theme.kt` 重复**（`ThemePackageImport.kt` 私有 helper）。建议把
   `primaryColor`/`globalTextColor` 覆盖抽到 `PackageThemeColors.kt` 的公共函数，`Theme.kt`
   与预览共用，避免漂移；本增量未改共享 `Theme.kt`。

## 测试命令建议

无需 Gradle 的主题包构建与审计：

```powershell
cd D:\DSH-Work\OrangeChat
python tools\build_local_theme_packages.py
python tools\build_local_theme_packages.py --check
```

由 lead 统一运行 Gradle（本增量未运行）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ThemePackage*' --tests '*StrictJson*' --tests '*PackageThemeColors*' --no-daemon --console=plain --init-script D:\DSH-Work\orangechat-admin\portable-test-cache.init.gradle
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain --init-script D:\DSH-Work\orangechat-admin\portable-test-cache.init.gradle
```

## 未验证边界

- 没有运行 Gradle 编译整包；`ThemePackageImport.kt` 的 Compose 编译未在真实 app classpath 下验证。
- 没有真机/模拟器 UI 测试，未验证不同字体缩放、深色系统栏、RTL 下预览实际观感。
- 三套 ZIP 已在 JVM 下被真实 reader 读取，但未安装到手机、未走系统文件选择器实测。
