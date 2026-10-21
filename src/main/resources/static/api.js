const TIMEOUT_MS = 10000;
/** The server's error message if it sent one, else the HTTP status. */
export async function errorReason(res) {
    try {
        const body = await res.json();
        if (body.message)
            return body.message;
    }
    catch {
        // non-JSON error body
    }
    return `HTTP ${res.status}`;
}
export async function listSaved() {
    const res = await fetch("/api/v1/saved", { signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (!res.ok)
        throw new Error(await errorReason(res));
    return (await res.json());
}
export async function saveItem(lookup, context) {
    const res = await fetch("/api/v1/saved", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...lookup, ...context }),
        signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!res.ok)
        throw new Error(await errorReason(res));
    return (await res.json());
}
/** Removing something that is already gone (e.g. deleted on another device) counts as success. */
export async function deleteSaved(id) {
    const res = await fetch(`/api/v1/saved/${encodeURIComponent(id)}`, {
        method: "DELETE",
        signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!res.ok && res.status !== 404)
        throw new Error(await errorReason(res));
}
/**
 * POST /lookup/stream. Calls onField(name, value) as each field arrives and resolves with the full
 * answer. Rejects with the server's message on an HTTP error or an `error` event. Server-sent events
 * are read by hand because EventSource cannot POST.
 */
export async function streamLookup(body, signal, onField) {
    const res = await fetch("/api/v1/lookup/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        body: JSON.stringify(body),
        signal,
    });
    if (!res.ok)
        throw new Error(await errorReason(res));
    if (!res.body)
        throw new Error("No response body");
    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let pending = "";
    for (;;) {
        const { done, value } = await reader.read();
        if (done)
            break;
        pending += value.replace(/\r\n/g, "\n");
        let end;
        while ((end = pending.indexOf("\n\n")) >= 0) {
            const block = pending.slice(0, end);
            pending = pending.slice(end + 2);
            let event = "message";
            const data = [];
            for (const line of block.split("\n")) {
                if (line.startsWith("event:"))
                    event = line.slice(6).trim();
                else if (line.startsWith("data:"))
                    data.push(line.slice(5).replace(/^ /, ""));
            }
            if (!data.length)
                continue;
            const payload = JSON.parse(data.join("\n"));
            if (event === "field")
                onField(payload.name, payload.value);
            else if (event === "done")
                return payload;
            else if (event === "error")
                throw new Error(payload.message ?? "Lookup failed");
        }
    }
    throw new Error("The lookup ended before an answer arrived");
}
