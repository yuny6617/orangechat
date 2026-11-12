import assert from "node:assert/strict";
import test from "node:test";

import type { ConversationDto, MessageDto } from "~/types";
import {
  MAX_MARKDOWN_EXPORT_CHARS,
  MarkdownExportError,
  buildConversationMarkdownPages,
  convertConversationToMarkdown,
  convertMessageToMarkdown,
  exportConversationMarkdown,
  formatPartToMarkdown,
  sanitizeExportFilename,
} from "./export-markdown";

function message(id: string, role: string, parts: MessageDto["parts"]): MessageDto {
  return {
    id,
    role,
    parts,
    annotations: [],
    createdAt: "2026-11-12T00:00:00.000Z",
  };
}

function conversation(messages: MessageDto[]): ConversationDto {
  return {
    id: "conversation-1",
    assistantId: "assistant-1",
    title: "Long chat",
    messages: messages.map((item) => ({ id: `node-${item.id}`, messages: [item], selectIndex: 0 })),
    truncateIndex: -1,
    chatSuggestions: [],
    isPinned: false,
    createAt: 0,
    updateAt: 0,
    isGenerating: false,
  };
}

test("message markdown keeps text, opt-in reasoning, media and tool payloads", () => {
  const item = message("m1", "ASSISTANT", [
    { type: "text", text: "**hello**" },
    { type: "reasoning", reasoning: "hidden thought" },
    { type: "image", url: "file:///private/image.png" },
    { type: "document", url: "file:///doc/a.txt", fileName: "a.txt", mime: "text/plain" },
    {
      type: "tool",
      toolCallId: "call-1",
      toolName: "chart_display",
      input: '{"style":"line","series":[{"values":[1,2]}]}',
      output: [{ type: "text", text: '{"success":true}' }],
      approvalState: { type: "auto" },
    },
  ]);

  const withoutReasoning = convertMessageToMarkdown(item, false);
  assert.match(withoutReasoning, /\*\*hello\*\*/);
  assert.doesNotMatch(withoutReasoning, /hidden thought/);
  assert.match(withoutReasoning, /!\[image\]\(<file:\/\/\/private\/image\.png>\)/);
  assert.match(withoutReasoning, /\[a\.txt\]/);
  assert.match(withoutReasoning, /\*\*Tool\*\*: `chart_display`/);
  assert.match(withoutReasoning, /"style": "line"/);

  const withReasoning = convertMessageToMarkdown(item, true);
  assert.match(withReasoning, /> hidden thought/);

  const withoutTools = convertMessageToMarkdown(item, false, false);
  assert.doesNotMatch(withoutTools, /chart_display/);
});

test("tool fences outgrow any backtick run in the payload", () => {
  const part = formatPartToMarkdown(
    {
      type: "tool",
      toolCallId: "call-2",
      toolName: "shell",
      input: '{"cmd":"echo ``` inside"}',
      output: [{ type: "text", text: "line with ``` fence" }],
      approvalState: { type: "auto" },
    },
    false,
  );
  assert.match(part, /````json/);
  assert.match(part, /````text/);
});

test("conversation export honors the selected message set and branch selection", () => {
  const detail = conversation([
    message("m1", "USER", [{ type: "text", text: "one" }]),
    message("m2", "ASSISTANT", [{ type: "text", text: "two" }]),
    message("m3", "USER", [{ type: "text", text: "three" }]),
  ]);
  const result = buildConversationMarkdownPages(detail, {
    includeReasoning: false,
    selectedMessageIds: new Set(["m3", "m1"]),
  });
  assert.equal(result.messageCount, 2);
  assert.match(result.content, /one/);
  assert.match(result.content, /three/);
  assert.doesNotMatch(result.content, /\btwo\b/);

  const legacy = convertConversationToMarkdown(detail, false, new Set(["m2"]));
  assert.match(legacy, /two/);
  assert.doesNotMatch(legacy, /\bone\b/);
});

test("long conversations are paginated explicitly without dropping messages", () => {
  const items = Array.from({ length: 80 }, (_, index) =>
    message(`m${index}`, index % 2 === 0 ? "USER" : "ASSISTANT", [
      { type: "text", text: `message-${index} ` + "正文 ".repeat(120) },
    ]),
  );
  const result = buildConversationMarkdownPages(conversation(items), {
    includeReasoning: false,
    pageCharLimit: 8_000,
  });
  assert.ok(result.pageCount > 1);
  assert.match(result.content, new RegExp(`第 1 / ${result.pageCount} 页`));
  assert.match(result.content, new RegExp(`第 ${result.pageCount} / ${result.pageCount} 页`));
  for (let index = 0; index < items.length; index += 1) {
    assert.match(result.content, new RegExp(`message-${index}\\b`));
  }
});

test("async export yields, reports progress, and rejects an already-aborted signal", async () => {
  const detail = conversation([
    message("m1", "USER", [{ type: "text", text: "alpha" }]),
    message("m2", "ASSISTANT", [{ type: "text", text: "beta" }]),
  ]);
  const progress: Array<[number, number]> = [];
  const result = await exportConversationMarkdown(detail, {
    includeReasoning: false,
    onProgress: (done, total) => progress.push([done, total]),
  });
  assert.equal(result.messageCount, 2);
  assert.deepEqual(progress, [
    [1, 2],
    [2, 2],
  ]);

  const controller = new AbortController();
  controller.abort();
  await assert.rejects(
    () => exportConversationMarkdown(detail, { includeReasoning: false, signal: controller.signal }),
    (error: unknown) => error instanceof DOMException && error.name === "AbortError",
  );
});

test("oversized exports fail loudly instead of truncating", () => {
  const detail = conversation([
    message("m1", "USER", [{ type: "text", text: "x".repeat(5_000) }]),
  ]);
  assert.throws(
    () =>
      buildConversationMarkdownPages(detail, {
        includeReasoning: false,
        pageCharLimit: 4_000,
        maxChars: 4_100,
      }),
    (error: unknown) => error instanceof MarkdownExportError,
  );
  assert.ok(MAX_MARKDOWN_EXPORT_CHARS > 1_000_000);
});

test("download filenames are sanitized and always end in .md", () => {
  assert.equal(sanitizeExportFilename("a/b:c*?.md"), "a_b_c__.md");
  assert.equal(sanitizeExportFilename("   "), "conversation.md");
  assert.equal(sanitizeExportFilename("标题"), "标题.md");
});
