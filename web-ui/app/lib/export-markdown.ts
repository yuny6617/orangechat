import type { ConversationDto, MessageDto, UIMessagePart } from "~/types";

/** Keep one Markdown page small enough to stay responsive in a browser editor. */
export const DEFAULT_MARKDOWN_PAGE_CHAR_LIMIT = 48_000;
/** A hard guard against accidentally materializing an unbounded conversation. */
export const MAX_MARKDOWN_EXPORT_CHARS = 16_000_000;
const PROGRESS_YIELD_EVERY = 20;

export class MarkdownExportError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MarkdownExportError";
  }
}

export interface MarkdownExportOptions {
  includeReasoning: boolean;
  includeTools?: boolean;
  signal?: AbortSignal;
  onProgress?: (done: number, total: number) => void;
}

export interface ConversationMarkdownOptions extends MarkdownExportOptions {
  selectedMessageIds?: ReadonlySet<string> | null;
  pageCharLimit?: number;
  maxChars?: number;
}

export interface MarkdownExportResult {
  content: string;
  messageCount: number;
  pageCount: number;
}

function fenced(text: string, language: string): string {
  const runs = text.match(/`+/g) ?? [];
  const longest = runs.reduce((max, run) => Math.max(max, run.length), 0);
  const fence = "`".repeat(Math.max(3, longest + 1));
  return `${fence}${language}\n${text}\n${fence}`;
}

function quote(text: string): string {
  return text
    .split("\n")
    .map((line) => (line.trim().length > 0 ? `> ${line}` : ">"))
    .join("\n");
}

function prettyJson(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return "{}";
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2);
  } catch {
    return raw;
  }
}

function escapeLinkText(text: string): string {
  return text.replace(/\\/g, "\\\\").replace(/\[/g, "\\[").replace(/\]/g, "\\]");
}

function imageMarkdown(url: string): string {
  // Angle-bracket destinations tolerate spaces and parentheses in local paths/data URLs.
  return `![image](<${url.replace(/>/g, "%3E")}>)`;
}

function formatToolOutput(part: UIMessagePart, includeReasoning: boolean): string {
  switch (part.type) {
    case "text":
      return fenced(part.text, "text");
    case "reasoning":
      return includeReasoning ? quote(part.reasoning) : "";
    case "image":
      return part.url ? imageMarkdown(part.url) : "";
    case "document":
      return `[${escapeLinkText(part.fileName)}](<${part.url}>)`;
    case "video":
      return `[视频](<${part.url}>)`;
    case "audio":
      return `[音频](<${part.url}>)`;
    case "tool":
      return `[工具调用: ${part.toolName}]`;
    default:
      return "";
  }
}

function formatToolPart(part: Extract<UIMessagePart, { type: "tool" }>, includeReasoning: boolean): string {
  const lines: string[] = [];
  lines.push(`**Tool**: \`${part.toolName}\``);
  if (part.toolCallId) {
    lines.push(`- Call ID: \`${part.toolCallId}\``);
  }
  lines.push("", "Input:", "", fenced(prettyJson(part.input), "json"), "");

  if (part.output.length > 0) {
    lines.push("Output:", "");
    for (const outputPart of part.output) {
      const rendered = formatToolOutput(outputPart, includeReasoning);
      if (rendered) {
        lines.push(rendered, "");
      }
    }
  }
  return lines.join("\n").trim();
}

export function formatPartToMarkdown(
  part: UIMessagePart,
  includeReasoning: boolean,
  includeTools = true,
): string {
  switch (part.type) {
    case "text":
      return part.text.trim() ? part.text : "";
    case "reasoning":
      return includeReasoning && part.reasoning.trim() ? quote(part.reasoning.trim()) : "";
    case "image":
      return part.url ? imageMarkdown(part.url) : "";
    case "document":
      return part.url || part.fileName
        ? `[${escapeLinkText(part.fileName || part.url)}](<${part.url}>)`
        : "";
    case "video":
      return part.url ? `[视频](<${part.url}>)` : "";
    case "audio":
      return part.url ? `[音频](<${part.url}>)` : "";
    case "tool":
      return includeTools ? formatToolPart(part, includeReasoning) : "";
    default:
      return "";
  }
}

function messageBody(message: MessageDto, includeReasoning: boolean, includeTools: boolean): string {
  return message.parts
    .map((part) => formatPartToMarkdown(part, includeReasoning, includeTools))
    .filter((part) => part.trim().length > 0)
    .join("\n\n")
    .trim();
}

function roleLabel(role: string): string {
  switch (role) {
    case "USER":
      return "User";
    case "ASSISTANT":
      return "Assistant";
    case "SYSTEM":
      return "System";
    case "TOOL":
      return "Tool";
    default:
      return role;
  }
}

export function convertMessageToMarkdown(
  message: MessageDto,
  includeReasoning: boolean,
  includeTools = true,
): string {
  return messageBody(message, includeReasoning, includeTools);
}

function selectedMessages(
  detail: ConversationDto,
  selectedMessageIds?: ReadonlySet<string> | null,
): MessageDto[] {
  const messages: MessageDto[] = [];
  for (const node of detail.messages) {
    const message = node.messages[node.selectIndex] ?? node.messages[0];
    if (!message) continue;
    if (selectedMessageIds && !selectedMessageIds.has(message.id)) continue;
    messages.push(message);
  }
  return messages;
}

function messageSection(
  message: MessageDto,
  index: number,
  includeReasoning: boolean,
  includeTools: boolean,
): string {
  const body = messageBody(message, includeReasoning, includeTools);
  return `${roleHeading(message.role, index + 1)}${body ? `\n\n${body}` : ""}`;
}

function roleHeading(role: string, index: number): string {
  return `## ${roleLabel(role)} ${index}`;
}

function exportHeader(title: string | null | undefined): string {
  const safeTitle = (title ?? "").replace(/\s+/g, " ").trim() || "Conversation";
  return `# ${safeTitle}\n\n*Exported on ${new Date().toISOString()}*`;
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw new DOMException("导出已取消", "AbortError");
  }
}

function yieldToBrowser(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, 0);
  });
}

function paginate(header: string, sections: string[], pageCharLimit: number): string[] {
  const pages: string[] = [];
  let current = "";
  for (const section of sections) {
    if (current.length > 0 && current.length + section.length + 8 > pageCharLimit) {
      pages.push(current.trim());
      current = "";
    }
    current += `${current.length > 0 ? "\n\n---\n\n" : ""}${section}`;
  }
  if (current.trim().length > 0) {
    pages.push(current.trim());
  }
  if (pages.length === 0) {
    pages.push("");
  }

  if (pages.length === 1) {
    return [`${header}\n\n${pages[0]}`.trim()];
  }
  return pages.map((page, index) => {
    const prefix = index === 0 ? `${header}\n\n` : "";
    return `${prefix}> 分页：第 ${index + 1} / ${pages.length} 页\n\n${page}`.trim();
  });
}

export function buildConversationMarkdownPages(
  detail: ConversationDto,
  options: ConversationMarkdownOptions,
): MarkdownExportResult {
  const includeReasoning = options.includeReasoning;
  const includeTools = options.includeTools ?? true;
  const messages = selectedMessages(detail, options.selectedMessageIds);
  const header = exportHeader(detail.title);
  const pageCharLimit = Math.max(4_000, options.pageCharLimit ?? DEFAULT_MARKDOWN_PAGE_CHAR_LIMIT);
  const maxChars = Math.max(pageCharLimit, options.maxChars ?? MAX_MARKDOWN_EXPORT_CHARS);

  let totalChars = header.length;
  const sections: string[] = [];
  for (let index = 0; index < messages.length; index += 1) {
    throwIfAborted(options.signal);
    const section = messageSection(messages[index], index, includeReasoning, includeTools);
    totalChars += section.length;
    if (totalChars > maxChars) {
      throw new MarkdownExportError(
        `导出内容超过 ${maxChars} 字符上限，请减少所选消息后重试`,
      );
    }
    sections.push(section);
    options.onProgress?.(index + 1, messages.length);
  }

  const pages = paginate(header, sections, pageCharLimit);
  return {
    content: pages.join("\n\n---\n\n"),
    messageCount: messages.length,
    pageCount: pages.length,
  };
}

export async function exportConversationMarkdown(
  detail: ConversationDto,
  options: ConversationMarkdownOptions,
): Promise<MarkdownExportResult> {
  const includeReasoning = options.includeReasoning;
  const includeTools = options.includeTools ?? true;
  const messages = selectedMessages(detail, options.selectedMessageIds);
  const header = exportHeader(detail.title);
  const pageCharLimit = Math.max(4_000, options.pageCharLimit ?? DEFAULT_MARKDOWN_PAGE_CHAR_LIMIT);
  const maxChars = Math.max(pageCharLimit, options.maxChars ?? MAX_MARKDOWN_EXPORT_CHARS);

  let totalChars = header.length;
  const sections: string[] = [];
  for (let index = 0; index < messages.length; index += 1) {
    throwIfAborted(options.signal);
    const section = messageSection(messages[index], index, includeReasoning, includeTools);
    totalChars += section.length;
    if (totalChars > maxChars) {
      throw new MarkdownExportError(
        `导出内容超过 ${maxChars} 字符上限，请减少所选消息后重试`,
      );
    }
    sections.push(section);
    options.onProgress?.(index + 1, messages.length);
    if ((index + 1) % PROGRESS_YIELD_EVERY === 0) {
      await yieldToBrowser();
    }
  }

  throwIfAborted(options.signal);
  const pages = paginate(header, sections, pageCharLimit);
  return {
    content: pages.join("\n\n---\n\n"),
    messageCount: messages.length,
    pageCount: pages.length,
  };
}

export function convertConversationToMarkdown(
  detail: ConversationDto,
  includeReasoning: boolean,
  selectedMessageIds?: ReadonlySet<string> | null,
  includeTools = true,
): string {
  const result = buildConversationMarkdownPages(detail, {
    includeReasoning,
    includeTools,
    selectedMessageIds,
  });
  return result.content;
}

export function sanitizeExportFilename(name: string): string {
  const invalid = '\\/:*?"<>|';
  let cleaned = "";
  for (const char of name) {
    const code = char.codePointAt(0) ?? 0;
    cleaned += code < 0x20 || invalid.includes(char) ? "_" : char;
  }
  cleaned = cleaned.replace(/\s+/g, " ").trim();
  const base = cleaned.replace(/\.md$/i, "").slice(0, 80) || "conversation";
  return `${base}.md`;
}

export function downloadMarkdown(content: string, filename: string): void {
  if (typeof document === "undefined") {
    throw new MarkdownExportError("当前环境不支持浏览器下载");
  }
  const blob = new Blob([content], { type: "text/markdown;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  try {
    anchor.href = url;
    anchor.download = sanitizeExportFilename(filename);
    anchor.rel = "noopener";
    document.body.appendChild(anchor);
    anchor.click();
  } catch {
    throw new MarkdownExportError("浏览器无法创建 Markdown 下载文件");
  } finally {
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1_000);
  }
}
