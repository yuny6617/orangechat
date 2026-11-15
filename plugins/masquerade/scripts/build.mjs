#!/usr/bin/env node
/*
 * 零依赖 ZIP(store) 打包：把 plugin/ 目录打成可直接导入橘瓣的插件包。
 * 输出后自带确定性校验（重新解析中央目录 + CRC），不依赖系统 zip/unzip。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PLUGIN_DIR = path.join(ROOT, 'plugin');
const MANIFEST = JSON.parse(fs.readFileSync(path.join(PLUGIN_DIR, 'manifest.json'), 'utf8'));
const OUT_DIR = path.join(ROOT, 'dist');
const OUT_FILE = path.join(OUT_DIR, 'orangechat-masquerade-' + MANIFEST.version + '.zip');

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) crc = CRC_TABLE[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

/* 文本文件统一 LF：Windows clone 常见 CRLF，若不归一化，同一份源码会打出不同的 ZIP。 */
const TEXT_EXT = new Set(['.js', '.json', '.html', '.css', '.svg', '.md', '.txt']);
function normalizeEol(name, buf) {
  if (!TEXT_EXT.has(path.extname(name).toLowerCase())) return buf;
  return Buffer.from(buf.toString('utf8').replace(/\r\n/g, '\n'), 'utf8');
}

function collect(dir, base) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if (entry.name.startsWith('.') || entry.name === 'node_modules') continue;
    const rel = base ? base + '/' + entry.name : entry.name;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...collect(full, rel));
    else if (entry.isFile()) out.push({ name: rel, data: normalizeEol(rel, fs.readFileSync(full)) });
  }
  return out;
}

function dosStamp(date) {
  return {
    time: (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1),
    date: ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate(),
  };
}

function buildZip(files) {
  /* 固定 DOS 时间戳：配合 LF 归一化，保证同一份源码在不同机器/时间构建出完全相同的 ZIP。 */
  const stamp = dosStamp(new Date(2026, 0, 1, 0, 0, 0));
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const file of files) {
    const name = Buffer.from(file.name, 'utf8');
    const crc = crc32(file.data);
    const size = file.data.length;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(stamp.time, 10);
    local.writeUInt16LE(stamp.date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(size, 18);
    local.writeUInt32LE(size, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, name, file.data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(stamp.time, 12);
    central.writeUInt16LE(stamp.date, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(size, 20);
    central.writeUInt32LE(size, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, name);
    offset += local.length + name.length + size;
  }
  const centralBuf = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([...locals, centralBuf, eocd]);
}

/** 反读中央目录，逐条核对签名、偏移、CRC 与长度；任一不符即抛错。 */
function verifyZip(zip, expected) {
  const eocdOffset = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocdOffset < 0) throw new Error('EOCD 缺失');
  if (eocdOffset !== zip.length - 22) throw new Error('EOCD 不在文件末尾，包结构异常');
  const count = zip.readUInt16LE(eocdOffset + 10);
  const cdOffset = zip.readUInt32LE(eocdOffset + 16);
  if (count !== expected.length) throw new Error(`条目数不符：${count} != ${expected.length}`);
  let p = cdOffset;
  for (let i = 0; i < count; i++) {
    if (zip.readUInt32LE(p) !== 0x02014b50) throw new Error('中央目录签名损坏 @' + i);
    const crc = zip.readUInt32LE(p + 16);
    const size = zip.readUInt32LE(p + 24);
    const nameLen = zip.readUInt16LE(p + 28);
    const localOffset = zip.readUInt32LE(p + 42);
    const name = zip.subarray(p + 46, p + 46 + nameLen).toString('utf8');
    if (zip.readUInt32LE(localOffset) !== 0x04034b50) throw new Error('本地头签名损坏：' + name);
    const dataOffset = localOffset + 30 + zip.readUInt16LE(localOffset + 26);
    const data = zip.subarray(dataOffset, dataOffset + size);
    if (crc32(data) !== crc) throw new Error('CRC 不符：' + name);
    const want = expected.find((f) => f.name === name);
    if (!want || !want.data.equals(data)) throw new Error('内容不符：' + name);
    p += 46 + nameLen;
  }
  return count;
}

const files = collect(PLUGIN_DIR, '');
const required = ['manifest.json', 'main.js', 'ui/index.html', 'ui/style.css', 'ui/app.js', 'ui/boot.js'];
for (const name of required) {
  if (!files.some((f) => f.name === name)) throw new Error('插件包缺少必需文件：' + name);
}
for (const f of files) {
  if (f.name.includes('..') || f.name.startsWith('/')) throw new Error('非法包内路径：' + f.name);
}

fs.mkdirSync(OUT_DIR, { recursive: true });
const zip = buildZip(files);
const verified = verifyZip(zip, files);
fs.writeFileSync(OUT_FILE, zip);

console.log('✓ 插件包：' + OUT_FILE);
console.log('  条目 ' + verified + ' 个，' + zip.length + ' 字节；CRC 与内容回读校验通过');
for (const f of files) console.log('  - ' + f.name + ' (' + f.data.length + ' B)');
