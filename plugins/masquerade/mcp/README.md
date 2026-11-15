# MCP 形态：假面舞会 server

同一个插件的第二种接入方式。零第三方依赖、stdio JSON-RPC 2.0、`node >= 18` 直接跑：

```bash
node mcp/server.mjs
```

客户端配置示例：

```json
{
  "mcpServers": {
    "masquerade": {
      "command": "node",
      "args": ["/absolute/path/to/orangechat-masquerade/mcp/server.mjs"],
      "env": {
        "MASQUERADE_STORE_FILE": "/absolute/path/to/.orangechat-masquerade/store.json"
      }
    }
  }
}
```

## 与插件形态的分工

| | 橘瓣插件 | MCP server |
|---|---|---|
| 谁来演 | 插件自带的模型通道（可配置 OpenAI 兼容接口） | **MCP 客户端的 LLM 本身就是舞伴** |
| 状态与角色卡 | QuickJS `dataStore` | 复用同一份 `plugin/main.js`（vm 加载），落盘到本地 JSON |
| 落幕语义 | 同一份 `buildClosingNote` | 同一份 `buildClosingNote` |
| 网络 | 仅插件白名单内的 HTTPS 模型接口 | **完全不联网**，注入的 `fetch` 只会显式失败 |
| 密钥 | 宿主 `password` 配置项 | **不读取任何 API Key** |

`roleplay_turn` 在 MCP 里记录用户台词并返回 `stagePrompt`（剧中系统提示词）；客户端 LLM 按该提示词以角色身份回复，再用 `record_stage_line` 把台词记回剧本并推进轮次。这样 server 不需要代跑模型，也不需要任何凭据。

## 工具

`list_role_cards` / `get_role_card` / `create_role_card` / `update_role_card` / `delete_role_card` /
`masquerade_status` / `masquerade_invite` / `masquerade_accept` / `roleplay_context` /
`roleplay_turn` / `record_stage_line` / `masquerade_end` / `get_model_config`

资源：`masquerade://stage/system-prompt`（当前剧中规则）、`masquerade://session/closing-note`（最近一次落幕说明）。

## 落盘与安全

- 默认写 `~/.orangechat-masquerade/store.json`，权限 `0600`，写入用临时文件 + `rename` 原子替换。
- `MASQUERADE_STORE_FILE` 必须是绝对路径且不含 `..`，否则启动即失败。
- 单条 JSON-RPC 消息上限 1 MiB；未知工具返回 `-32602`，未知方法 `-32601`，解析失败 `-32700`。
- 日志只走 stderr，不污染 stdout 协议流。
- 所有状态、角色卡、落幕说明都与插件共用 `plugin/main.js`，不存在两套语义。

## 冒烟测试

```bash
node --test tests/mcp.test.mjs
```
