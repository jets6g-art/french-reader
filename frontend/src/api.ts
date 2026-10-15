import type { Lookup, SavedItem } from "./types.js";

const TIMEOUT_MS = 10_000;

/** The server's error message if it sent one, else the HTTP status. */
export async function errorReason(res: Response): Promise<string> {
  try {
    const body = await res.json();
    if (body.message) return body.message as string;
  } catch {
    // non-JSON error body
  }
  return `HTTP ${res.status}`;
}

export async function listSaved(): Promise<SavedItem[]> {
  const res = await fetch("/api/v1/saved", { signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!res.ok) throw new Error(await errorReason(res));
  return (await res.json()) as SavedItem[];
}

export async function saveItem(
  lookup: Lookup,
  context: { word: string; sentence: string; articleUrl: string },
): Promise<SavedItem> {
  const res = await fetch("/api/v1/saved", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...lookup, ...context }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(await errorReason(res));
  return (await res.json()) as SavedItem;
}

/** Removing something that is already gone (e.g. deleted on another device) counts as success. */
export async function deleteSaved(id: string): Promise<void> {
  const res = await fetch(`/api/v1/saved/${encodeURIComponent(id)}`, {
    method: "DELETE",
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!res.ok && res.status !== 404) throw new Error(await errorReason(res));
}
