# 视频礼物双版本构建（GitHub Actions / fork）

本文档说明“视频礼物”功能的两个构建产物、如何在 fork 里构建、以及只有原构建者能做的那一步签名。

- 影响范围：只新增 `videoCoexist` / `videoUpdate` 两个构建类型、一个新 workflow、以及 `tools/` 下的验证脚本。
  原有 `debug` / `release` / `performance` / `baseline` 的包名、默认版本和签名决策都没有改动，
  现有 workflow、`app/debug.keystore`、`local.properties` 也没有被覆盖。
- 视频通话/礼物功能代码本身（67 个已交付文件、224 个 feature 测试）没有改动。
- **事实界限**：写这份文档的实现过程没有提交、没有推送、也没有安装任何 APK。
  用户已授权主模型把代码推到用户的 `yuny6617/orangechat` fork，并触发云端构建；
  不直接修改默认分支、不自动发布 Release、不安装 APK。
  本文不假设任何“不允许推送”的约束——下面描述的是 GitHub 上的实际行为。

## 1. 两个版本是什么

| 版本 | 构建类型 | 包名 | 签名 | 能不能装 | 会不会覆盖旧版 |
| --- | --- | --- | --- | --- | --- |
| 共存版 | `videoCoexist` | `me.rerere.orangechat.videogifts` | 共享 debug 密钥（`app/debug.keystore`） | 能，可与正式版并存 | 不会 |
| 更新候选 | `videoUpdate` | `me.rerere.orangechat` | **无签名（unsigned）** | **不能** | 只有原构建者用原证书签名后才会变成“覆盖更新”包 |

关键点：

- 共存版是**独立包名**，所以它可以和已安装的正式版同时存在，桌面名字也不同（`西瓜瓣·视频共存`）。
  它使用公开的共享 debug 密钥，**明确不是发布安全签名**，只适合自己/朋友安装使用。
- 更新候选保持了正式包名 `me.rerere.orangechat`，但默认**没有签名**：
  Android 不会安装 unsigned APK，它也不会覆盖任何已安装应用。
- 本流程**不会**生成新密钥去冒充“覆盖更新”：用别的密钥签出来的同包名 APK
  在 Android 上会安装失败（签名不一致），强行改名/换包名又会丢数据，
  所以在没有原证书的情况下这里只产出 unsigned 候选。
  如果确实持有原证书，则可以按 §4.3 离线签名或按 §4.4 配进 secrets，那样产出的才是真更新包。
- 更新候选的 `versionCode` 默认 **166**（当前已交付正式版是 165），只有 `> 165` 才会被接受，
  这样才能作为“覆盖旧版并保留数据”的更新。

## 2. 本地构建

要求：JDK 17、Android SDK（platform 37.0 / build-tools 37.0.0）、NDK 28.2.13676358、CMake 3.22.1、
Node 24 + pnpm（`web-ui` 依赖），并初始化 `material3/material-color-utilities` 子模块。

```bash
# 共存可安装包
./gradlew :app:assembleVideoCoexist \
  -PorangechatAbis=arm64-v8a -PorangechatUniversalApk=false
# 预期产物: app/build/outputs/apk/videoCoexist/app-arm64-v8a-videoCoexist.apk

# unsigned 更新候选（默认 166）
./gradlew :app:assembleVideoUpdate \
  -PorangechatAbis=arm64-v8a -PorangechatUniversalApk=false
# 预期产物: app/build/outputs/apk/videoUpdate/app-arm64-v8a-videoUpdate-unsigned.apk
# （CI 里用 `find … -name '*arm64-v8a*.apk'` 定位，不依赖具体文件名）

# 指定别的版本码（必须 > 165）
./gradlew :app:assembleVideoUpdate \
  -PorangechatAbis=arm64-v8a -PorangechatUniversalApk=false \
  -PorangechatVideoUpdateVersionCode=170
```

版本码规则（`app/build.gradle.kts`）：

- `-PorangechatVideoUpdateVersionCode` 是本流程专用属性，默认 `166`。
- 只接受纯十进制数字，范围 `166..2100000000`；`165`、`16a`、`16500000000` 之类会直接让 Gradle 配置失败。
- 只作用于 `videoUpdate` variant；其他 variant 的 `versionCode` 仍是 165。
- `BuildConfig.VERSION_CODE` 与 APK 的 `versionCode` 用同一个值生成，`BuildConfig.VERSION_NAME` 仍是 `2.2.9`。
- 正式版以后升到 166 以上时，需要同步修改 `app/build.gradle.kts` 里的
  `videoUpdateBaselineVersionCode`（当前 165），否则“必须 > 基线”的校验会拦住。

注意：`videoUpdate` 复制的是 `release` 的优化/ProGuard 设置，但 `signingConfig = null`，
因此**不会**读取 `local.properties` 里的发布密钥，也不要求配置 release 密钥；
原有 release 的“必须配置 release keystore”强制校验没有放宽。

## 3. 共存版的身份细节

- 包名：`me.rerere.orangechat.videogifts`（`applicationIdSuffix = ".videogifts"`）。
  `debug` 默认的 `.liquidglass` 后缀被显式覆盖，不会被隐式继承。
- 桌面名：`app/src/videoCoexist/res/values/strings.xml` 里的 `app_name_video_coexist`
  （`西瓜瓣·视频共存`），由 `app/src/videoCoexist/AndroidManifest.xml` 覆盖 `android:label`。
  之所以用独立资源名，是因为如果直接改 `app_name`，各语言 `values-*/strings.xml` 里的
  `app_name` 会在中文/日文等语言下把它覆盖回 `西瓜瓣`，两个包在桌面上就分不清了。
  该资源只存在于 `videoCoexist` 源集，主源集和各语言目录都没有同名条目。
- 冲突检查：manifest 里所有 provider authorities（`${applicationId}.shizuku` / `.fileprovider` /
  `.workspace.documents` / `.androidx-startup`）和 taskAffinity（`${applicationId}.applock`）
  都由 `${applicationId}` 占位符推导，共存包会解析到自己的包名，与正式包互不冲突；
  launcher activity 没有显式 taskAffinity，默认按包名隔离。
- 数据：不同包名 → 独立的数据目录/数据库，共存版**不会**共享正式版的聊天数据。
  需要搬数据时只能走应用现有的导出/导入机制，本文不承诺任何跨包自动迁移。
- 签名：`app/debug.keystore`（alias `androiddebugkey`，密码是公开的 `android`），
  证书 SHA-256 为 `ff516315e577235b64ff356dbc856c053b7e589ae636dd8bc4301e528b46d116`。
  公开密钥签出来的包任何人都能伪造，所以共存版只用于个人/朋友安装，不能当作正式发布渠道。

## 4. CI workflow：`.github/workflows/video-gifts-dual.yml`

触发方式：

- `push` 到分支 **`feature/video-call-gifts-ci`**；
- `workflow_dispatch` 手动触发，输入 `update_version_code`，默认 `166`。

安全约束：

- `permissions: contents: read`，没有 `pull_request_target`，不创建/上传 Release，不写仓库。
- 不需要任何 secrets 就能完整跑通并产出共存包与 unsigned 更新候选。
- 输入只通过环境变量进入脚本，并且只接受纯数字，不会拼进 shell 命令。
- 产物名包含 run id 和 commit 短 sha，保留 14 天。
- 私钥（如果配置了 `VIDEO_UPDATE_*` secrets）只还原到 runner 的临时目录：step 内先
  `umask 077`，还原出来的 keystore 权限为 `600`，并用 `trap` 保证成功/失败/中断都会删除；
  密码通过 `apksigner` 的 `env:` 前缀从环境变量读取，不出现在命令行或日志里；
  任何情况下都不上传 keystore / `local.properties` / secret。

流水线步骤：

环境准备（不是新增步骤，属于原有 setup）：用 `sdkmanager` **逐包**安装 SDK
（platform / build-tools / NDK / CMake），每装一个都确认 `platforms/android-*`、`aapt2`、
`apksigner`、`ndk/<版本>`、`cmake/<版本>` 真的存在，不允许“命令成功但工具没装上”。

流水线步骤（保持原来的 7 步范围）：

1. 校验 `update_version_code` 输入（fail-closed）；
2. 跑 `tools/` 下的 Python 单元测试；
3. 跑视频礼物 feature 过滤集（21 个类、**224** 个测试）并用 `tools/verify_feature_tests.py` 校验：
   类缺失、测试数少于 224、有失败/错误/跳过，都会让构建立即失败；
4. 构建 `assembleVideoCoexist` + `assembleVideoUpdate`；
5. 用 `tools/apk_identity.py` 校验产物：
   - 共存包：包名必须是 `me.rerere.orangechat.videogifts`（且不能等于正式包 / `.liquidglass`），
     桌面名必须是 `西瓜瓣·视频共存`，ABI 必须是 `arm64-v8a`，签名者必须恰好是共享 debug 证书
     （出现正式证书直接失败）；
   - 更新候选：包名必须是 `me.rerere.orangechat`，`versionCode` 必须等于输入且 `> 165`，
     ABI 必须是 `arm64-v8a`，**必须 unsigned**——而且必须是**两个独立来源同时证明**的 unsigned：
     `apksigner` 给出 `DOES NOT VERIFY` + 一条在允许列表里的“无签名”诊断，
     并且 ZIP 容器里既没有 `META-INF` 的 JAR 签名条目、也没有 `APK Sig Block 42` 签名块。
     `apksigner` 启动失败 / JVM 崩溃 / APK 解析不了 / 签名损坏，全部按失败处理，不会被当成 unsigned；
   - `videoUpdate` 生成的 `BuildConfig.java` 里的 `VERSION_CODE` / `VERSION_NAME`
     必须与 APK metadata 一致；
6. 如果配置了 `VIDEO_UPDATE_*` secrets，则签名更新候选并校验：
   证书必须等于原正式证书 `49ceec2c9d193a1b515ff7c5aa3a8bb745e2dbcfdb8eb0b99aa184b88472ae64`，
   包名/版本/ABI 必须正确，否则 job 失败且**不会**上传任何“可更新”APK；
   签名 step 里 `umask 077`，私钥还原成 `600`，无论成功/失败/中断都会删掉临时目录；
7. 上传产物 + summary，并按需额外上传 `videoUpdate` 的 R8 mapping（`mapping.txt`、
   `configuration.txt`、`seeds.txt`、`usage.txt`、`resources.txt`）作为**独立 artifact**，
   供崩溃栈回溯。它只是构建产物，不含任何密钥/凭证，也不包含 `local.properties`。

summary 按“实际发生了什么”分四种情况写，不会笼统声称“只产出候选 / 没有原私钥”：

| 实际情况 | summary 的说法 |
| --- | --- |
| 测试/构建/校验没走完，没有任何产物 | 没有可下载的 APK 产物；即便校验失败时磁盘上有 APK 也不上传 |
| 共存包 + unsigned 候选，未配置签名密钥 | “更新版尚不可安装”，并说明本仓库没有配置原证书私钥 |
| 配置了密钥但签名/证书校验失败 | 明确写“已配置密钥，但签名或证书校验失败”，并指出这与“没有原私钥”无关 |
| 用原证书签名且校验通过 | 给出已签名更新包的 metadata，不再出现“尚不可安装” |

关于本仓库已有的其他 workflow：它们没有被修改，仍然照旧使用 `KEY_BASE64` / `SIGNING_CONFIG`。
本 workflow 不会读取这两个 secret，也不会写入 `app/app.key` 或 `local.properties`。

### 4.1 朋友的 fork 怎么跑

1. 在 GitHub 网页上 fork `yuny6617/orangechat`。fork 页面上默认勾选了
   **“Copy the `main` branch only”（只复制默认分支）**，这里**一定要取消勾选**，
   让它把上游所有分支都复制过来；否则 fork 里只有默认分支，
   `feature/video-call-gifts-ci`（workflow 文件所在分支）根本不存在。
2. 如果 fork 时不小心只复制了默认分支，或者想在自己的 clone 里补齐，就在**自己的 clone** 里从上游取一次：

   ```bash
   # 在自己 fork 出来的 clone 里执行
   git remote add source https://github.com/yuny6617/orangechat.git
   git fetch source feature/video-call-gifts-ci
   git switch -c feature/video-call-gifts-ci source/feature/video-call-gifts-ci
   git push -u origin feature/video-call-gifts-ci
   ```

   如果这个 clone 里**已经有** `feature/video-call-gifts-ci` 分支，就不要重复 `switch -c`
   去创建同名分支（会报 “already exists”）；先 `git switch feature/video-call-gifts-ci`，
   确认它已经包含上游最新提交（必要时 `git merge --ff-only source/feature/video-call-gifts-ci`），
   然后直接 `git push -u origin feature/video-call-gifts-ci` 即可。

   **不要**用 `git clone --mirror` 再 `git push --all` 这种办法：`--mirror` 复制的是你**自己 fork**
   现有的引用，上游本来就没有的分支它变不出来，所以补不齐 `feature/video-call-gifts-ci`；
   而且 mirror push 会连同 tags、remote-tracking 引用一起强推，可能覆盖/删除 fork 里已有分支。
   同样**不要** `git push --force`（强推会改写别人的提交历史）。

   只需要 `feature/video-call-gifts-ci` 这一个分支就够了，不必搬运所有分支。
3. 在 fork 的 **Actions** 页面点 “I understand my workflows, go ahead and enable them” 启用 Actions
   （fork 默认是禁用状态）；
4. 触发方式二选一：
   - **首次 push 新分支就能触发**：`git push -u origin feature/video-call-gifts-ci` 本身就是一次
     `push` 事件，workflow 文件只要在该分支上就会跑；
   - 想用界面上的 **Run workflow** 按钮（`workflow_dispatch`）时，workflow 文件必须已经存在于
     fork 的**默认分支**上——GitHub 只从默认分支解析可手动触发的工作流。文件只在
     `feature/video-call-gifts-ci` 上时按钮不会出现，此时就用上面 `push` 的方式，别干等按钮。
5. secrets 与签名能力（fork **不会继承**上游仓库的 secrets）：
   - 共存包照常构建（用仓库里已有的 `app/debug.keystore`，不需要额外 secret）；
   - 更新候选保持 unsigned，**不能安装**；
   - fork 可以配置自己的 `VIDEO_UPDATE_*`。**能不能产出“真正的覆盖更新”只取决于证书是不是原证书**，
     不取决于“fork 还是上游”：如果配置的确实是原发布证书，签出来的包会通过
     `verify-signed-update` 的严格校验（证书 SHA-256 必须等于
     `49ceec2c9d193a1b515ff7c5aa3a8bb745e2dbcfdb8eb0b99aa184b88472ae64`），
     那就是可以覆盖旧版、保留数据的更新包；
     如果配置的是别的密钥，签名 step 会**直接失败**，不会上传任何“可更新”APK——
     不是“允许但别用”，而是校验就会拦住。
   - 私钥不一定要放进 fork：最稳的做法是 fork 里**不配**任何签名 secret（只产出 unsigned 候选），
     由持有原私钥的原构建者按 §4.3 在自己机器上离线签名。

### 4.2 新渠道不会自动更新

应用内的更新检查指向的是上游正式仓库的发布地址，本 workflow **没有**改 updater，
也**不会**给 fork 建 Release。因此：

- 从 fork 构建的共存版/更新候选，不会被任何渠道自动下载到手机上；
- 想升级只能手动下载对应产物再安装；
- 不要指望在旧版 App 里点“检查更新”就能拿到这两个包。

### 4.3 原作者/持有原私钥的人：签名步骤

拿到 CI 产出的 `app-arm64-v8a-videoUpdate-unsigned.apk` 后，在自己的机器上离线签名：

```bash
# 密码用环境变量给出，不要写在命令行里
export KS_PASS='...'
export KEY_PASS='...'
apksigner sign \
  --ks original-release.jks \
  --ks-key-alias '<原 alias>' \
  --ks-pass env:KS_PASS \
  --key-pass env:KEY_PASS \
  --out OrangeChat-2.2.9-video-update-166-signed.apk \
  app-arm64-v8a-videoUpdate-unsigned.apk

# 必须通过：证书、包名、versionCode、ABI 全部匹配才算可安装更新
python tools/apk_identity.py verify-signed-update \
  --apk OrangeChat-2.2.9-video-update-166-signed.apk \
  --expected-version-code 166
```

- 离线签名的私钥**不需要**离开原构建者的机器，也**不需要**放进 fork/CI：
  只有“想让 CI 直接产出已签名包”时才需要配置 §4.4 的 secrets。
- `verify-signed-update` 固定校验证书 SHA-256 =
  `49ceec2c9d193a1b515ff7c5aa3a8bb745e2dbcfdb8eb0b99aa184b88472ae64`，
  用错密钥会直接失败，不要跳过这一步。
- 安装更新版时**不要先卸载旧版**，否则数据会丢；直接覆盖安装（同包名、签名一致、versionCode 更大）。
- 共存版和更新版是两个不同包名，互不影响；卸载其中一个不会动另一个的数据。

### 4.4 可选：在 CI 里自动签名

如果（且仅当）本仓库配置了下面四个**新名字**的 secrets，workflow 会自动签名并上传签名版：

| secret | 内容 |
| --- | --- |
| `VIDEO_UPDATE_KEYSTORE_BASE64` | 原 keystore（JKS/PKCS12）的 base64 |
| `VIDEO_UPDATE_KEYSTORE_PASSWORD` | keystore 密码 |
| `VIDEO_UPDATE_KEY_ALIAS` | 原密钥 alias |
| `VIDEO_UPDATE_KEY_PASSWORD` | 密钥密码 |

- 不要用原来的 `KEY_BASE64` / `SIGNING_CONFIG` 来承载这里的私钥，也不要把这些 secret 提交到仓库。
- 没配置这些 secrets 时，签名 step 会被跳过，流水线照样成功，只上传 unsigned 候选。
- 签名后仍然会做证书/包名/版本/ABI 校验；只要不匹配，job 失败且不上传“可更新”包。
- 没有单独的手动签名 workflow：签名只是同一条流水线里一个受 secrets 控制的可选 step，
  避免为了签名再复制一遍整套构建。原作者离线签名请用 §4.3 的命令。

## 5. 验证工具

### `tools/apk_identity.py`

解析 `aapt2 dump badging` 与 `apksigner verify --print-certs` 的输出，并额外扫描 ZIP 容器，做 fail-closed 校验；
证书摘要必须完整 64 位十六进制、按 SHA-256 全值比较（不做子串包含比较）。

“unsigned” 从来不是靠“没输出 / 退出码 1”推断出来的，必须要两个独立来源同时肯定：

1. `apksigner` 明确打印 `DOES NOT VERIFY`，并且诊断恰好在最小允许列表里
   （当前只有真实 unsigned APK 会输出的 `ERROR: Missing META-INF/MANIFEST.MF`）；
2. ZIP 容器里既没有 `META-INF/MANIFEST.MF`、`*.SF`、`*.RSA`、`*.DSA`、`*.EC` 这类 JAR(v1) 签名条目，
   最后一条 ZIP 记录与中央目录之间也没有 `APK Sig Block 42` 签名块（v2/v3/v4），
   而且这段间隙必须为 0 字节或正好是一个可识别的签名块——其它任何字节都算错误。

因此下面这些情况一律**不会**被当成 unsigned，而是直接失败：

| 情况 | 结果 |
| --- | --- |
| `apksigner` 退出 1 且输出为空 | 失败（`apksigner exited 1 without a 'DOES NOT VERIFY' verdict`） |
| JVM 启动失败（`JAVA_HOME is not set…`） | 失败（exit 2，工具错误） |
| JVM 崩溃 / APK 解析不了（`Exception in thread "main" … Malformed APK`） | 失败（exit 2） |
| 签名损坏（`DOES NOT VERIFY` + `APK integrity check failed`） | 失败（诊断不在允许列表里） |
| 证书摘要被截断/被篡改/大写 | 失败（malformed） |
| APK 带 v1 或 v2/v3/v4 签名 | 失败（ZIP 证据与 apksigner 都会报） |
| ZIP 读不了 / 尾部有无法解释的字节 | 失败 |

```bash
python tools/apk_identity.py inspect --apk <apk>
python tools/apk_identity.py verify-coexist --apk <apk>
python tools/apk_identity.py verify-update-candidate --apk <apk> --expected-version-code 166
python tools/apk_identity.py verify-signed-update --apk <apk> --expected-version-code 166
python tools/apk_identity.py check-buildconfig \
  --file app/build/generated/source/buildConfig/videoUpdate/me/rerere/rikkahub/BuildConfig.java \
  --expected-version-code 166 --expected-version-name 2.2.9
```

`--sdk-root`（或 `ANDROID_HOME` / `ANDROID_SDK_ROOT`）用来定位 `aapt2` / `apksigner`，
也可以用 `AAPT2` / `APKSIGNER` 环境变量或 `--aapt2` / `--apksigner` 直接指定。
`inspect` 会输出包名、版本、ABI、证书摘要和 APK 的 SHA-256，方便贴到 artifact 说明里。

### `tools/verify_feature_tests.py`

```bash
python tools/verify_feature_tests.py --results app/build/test-results/testDebugUnitTest
```

校验 21 个 feature 测试类都跑了、总数不少于 224、没有失败/错误/跳过。

### 单元测试

```bash
python -m unittest discover -s tools -p 'test_apk_identity.py'
python -m unittest discover -s tools -p 'test_verify_feature_tests.py'
```

## 6. 已知边界（不假装通过）

- 完整单元测试套件原本就有 2 个失败（与视频礼物功能无关），Lint 基线也有 1 个既有错误。
  本次**没有**删除断言、没有放宽 Lint baseline，也**不声称**全套测试是绿的；
  CI 只跑已审的 224 个 feature 测试，并把它当作阻断条件。
- 本 workflow 不做 `assembleDebug` / `assembleRelease`，也不跑 Lint；原有 workflow 的职责没变。
- fork 只要没有原私钥就产不出“可覆盖更新”的包，这是签名数学决定的，不是配置问题；
  但“原私钥”和“fork”是两件事——私钥可以只留在原构建者机器上离线签（§4.3），
  也可以配进 fork 的 secrets（§4.4），只要证书是原证书就能通过校验。
- workflow 里的 shell 逻辑（输入校验、逐包安装与落地检查、summary 四种状态、签名 step 的
  `umask 077` 与 cleanup）已经在真实的 bash 里跑过；
  完整的 `assembleVideoCoexist` / `assembleVideoUpdate` 是本地 Gradle 跑过的（两个 variant 均 BUILD SUCCESSFUL），
  GitHub runner 上的同一套构建与真机安装**没有**在本次实现中执行，不声称已验证。
- 两个真实产物（`app/build/outputs/apk/videoCoexist/app-arm64-v8a-videoCoexist.apk`、
  `app/build/outputs/apk/videoUpdate/app-arm64-v8a-videoUpdate-unsigned.apk`）
  已用 `tools/apk_identity.py` 实际校验通过：前者是共享 debug 证书的 v2/v3 签名，
  后者被两个来源同时确认 unsigned。
- R8 mapping（尤其 `mapping.txt`）体积可观；它作为独立 artifact 上传，只用于崩溃回溯，
  不含密钥，也不要把它和 APK 混在同一个 artifact 里。
