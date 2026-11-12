# localdata Robolectric 原生 SQLite 路径上限诊断

日期：2026-11-11  
范围：`app/src/main/java/me/rerere/rikkahub/plugin/localdata/**`、
`app/src/test/java/me/rerere/rikkahub/plugin/localdata/**`（本文件只记录证据，未改生产行为）。

## 1. 结论

lead 的 `:app:testDebugUnitTest` 中 27 个 localdata 失败全部是**同一个宿主路径问题**，不是 localdata
SQL 或 ACL 逻辑错误：

- Robolectric 4.16.1 默认 `SQLiteMode.NATIVE`（不是 LEGACY），真正走
  `ShadowNativeSQLiteConnection.nativeOpen` → 原生 Windows SQLite。
- Robolectric 每个测试方法都会新建临时目录，目录名 =
  `<测试类简单名>_<测试方法名>`（非法字符替换为 `_`，再截断到 120 字符），
  dataDir = `<临时目录>/<packageName>-dataDir`。
- localdata 的数据库名是 `localdata_v1_<sha256(pluginId) 前 32 hex>.db`（48 字符），
  又放在 `<临时目录>/<packageName>-dataDir/databases/` 下。
- localdata 测试方法名普遍 60–95 字符（最长 `restore regenerates ids and preserves posts comments likes drafts and media`，
  清理后临时目录名 95 字符）。临时目录名 + dataDir + 数据库名 + 随机后缀叠加后超过
  **Windows native SQLite 的 MAX_PATH 限制**。
- `SQLiteOpenHelper` 首次打开新库要在 `onCreate` 里建表并创建回滚日志 `<db>-journal`；
  数据库文件路径达到 252 字符时，`<db>-journal` 达到 260 字符，`sqlite3_open_v2` 直接返回
  `SQLITE_CANTOPEN (code 14)`。

所以：

- 生产 `LocalDataDatabase` 里的 `getDatabasePath(...).parentFile?.mkdirs()` 是对的，**不需要改**；
  父目录存在，失败原因是文件路径本身超长。
- 27 个失败中 10 个直接以 `SQLiteCantOpenDatabaseException` 暴露（测试直接调 `importMedia`），
  14 个显示 `IllegalStateException: Internal error`，因为
  `LocalDataDispatcher.dispatch` 的 `catch (error: Exception)` 把所有底层异常统一映射成
  `INTERNAL / "Internal error"`；另有 1 个 `ComparisonFailure`（`not_found` 变 `internal`）和
  2 个 `AssertionError`（期望成功/拒绝的写操作拿到 `internal`）。它们都是第一次打开数据库就失败。
- 独立 JUnit 之所以 40 项全过：`TASK5_REPORT.md` 已记录，当时给 JVM 加了**短**
  `-Djava.io.tmpdir`；不是 SQLite 模式不同。

## 2. 关键证据

### 2.1 确实是 NATIVE，不是 LEGACY

失败栈顶：

```
android.database.sqlite.SQLiteCantOpenDatabaseException: unknown error (code 14 SQLITE_CANTOPEN)
    at org.robolectric.nativeruntime.SQLiteConnectionNatives.nativeOpen(Native Method)
    at org.robolectric.shadows.ShadowNativeSQLiteConnection.lambda$nativeOpen$0(...)
    ...
    at android.database.sqlite.SQLiteOpenHelper.getReadableDatabase(...)
    at me.rerere.rikkahub.plugin.localdata.LocalDataDatabase.readable(LocalDataDatabase.kt:92)
```

Robolectric 4.16.1 的 `SQLiteModeConfigurer` 默认值就是 `SQLiteMode.Mode.NATIVE`
（`javap` 字节码：`SingleValueConfigurer(..., Mode.NATIVE, ...)`）。因此不需要、也不应该用
`robolectric.sqliteMode=LEGACY` 绕过。

### 2.2 原生 SQLite 的 Windows 路径上限是 251 字符（加 `-journal` 后 260）

用与 native SQLite 相同的 Windows OS 层做独立探针（Python 3.53.1 sqlite3，非 Gradle、非本仓库代码）：

```
dbpath=250 journal=258 -> ok
dbpath=251 journal=259 -> ok
dbpath=252 journal=260 -> OperationalError code=14 SQLITE_CANTOPEN
```

同一 JVM 的 Java `FileOutputStream` 在 250 / 260 / 270 / 280 / 300 字符都能写成功
（JDK 17 在 Windows 上支持长路径）。这解释了为什么同目录的媒体文件没有先失败，只有 native SQLite 失败。

### 2.3 失败列表与临时目录名长度严格相关

按实际测试结果统计（`app/build/test-results/testDebugUnitTest/TEST-*localdata*.xml`）：

| 测试 | 清理后临时目录名长度 | 是否碰数据库 | 结果 |
|---|---:|---|---|
| `LocalDataMediaTest_duplicate_bytes_reuse_the_same_resource_id` | 61 | 是 | 通过 |
| `LocalDataBackupTest_assistant_id_map_rewrites_authors_on_restore` | 64 | 是 | 通过 |
| `LocalDataAclTest_comment_reply_must_belong_to_the_same_visible_post` | 67 | 是 | `internal` |
| `LocalDataBackupTest_unsupported_backup_version_is_rejected_before_writing` | 73 | 是 | `internal` |
| `LocalDataMediaTest_clearing_the_cache_removes_thumbnails_but_never_published_originals` | 86 | 是 | `CANTOPEN` |
| `LocalDataBackupTest_restore_regenerates_ids_and_preserves_posts_comments_likes_drafts_and_media` | 95 | 是 | `CANTOPEN` |
| `LocalDataIdentityTest_version_method_and_payload_errors_are_stable` | 66 | 否 | 通过 |
| `LocalDataIdentityTest_identity_fields_in_params_or_root_are_rejected` | 68 | 否 | 通过 |
| `LocalDataIdentityTest_selected_audience_must_reference_existing_assistants` | 74 | 否 | 通过 |

即：所有**实际打开数据库**且目录名 ≤64 的用例通过；目录名 ≥67 的打开数据库用例全部失败；
不打开数据库的纯参数校验用例不受影响。按临时目录名 64→95 与随机后缀 19–20 反推，
失败边界正好落在 DB 路径约 250（通过）/ 252–253（CANTOPEN）附近，与 2.2 的原生阈值一致。

### 2.4 仓库里已有同一问题的 workaround 先例

- `TranslationHistoryBackupTest`：`// Short names keep Robolectric's class/method-based temp paths usable by native Windows SQLite.`
- `TranslationHistoryMigrationTest`：`// Robolectric places the class and method names in the temporary path. Native Windows SQLite needs short test/database names so journal files stay within its path limit.`
- `CompressionSchemaMigrationTest`：`// Robolectric uses the method name in its temporary directory. Keep filenames short for native Windows SQLite.`

这些测试把数据库名缩到 `c-<8>` / `ts-<8>` 等，并打印 `SQLite path length`，说明该路径上限是已知的宿主限制。

## 3. 正确修复：给统一 Gradle 测试 JVM 一个短的 `java.io.tmpdir`

**必须在 JVM 启动参数里设置，不能在测试代码里运行时改**：`java.nio.file.TempFileHelper` 会在第一次
使用临时目录时缓存 `java.io.tmpdir`，之后再 `System.setProperty` 无效（已实测：第一次
`Files.createTempDirectory` 之后再设属性，第二次仍用旧目录）。

Gradle 9.4.1 的 `org.gradle.process.internal.JvmOptions` 把 `java.io.tmpdir` 列在
`IMMUTABLE_SYSTEM_PROPERTIES` 中；`Test` 任务的 `systemProperty` 正是把值放进
`immutableSystemProperties`，fork test worker 时以 `-Djava.io.tmpdir=...` 传给 JVM。
所以下面的 init script 写法是 Gradle 支持的入口。

在 lead 的 `workspace-robolectric.init.gradle`（或等价 init script）中加入：

```groovy
def shortTmp = new File('D:/DSH-Work/rt')
shortTmp.mkdirs()
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        systemProperty 'java.io.tmpdir', shortTmp.absolutePath
        // 现有：
        systemProperty 'user.home', 'D:/DSH-Work/android-build-tools/test-home'
        systemProperty 'maven.repo.local', 'D:/DSH-Work/android-build-tools/maven-local'
        systemProperty 'robolectric.dependency.repo.url', 'https://repo.maven.apache.org/maven2'
    }
}
```

估算（最坏用例：临时目录名 95、随机后缀 20、dataDir 名 34、数据库名 48）：

```
14 (D:/DSH-Work/rt) + 25 (固定分隔/robolectric-//databases/) + 95 + 20 + 34 + 48 = 236
加 "-journal" 8 = 244 < 260
```

即使 dataDir 名再长几个字符也有余量。目录必须存在（`mkdirs()`），否则
`Files.createTempDirectory` 会因 tmpdir 不存在而失败。

不要用 `JAVA_TOOL_OPTIONS` 作为替代：Gradle 会把它自己的 `java.io.tmpdir` 作为
worker 启动参数传给 JVM，命令行 `-D` 会覆盖 `JAVA_TOOL_OPTIONS`。用 `systemProperty`
（或 `jvmArgs`）才是可靠入口。

**不要**：

- 不要设 `robolectric.sqliteMode=LEGACY`：那会把 native SQLite 换成 legacy 假实现，掩盖真实 SQLite 行为。
- 不要在 `LocalDataDatabase` 里吞 `SQLiteCantOpenDatabaseException` 或退回内存库。
- 不建议为适配 Windows 测试把生产数据库名缩短到 32/48-bit hash：会削弱插件命名空间隔离，
  而且最坏路径仍可能不够。

## 4. 复现/验证

1. 用上面的 init script 重跑 lead 的统一命令（`--tests '*localdata*'`），预期 27 项恢复通过。
2. 如仍失败，打印 `context.getDatabasePath(databaseName).absolutePath` 及其 `+ "-journal"` 的字符数，
   确认数据库文件路径 ≤251、journal 路径 ≤259。
3. 独立诊断脚本（不在仓库源码内）：
   - `app/build/diag/sqlite_path_probe2.py`：原生 SQLite 251/252 阈值。
   - `app/build/diag/LongPathProbe.java`：JDK 17 长路径文件写入对照。
