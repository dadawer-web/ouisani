import { create } from "zustand";
import { AIOS_API_URL } from "../config";

export type WikiCategory =
  | "ALL"
  | "PROJECTS"
  | "TOPICS"
  | "DECISIONS"
  | "SOURCES"
  | "ARTIFACTS";

export interface WikiEntry {
  wikiId: string;
  category: Exclude<WikiCategory, "ALL">;
  title: string;
  content: string;
  memoryId: string;
  namespace: string;
  source: string;
  sourceRef: string | null;
  ownerAgentId: string;
  sourceAgentId: string | null;
  workflowId: string | null;
  traceId: string | null;
  confidence: number;
  version: number;
  visibilityScope: string;
  tenantId: string | null;
  teamId: string | null;
  userConfirmed: boolean;
  superseded: boolean;
  supersedesWikiId: string | null;
  basis: string | null;
  tags: string[];
  createdAt: number;
  updatedAt: number;
}

const TOKEN = "AIOS-SUPER-SECRET-KEY";
const LS_KEY = "aios.wiki.agentId";
const LS_CONTEXT_KEY = "aios.wiki.context";

interface WikiState {
  agentId: string;
  tenantId: string;
  workflowId: string;
  teamId: string;
  entries: WikiEntry[];
  category: WikiCategory;
  search: string;
  onlyConfirmed: boolean;
  loading: boolean;
  error: string | null;
  lastUpdated: number | null;
  pendingId: string | null;
  setAgentId: (id: string) => void;
  setTenantId: (id: string) => void;
  setWorkflowId: (id: string) => void;
  setTeamId: (id: string) => void;
  setCategory: (category: WikiCategory) => void;
  setSearch: (search: string) => void;
  setOnlyConfirmed: (onlyConfirmed: boolean) => void;
  fetchWiki: () => Promise<void>;
  confirmEntry: (wikiId: string, confirmed: boolean) => Promise<void>;
}

function loadAgentId(): string {
  try {
    return localStorage.getItem(LS_KEY) || "";
  } catch {
    return "";
  }
}

function loadContext(): { tenantId: string; workflowId: string; teamId: string } {
  try {
    const raw = localStorage.getItem(LS_CONTEXT_KEY);
    if (raw) return { tenantId: "", workflowId: "", teamId: "", ...JSON.parse(raw) };
  } catch {
    /* localStorage 不可用时静默降级 */
  }
  return { tenantId: "", workflowId: "", teamId: "" };
}

async function parseError(resp: Response): Promise<string> {
  try {
    const body = await resp.json();
    return body.error || `HTTP ${resp.status}`;
  } catch {
    return `HTTP ${resp.status}`;
  }
}

export const useWikiStore = create<WikiState>((set, get) => ({
  agentId: loadAgentId(),
  ...loadContext(),
  entries: [],
  category: "ALL",
  search: "",
  onlyConfirmed: false,
  loading: false,
  error: null,
  lastUpdated: null,
  pendingId: null,

  setAgentId: (id) => {
    set({ agentId: id });
    try {
      localStorage.setItem(LS_KEY, id);
    } catch {
      /* localStorage 不可用时静默降级 */
    }
  },

  setTenantId: (tenantId) => {
    set({ tenantId });
    persistContext({ ...get(), tenantId });
  },
  setWorkflowId: (workflowId) => {
    set({ workflowId });
    persistContext({ ...get(), workflowId });
  },
  setTeamId: (teamId) => {
    set({ teamId });
    persistContext({ ...get(), teamId });
  },

  setCategory: (category) => set({ category }),
  setSearch: (search) => set({ search }),
  setOnlyConfirmed: (onlyConfirmed) => set({ onlyConfirmed }),

  fetchWiki: async () => {
    const { agentId, tenantId, workflowId, teamId, category, search, onlyConfirmed } = get();
    if (!agentId.trim()) {
      set({ entries: [], error: "agentId required" });
      return;
    }
    set({ loading: true, error: null });
    try {
      const params = new URLSearchParams({
        agentId,
        token: TOKEN,
        includeLegacy: "true",
      });
      if (tenantId.trim()) params.set("tenantId", tenantId.trim());
      if (workflowId.trim()) params.set("workflowId", workflowId.trim());
      if (teamId.trim()) params.set("teamId", teamId.trim());
      if (category !== "ALL") params.set("category", category);
      if (search.trim()) params.set("q", search.trim());
      if (onlyConfirmed) params.set("confirmed", "true");
      const resp = await fetch(`${AIOS_API_URL}/api/wiki?${params.toString()}`);
      if (!resp.ok) {
        set({ loading: false, entries: [], error: await parseError(resp), lastUpdated: Date.now() });
        return;
      }
      const data = (await resp.json()) as { entries?: WikiEntry[] };
      set({
        entries: data.entries || [],
        loading: false,
        error: null,
        lastUpdated: Date.now(),
      });
    } catch (error) {
      set({
        loading: false,
        error: error instanceof Error ? error.message : "network error",
        lastUpdated: Date.now(),
      });
    }
  },

  confirmEntry: async (wikiId, confirmed) => {
    const { agentId } = get();
    const previous = get().entries;
    set({
      pendingId: wikiId,
      error: null,
      entries: previous.map((entry) =>
        entry.wikiId === wikiId ? { ...entry, userConfirmed: confirmed } : entry,
      ),
    });
    try {
      const params = new URLSearchParams({ agentId, token: TOKEN });
      const resp = await fetch(
        `${AIOS_API_URL}/api/wiki/${encodeURIComponent(wikiId)}/confirm?${params.toString()}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ confirmed }),
        },
      );
      if (!resp.ok) {
        set({ entries: previous, pendingId: null, error: await parseError(resp) });
        return;
      }
      const updated = (await resp.json()) as WikiEntry;
      set({
        entries: get().entries.map((entry) =>
          entry.wikiId === wikiId ? updated : entry,
        ),
        pendingId: null,
        lastUpdated: Date.now(),
      });
    } catch (error) {
      set({
        entries: previous,
        pendingId: null,
        error: error instanceof Error ? error.message : "network error",
      });
    }
  },
}));

function persistContext(context: { tenantId: string; workflowId: string; teamId: string }) {
  try {
    localStorage.setItem(LS_CONTEXT_KEY, JSON.stringify({
      tenantId: context.tenantId,
      workflowId: context.workflowId,
      teamId: context.teamId,
    }));
  } catch {
    /* localStorage 不可用时静默降级 */
  }
}
