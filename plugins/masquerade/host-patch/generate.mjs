/*
 * 从橘瓣源码生成「加号菜单」最小补丁。
 * 用法：node host-patch/generate.mjs [--src=<...>/FilesPicker.kt] [--keep-before]
 * 只做四处精确插入；锚点不唯一时立即失败，绝不猜位置。
 * 默认只产出补丁后的完整文件，不留中间产物；需要重算 unified diff 时才用 --keep-before
 * 写出 FilesPicker.kt.before，用完请自行删除。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HOST_DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HOST_DIR, '..');
const DEFAULT_SRC = path.join(ROOT, '..', '_ref', 'orangechat', 'app', 'src', 'main', 'java',
  'me', 'rerere', 'rikkahub', 'ui', 'components', 'ai', 'FilesPicker.kt');
const arg = process.argv.find((a) => a.startsWith('--src='));
const SRC = arg ? arg.slice(6) : DEFAULT_SRC;

const original = fs.readFileSync(SRC, 'utf8');
/* Windows checkout 常见 CRLF：保持原文件换行风格，避免整文件 diff。 */
const EOL = original.indexOf('\r\n') >= 0 ? '\r\n' : '\n';
const fit = (s) => s.split('\n').join(EOL);

const IMPORTS_ANCHOR = 'import me.rerere.rikkahub.ui.hooks.ChatInputState\n';
const IMPORTS_ADD = 'import androidx.lifecycle.compose.collectAsStateWithLifecycle\n' +
  'import me.rerere.rikkahub.plugin.manager.PluginManager\n' +
  'import org.koin.compose.koinInject\n';

const VM_ANCHOR = '    val toaster = LocalToaster.current\n';
const VM_ADD = '    val pluginManager = koinInject<PluginManager>()\n' +
  '    val installedPlugins by pluginManager.plugins.collectAsStateWithLifecycle()\n' +
  '    /* 可插拔：只有装好并启用「假面舞会」插件，加号里才出现入口。 */\n' +
  '    val masqueradeInstalled = installedPlugins.any {\n' +
  '        it.manifest.id == "com.orangechat.masquerade" && it.isEnabled\n' +
  '    }\n';

const BUTTON_ANCHOR = '            BigIconTextButton(icon = { Icon(androidx.compose.ui.res.painterResource(R.drawable.action_transfer), null) }, text = { Text("转账") }) { sendGift("transfer") }\n';
const BUTTON_ADD = '            if (masqueradeInstalled) {\n' +
  '                MasqueradeButton(onClick = {\n' +
  '                    onDismiss()\n' +
  '                    navController.navigate(\n' +
  '                        Screen.PluginWebView(\n' +
  '                            pluginId = "com.orangechat.masquerade",\n' +
  '                            entryPath = "ui/index.html",\n' +
  '                            conversationId = conversation.id.toString()\n' +
  '                        )\n' +
  '                    )\n' +
  '                })\n' +
  '            }\n';

const GONGDU_ANCHOR = '@Composable\nfun GongduButton(onClick: () -> Unit = {}) {\n' +
  '    BigIconTextButton(icon = {\n' +
  '        Icon(HugeIcons.Tv01, null)\n' +
  '    }, text = {\n' +
  '        Text("共度")\n' +
  '    }) {\n' +
  '        onClick()\n' +
  '    }\n' +
  '}\n';
const GONGDU_ADD = '\n/** 假面舞会插件入口：宿主只负责导航，剧情与状态全在插件包里。 */\n' +
  '@Composable\n' +
  'fun MasqueradeButton(onClick: () -> Unit = {}) {\n' +
  '    BigIconTextButton(icon = {\n' +
  '        Text("🎭", fontSize = 26.sp)\n' +
  '    }, text = {\n' +
  '        Text("假面舞会")\n' +
  '    }) {\n' +
  '        onClick()\n' +
  '    }\n' +
  '}\n';

const STEPS = [
  ['imports', IMPORTS_ANCHOR, IMPORTS_ADD],
  ['viewmodel state', VM_ANCHOR, VM_ADD],
  ['plus button', BUTTON_ANCHOR, BUTTON_ADD],
  ['button composable', GONGDU_ANCHOR, GONGDU_ADD],
];

let output = original;
for (const [label, anchor, addition] of STEPS) {
  const a = fit(anchor);
  const occurrences = output.split(a).length - 1;
  if (occurrences !== 1) {
    throw new Error(`锚点 ${label} 出现 ${occurrences} 次（应为 1 次），拒绝生成补丁`);
  }
  output = output.replace(a, a + fit(addition));
}

const checks = [
  ['MasqueradeButton(onClick', 2],
  ['fun MasqueradeButton', 1],
  ['masqueradeInstalled', 2],
  ['Screen.PluginWebView', 1],
  ['com.orangechat.masquerade', 2],
];
for (const [needle, expected] of checks) {
  const count = output.split(needle).length - 1;
  if (count !== expected) throw new Error(`生成结果校验失败：${needle} 出现 ${count} 次，应为 ${expected}`);
}

if (process.argv.includes('--keep-before')) {
  fs.writeFileSync(path.join(HOST_DIR, 'FilesPicker.kt.before'), original);
}
fs.writeFileSync(path.join(HOST_DIR, 'FilesPicker.kt'), output);
console.log('OK: 生成 FilesPicker.kt（+' + (output.split('\n').length - original.split('\n').length) + ' 行）');
console.log('SRC:', SRC);
