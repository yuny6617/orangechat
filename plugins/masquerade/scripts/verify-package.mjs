#!/usr/bin/env node
/*
 * 宿主同款导入自检。
 *
 * 橘瓣 PluginScanner 的导入链路是：
 *   ZipInputStream（流式，只读本地文件头）→ 解压 → findManifest()
 *   → 解析 manifest.json → validateManifest() 检查 entry / customPageWebView.entry
 * Python zipfile 与之前的 Node 回读都走「中央目录」，是另一条路径，
 * 无法证明真机能导入。本脚本按宿主规则逐条复现，导入前即可判断会不会报
 * 「导入失败: 找不到 manifest.json」。
 *
 * 用法：
 *   node scripts/verify-package.mjs                     # 检查 dist/ 里的默认插件包
 *   node scripts/verify-package.mjs <任意 .zip 路径>    # 检查你手上下载到的文件
 *   node scripts/verify-package.mjs --self-test         # 检测器正反样本自测
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const VERSION = JSON.parse(fs.readFileSync(path.join(ROOT, 'plugin', 'manifest.json'), 'utf8')).version;
const DEFAULT_FILE = path.join(ROOT, 'dist', 'orangechat-masquerade-' + VERSION + '.zip');

/** 严格模拟 java.util.zip.ZipInputStream.getNextEntry()：只认本地头，遇到别的签名即返回 null。 */
function streamEntries(buf) {
  const out = [];
  let p = 0;
  while (p + 30 <= buf.length) {
    if (buf.readUInt32LE(p) !== 0x04034b50) break;
    const flag = buf.readUInt16LE(p + 6);
    const csize = buf.readUInt32LE(p + 18);
    const usize = buf.readUInt32LE(p + 22);
    const nameLen = buf.readUInt16LE(p + 26);
    const extraLen = buf.readUInt16LE(p + 28);
    const name = buf.subarray(p + 30, p + 30 + nameLen).toString('utf8');
    const dataStart = p + 30 + nameLen + extraLen;
    const data = buf.subarray(dataStart, dataStart + csize);
    if ((flag & 8) === 8) throw new Error('包使用了 data descriptor（bit 3），宿主流式读取存在兼容风险：' + name);
    if (data.length !== csize) throw new Error('entry 数据被截断：' + name);
    out.push({
      name: name.replace(/\\/g, '/').replace(/^\/+/, ''),
      data, csize, usize, flag,
    });
    p = dataStart + csize;
  }
  return out;
}

/** 复刻 PluginScanner.findManifest()：根目录优先，其次只找一级子目录。 */
function findManifest(entries) {
  const byName = new Map(entries.map((e) => [e.name, e]));
  if (byName.has('manifest.json')) return { entry: byName.get('manifest.json'), level: 0 };
  const topDirs = [...new Set(entries.map((e) => e.name.split('/')[0]).filter(Boolean))];
  for (const dir of topDirs) {
    const candidate = byName.get(dir + '/manifest.json');
    if (candidate) return { entry: candidate, level: 1 };
  }
  return null;
}

/** 复刻 validateManifest() 的路径存在性检查（SafeFileResolver 只要求不逃逸、无 NUL）。 */
function validateManifestLikeHost(manifest, entries) {
  const names = new Set(entries.map((e) => e.name));
  const problems = [];
  for (const [label, value] of [
    ['entry', manifest.entry],
    ['customPageWebView.entry', manifest.customPageWebView && manifest.customPageWebView.entry],
  ]) {
    if (!value) continue;
    if (value.includes('\u0000') || value.startsWith('/') || value.split('/').includes('..')) {
      problems.push(label + ' 路径非法：' + value);
    } else if (!names.has(value.replace(/\\/g, '/'))) {
      problems.push(label + ' 指向的文件不在包内：' + value);
    }
  }
  return problems;
}

/** 返回一个可供人读、可供脚本断言的诊断结果。 */
export function inspect(buf) {
  const issues = [];
  let entries;
  try {
    entries = streamEntries(buf);
  } catch (e) {
    return { ok: false, issues: ['ZIP 流式解析失败：' + String((e && e.message) || e)], entries: [], hints: [] };
  }
  if (entries.length === 0) {
    issues.push('ZIP 里一个 entry 都读不到——文件可能不是 ZIP（比如下载到的是网页/HTML），或已损坏');
  }

  const found = findManifest(entries);
  const hints = [];
  if (!found) {
    issues.push('findManifest() 返回 null → 真机会报「导入失败: 找不到 manifest.json」');
    const deep = entries.find((e) => e.name.endsWith('/manifest.json'));
    if (deep) {
      const depth = deep.name.split('/').length;
      hints.push('包里确实有 ' + deep.name + '，但它在第 ' + depth + ' 层；宿主只查根目录和一级子目录。');
      hints.push('这通常是误把「GitHub → Code → Download ZIP」的仓库源码包当成插件包了。');
    } else {
      hints.push('包里没有任何 manifest.json；请确认下载的是 dist/orangechat-masquerade-' + VERSION + '.zip。');
    }
  } else {
    let manifest;
    try {
      manifest = JSON.parse(found.entry.data.toString('utf8'));
    } catch (e) {
      issues.push('manifest.json 不是合法 JSON：' + String((e && e.message) || e));
    }
    if (manifest) {
      if (!manifest.id) issues.push('manifest.json 缺少 id');
      for (const p of validateManifestLikeHost(manifest, entries)) issues.push(p);
    }
  }

  const rootFiles = entries.filter((e) => !e.name.includes('/')).map((e) => e.name);
  return {
    ok: issues.length === 0,
    issues, hints, entries,
    foundLevel: found ? found.level : null,
    rootFiles,
  };
}

function printReport(file, r) {
  console.log('文件：' + file);
  console.log('流式读到 ' + r.entries.length + ' 个 entry；根目录文件：' + (r.rootFiles.join(', ') || '（无）'));
  if (r.foundLevel !== null) console.log('findManifest()：在第 ' + r.foundLevel + ' 层找到 manifest.json');
  for (const h of r.hints) console.log('  · ' + h);
  for (const i of r.issues) console.log('  [问题] ' + i);
  if (r.ok) {
    console.log('✓ 导入可成功：宿主能找到并解析清单，入口文件与 WebView 页面均在包内');
  } else {
    console.log('✗ 导入会失败：请改导入 dist/orangechat-masquerade-' + VERSION + '.zip');
  }
}

/* ---------- 正反样本自测：证明检测器不会把坏包判成好包 ---------- */
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}
/** 生成最小 store ZIP，仅用于构造自检样本。 */
function makeZip(files) {
  const locals = [], centrals = [];
  let offset = 0;
  for (const f of files) {
    const name = Buffer.from(f.name, 'utf8');
    const data = Buffer.from(f.data, 'utf8');
    const crc = crc32(data), size = data.length;
    const l = Buffer.alloc(30);
    l.writeUInt32LE(0x04034b50, 0); l.writeUInt16LE(20, 4); l.writeUInt16LE(0x0800, 6);
    l.writeUInt32LE(crc, 14); l.writeUInt32LE(size, 18); l.writeUInt32LE(size, 22);
    l.writeUInt16LE(name.length, 26);
    locals.push(l, name, data);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6);
    c.writeUInt16LE(0x0800, 8); c.writeUInt32LE(crc, 16);
    c.writeUInt32LE(size, 20); c.writeUInt32LE(size, 24); c.writeUInt16LE(name.length, 28);
    c.writeUInt32LE(offset, 42);
    centrals.push(c, name);
    offset += l.length + name.length + size;
  }
  const cd = Buffer.concat(centrals);
  const e = Buffer.alloc(22);
  e.writeUInt32LE(0x06054b50, 0);
  e.writeUInt16LE(files.length, 8); e.writeUInt16LE(files.length, 10);
  e.writeUInt32LE(cd.length, 12); e.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, e]);
}

function selfTest() {
  const goodManifest = JSON.stringify({
    id: 'com.orangechat.masquerade', name: 'test', version: VERSION,
    entry: 'main.js', customPageWebView: { entry: 'ui/index.html' },
  });
  const cases = [
    {
      label: '根目录含清单（正确插件包）', expect: true,
      files: [
        { name: 'manifest.json', data: goodManifest },
        { name: 'main.js', data: 'exports.x=1;' },
        { name: 'ui/index.html', data: '<html></html>' },
      ],
    },
    {
      label: '仓库源码包（清单在第 4 层）', expect: false,
      files: [
        { name: 'orangechat-main/README.md', data: 'x' },
        { name: 'orangechat-main/plugins/masquerade/plugin/manifest.json', data: goodManifest },
        { name: 'orangechat-main/plugins/masquerade/plugin/main.js', data: 'x' },
      ],
    },
    {
      label: '完全没有清单', expect: false,
      files: [{ name: 'main.js', data: 'x' }],
    },
    {
      label: '清单在包内但入口文件缺失', expect: false,
      files: [{ name: 'manifest.json', data: goodManifest }],
    },
    {
      label: '非 ZIP 内容', expect: false, raw: Buffer.from('<!DOCTYPE html><html>download page</html>'),
    },
  ];
  const bad = [];
  for (const c of cases) {
    const buf = c.raw || makeZip(c.files);
    const r = inspect(buf);
    if (r.ok !== c.expect) bad.push(c.label + '（期望 ' + (c.expect ? 'PASS' : 'FAIL') + '，实得 ' + (r.ok ? 'PASS' : 'FAIL') + '）');
  }
  if (bad.length) {
    console.log('SELF_TEST: FAILED');
    for (const b of bad) console.log('  · ' + b);
    return false;
  }
  console.log('SELF_TEST: OK（' + cases.length + ' 组正反样本全部符合预期）');
  return true;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  if (process.argv.includes('--self-test')) {
    process.exit(selfTest() ? 0 : 1);
  }
  const positional = process.argv.slice(2).find((a) => !a.startsWith('--'));
  const file = positional ? path.resolve(positional) : DEFAULT_FILE;
  if (!fs.existsSync(file)) {
    console.error('文件不存在：' + file);
    process.exit(1);
  }
  const r = inspect(fs.readFileSync(file));
  printReport(file, r);
  process.exit(r.ok ? 0 : 1);
}
