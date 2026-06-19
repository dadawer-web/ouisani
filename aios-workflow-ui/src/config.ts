/**
 * AIOS 内核 API 基地址 — 所有前端组件统一引用此常量。
 *
 * 修改后端端口只需改这一处，或设置环境变量 VITE_AIOS_API_URL。
 */
export const AIOS_API_URL =
  import.meta.env.VITE_AIOS_API_URL || "http://localhost:8080";

export const AIOS_WS_URL =
  import.meta.env.VITE_AIOS_WS_URL || "ws://localhost:8080";
