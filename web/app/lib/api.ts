import { demoDashboard, demoHistory } from "./demo";
import type { DashboardData, HistoryPoint } from "./types";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL || "/api/v1";
const DEMO_MODE = process.env.NEXT_PUBLIC_DEMO_MODE !== "false";

let memoryToken: string | null = null;

function getToken() {
  if (memoryToken) return memoryToken;
  if (typeof window !== "undefined") memoryToken = window.sessionStorage.getItem("family-orbit-access");
  return memoryToken;
}

export function saveAccessToken(token: string) {
  memoryToken = token;
  window.sessionStorage.setItem("family-orbit-access", token);
}

export function clearAccessToken() {
  memoryToken = null;
  if (typeof window !== "undefined") window.sessionStorage.removeItem("family-orbit-access");
}

async function refreshAccessToken(): Promise<string | null> {
  try {
    const response = await fetch(`${API_BASE}/auth/refresh`, { method: "POST", credentials: "include", headers: { "Content-Type": "application/json" }, body: "{}" });
    if (!response.ok) return null;
    const session = (await response.json()) as { accessToken: string };
    saveAccessToken(session.accessToken);
    return session.accessToken;
  } catch {
    return null;
  }
}

async function authenticatedFetch(path: string) {
  let token = getToken() || (await refreshAccessToken());
  if (!token) throw new Error("unauthorized");
  let response = await fetch(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` }, credentials: "include", cache: "no-store" });
  if (response.status === 401 && (token = await refreshAccessToken())) {
    response = await fetch(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` }, credentials: "include", cache: "no-store" });
  }
  if (!response.ok) throw new Error(`api:${response.status}`);
  return response;
}

export async function loadDashboard(): Promise<{ data: DashboardData; isDemo: boolean }> {
  try {
    const response = await authenticatedFetch("/dashboard");
    return { data: (await response.json()) as DashboardData, isDemo: false };
  } catch (error) {
    if (!DEMO_MODE) throw error;
    await new Promise((resolve) => setTimeout(resolve, 260));
    return { data: demoDashboard, isDemo: true };
  }
}

export async function loadHistory(childId: string): Promise<HistoryPoint[]> {
  try {
    const from = new Date(Date.now() - 24 * 60 * 60_000).toISOString();
    const response = await authenticatedFetch(`/children/${childId}/history?from=${encodeURIComponent(from)}`);
    return (await response.json()) as HistoryPoint[];
  } catch (error) {
    if (!DEMO_MODE) throw error;
    return demoHistory[childId] || [];
  }
}

export async function login(email: string, password: string) {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, clientType: "web" }),
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.message || "ログインできませんでした");
  saveAccessToken(payload.accessToken);
  return payload;
}

export function websocketURL() {
  const token = getToken();
  if (!token) return null;
  const explicit = process.env.NEXT_PUBLIC_SOCKET_URL;
  const origin = explicit || window.location.origin;
  return `${origin.replace(/^http/, "ws")}/ws?token=${encodeURIComponent(token)}`;
}

