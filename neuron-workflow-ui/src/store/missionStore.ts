import { create } from "zustand";
import { AIOS_API_URL } from "../config";

export type MissionStatus =
  | "ACTIVE"
  | "WAITING_APPROVAL"
  | "BACKGROUND"
  | "PLANNED"
  | "COMPLETED"
  | "BLOCKED"
  | "FAILED";

export interface MissionKnowledge {
  id: string;
  kind: string;
  title: string;
  summary: string;
  source: string;
  createdAt: number;
}

export interface MissionApproval {
  requestId: string;
  action: string;
  toolName: string;
  target: string | null;
  workflowId: string | null;
  traceId: string | null;
  createdAt: number;
}

export interface Mission {
  missionId: string;
  goal: string;
  status: MissionStatus;
  currentState: string;
  nextStep: string;
  runIds: string[];
  confirmedKnowledge: MissionKnowledge[];
  pendingApprovals: MissionApproval[];
  nextTriggerAt: number;
  nextTriggerEvent: string | null;
  completionReport: string | null;
  createdAt: number;
  updatedAt: number;
}

interface MissionState {
  missions: Mission[];
  loading: boolean;
  error: string | null;
  fetchMissions: () => Promise<void>;
  createMission: (goal: string) => Promise<Mission | null>;
  updateMission: (missionId: string, patch: Partial<Pick<Mission, "goal" | "currentState" | "nextStep" | "status" | "nextTriggerAt" | "nextTriggerEvent" | "completionReport">>) => Promise<void>;
  attachRun: (missionId: string, runId: string) => Promise<void>;
  addApproval: (missionId: string, approval: Partial<MissionApproval> & { requestId: string }) => Promise<void>;
}

const TOKEN = "AIOS-SUPER-SECRET-KEY";
const endpoint = (path: string) => `${AIOS_API_URL}${path}${path.includes("?") ? "&" : "?"}token=${TOKEN}`;

async function request(path: string, init?: RequestInit) {
  const response = await fetch(endpoint(path), {
    ...init,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}`, ...(init?.headers ?? {}) },
  });
  if (!response.ok) throw new Error(`Mission API HTTP ${response.status}`);
  return response.json();
}

export const useMissionStore = create<MissionState>((set) => ({
  missions: [],
  loading: true,
  error: null,

  fetchMissions: async () => {
    try {
      const data = await request("/api/missions");
      set({ missions: Array.isArray(data) ? data : [], loading: false, error: null });
    } catch (error) {
      set({ loading: false, error: error instanceof Error ? error.message : "Mission service unavailable" });
    }
  },

  createMission: async (goal) => {
    if (!goal.trim()) return null;
    try {
      const mission = await request("/api/missions", { method: "POST", body: JSON.stringify({ goal: goal.trim() }) });
      set((state) => ({ missions: [mission, ...state.missions], error: null }));
      return mission as Mission;
    } catch (error) {
      set({ error: error instanceof Error ? error.message : "Mission could not be created" });
      return null;
    }
  },

  updateMission: async (missionId, patch) => {
    try {
      const mission = await request(`/api/missions/${encodeURIComponent(missionId)}`, { method: "PATCH", body: JSON.stringify(patch) });
      set((state) => ({ missions: state.missions.map((item) => item.missionId === missionId ? mission : item), error: null }));
    } catch (error) {
      set({ error: error instanceof Error ? error.message : "Mission update failed" });
    }
  },

  attachRun: async (missionId, runId) => {
    try {
      const mission = await request(`/api/missions/${encodeURIComponent(missionId)}/runs`, { method: "POST", body: JSON.stringify({ runId }) });
      set((state) => ({ missions: state.missions.map((item) => item.missionId === missionId ? mission : item) }));
    } catch (error) {
      set({ error: error instanceof Error ? error.message : "Run could not be linked" });
    }
  },

  addApproval: async (missionId, approval) => {
    try {
      const mission = await request(`/api/missions/${encodeURIComponent(missionId)}/approvals`, { method: "POST", body: JSON.stringify(approval) });
      set((state) => ({ missions: state.missions.map((item) => item.missionId === missionId ? mission : item) }));
    } catch {
      // Permission stream remains usable if a mission read-model is temporarily unavailable.
    }
  },
}));
