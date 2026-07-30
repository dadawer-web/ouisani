import { AIOS_API_URL } from "../config";

// ════════════════════════════════════════════════════════════════
//  chatStream — 消费 POST /api/chat 的 SSE 流式响应
//  EventSource 只支持 GET，故用 fetch + ReadableStream 解析 `data:` 行
// ════════════════════════════════════════════════════════════════

export interface ChatTurnMessage {
  role: "user" | "assistant" | "system";
  content: string;
}

export interface StreamChatOptions {
  agentId: string;
  messages: ChatTurnMessage[];
  systemPrompt?: string;
  onDelta: (delta: string) => void;
  onDone: () => void;
  onError: (message: string) => void;
  signal?: AbortSignal;
}

/**
 * 发起一次对话 turn：POST /api/chat，逐 token 回调 onDelta。
 * 正常结束或收到 [DONE] → onDone；HTTP/网络错误或 {"error":...} 事件 → onError。
 */
export async function streamChat(opts: StreamChatOptions): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`${AIOS_API_URL}/api/chat?token=AIOS-SUPER-SECRET-KEY`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        agentId: opts.agentId,
        messages: opts.messages,
        systemPrompt: opts.systemPrompt,
      }),
      signal: opts.signal,
    });
  } catch (e: any) {
    if (e?.name === "AbortError") {
      opts.onDone();
      return;
    }
    opts.onError(e?.message ?? "network error");
    return;
  }

  if (!res.ok || !res.body) {
    opts.onError(`HTTP ${res.status}`);
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  let finished = false;

  const finishDone = () => {
    if (finished) return;
    finished = true;
    opts.onDone();
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });

      // SSE 事件以空行分隔
      const parts = buf.split("\n\n");
      buf = parts.pop() ?? "";

      for (const part of parts) {
        const data = extractData(part);
        if (data === null) continue;
        if (data === "[DONE]") {
          finishDone();
          return;
        }
        try {
          const obj = JSON.parse(data);
          if (obj.error) {
            if (!finished) {
              finished = true;
              opts.onError(String(obj.error));
            }
            return;
          }
          if (typeof obj.delta === "string") opts.onDelta(obj.delta);
        } catch {
          // 非 JSON 的 data 行，当作纯文本 delta
          opts.onDelta(data);
        }
      }
    }
    // 流自然结束兜底
    finishDone();
  } catch (e: any) {
    if (e?.name === "AbortError") {
      finishDone();
      return;
    }
    opts.onError(e?.message ?? "stream read error");
  }
}

/** 从一个 SSE 事件块中提取 `data:` 行内容（多行 data 用换行拼接） */
function extractData(block: string): string | null {
  const lines = block.split("\n");
  const dataLines: string[] = [];
  for (const line of lines) {
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).replace(/^ /, ""));
    }
  }
  if (dataLines.length === 0) return null;
  return dataLines.join("\n");
}
