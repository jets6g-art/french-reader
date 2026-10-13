"use strict";
const form = document.getElementById("load-form");
const urlInput = document.getElementById("url");
const statusEl = document.getElementById("status");
const articleEl = document.getElementById("article");
const popupEl = document.getElementById("popup");
const popupBody = document.getElementById("popup-body");
const popupClose = document.getElementById("popup-close");
const HAS_WORD_CHAR = /[\p{L}\p{N}]/u;
const LOOKUP_TIMEOUT_MS = 10000; // matches the backend's 10s LLM budget
// Sentences by id for the current article, so a click can recover its containing sentence.
let sentencesById = new Map();
let articleUrl = "";
let selected = null;
// The in-flight lookup; a newer click aborts it so a slow reply can't overwrite a newer popup.
let lookupInFlight = null;
function setStatus(msg, isError = false) {
    statusEl.textContent = msg;
    statusEl.hidden = msg === "";
    statusEl.classList.toggle("error", isError);
}
async function loadArticle(url) {
    setStatus("Loading…");
    articleEl.replaceChildren();
    clearSelection();
    try {
        const res = await fetch(`/api/v1/article?url=${encodeURIComponent(url)}`, {
            signal: AbortSignal.timeout(15000),
        });
        if (!res.ok) {
            let reason = `HTTP ${res.status}`;
            try {
                const body = await res.json();
                if (body.message)
                    reason = body.message;
            }
            catch {
                // non-JSON error body; keep the status line
            }
            throw new Error(reason);
        }
        render((await res.json()));
        setStatus("");
    }
    catch (e) {
        const timedOut = e instanceof DOMException && e.name === "TimeoutError";
        setStatus(timedOut ? "Timed out loading the article." : `Could not load article: ${e.message}`, true);
    }
}
// Everything is built with createElement/textContent — article text is never parsed as HTML.
function render(article) {
    document.title = `${article.title} — French Reader`;
    sentencesById = new Map(article.sentences.map((s) => [s.id, s]));
    articleUrl = article.url;
    const header = document.createElement("header");
    const h1 = document.createElement("h1");
    h1.textContent = article.title;
    const source = document.createElement("p");
    source.className = "source";
    source.textContent = article.source;
    header.append(h1, source);
    const blocks = [header];
    let current = null;
    let currentParagraph = -1;
    for (const sentence of article.sentences) {
        if (sentence.paragraph !== currentParagraph) {
            currentParagraph = sentence.paragraph;
            current = document.createElement(sentence.kind === "heading" ? "h2" : sentence.kind === "quote" ? "blockquote" : "p");
            blocks.push(current);
        }
        current.append(renderSentence(sentence));
    }
    articleEl.replaceChildren(...blocks);
}
function renderSentence(sentence) {
    const el = document.createElement("span");
    el.className = "s";
    el.dataset.sid = String(sentence.id);
    for (const t of sentence.tokens) {
        if (HAS_WORD_CHAR.test(t.text)) {
            const w = document.createElement("span");
            w.className = "w";
            w.dataset.idx = String(t.idx);
            w.textContent = t.text;
            el.append(w);
        }
        else {
            el.append(t.text); // punctuation: plain text, not clickable
        }
        // Trailing whitespace stays outside the word span so highlights don't include it.
        if (t.ws)
            el.append(t.ws);
    }
    return el;
}
function clearSelection() {
    lookupInFlight?.abort();
    lookupInFlight = null;
    selected?.classList.remove("selected");
    selected = null;
    popupEl.hidden = true;
}
function popupLine(className, text) {
    const p = document.createElement("p");
    p.className = className;
    p.textContent = text;
    return p;
}
function showLoading(word) {
    popupBody.replaceChildren(popupLine("popup-word", word), popupLine("popup-muted", "Looking up…"));
    popupEl.hidden = false;
}
function showError(word, message, retry) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Retry";
    button.addEventListener("click", retry);
    popupBody.replaceChildren(popupLine("popup-word", word), popupLine("popup-error", message), button);
}
function showResult(word, r) {
    const meta = [r.lemma !== word ? `→ ${r.lemma}` : "", r.pos, r.register].filter(Boolean).join(" · ");
    popupBody.replaceChildren(popupLine("popup-word", word), popupLine("popup-muted", meta), popupLine("popup-meaning", r.meaning), popupLine("popup-gloss", r.gloss), popupLine("popup-note", r.note));
}
async function lookup(word, sentence) {
    lookupInFlight?.abort();
    const ctl = new AbortController();
    lookupInFlight = ctl;
    let timedOut = false;
    const timer = setTimeout(() => {
        timedOut = true;
        ctl.abort();
    }, LOOKUP_TIMEOUT_MS);
    showLoading(word);
    try {
        const res = await fetch("/api/v1/lookup", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ word, sentence, articleUrl }),
            signal: ctl.signal,
        });
        if (!res.ok) {
            let reason = `HTTP ${res.status}`;
            try {
                const body = await res.json();
                if (body.message)
                    reason = body.message;
            }
            catch {
                // non-JSON error body; keep the status line
            }
            throw new Error(reason);
        }
        const result = (await res.json());
        if (lookupInFlight === ctl)
            showResult(word, result);
    }
    catch (e) {
        if (lookupInFlight !== ctl)
            return; // superseded or closed: nothing to report
        const message = timedOut
            ? `Lookup timed out after ${LOOKUP_TIMEOUT_MS / 1000}s.`
            : `Lookup failed: ${e.message}`;
        showError(word, message, () => void lookup(word, sentence));
    }
    finally {
        clearTimeout(timer);
    }
}
articleEl.addEventListener("click", (ev) => {
    const word = ev.target.closest(".w");
    if (!word)
        return;
    const sentence = sentencesById.get(Number(word.closest(".s").dataset.sid));
    if (!sentence)
        return;
    selected?.classList.remove("selected");
    selected = word;
    word.classList.add("selected");
    const sentenceText = sentence.tokens.map((t) => t.text + t.ws).join("").trim();
    void lookup(word.textContent ?? "", sentenceText);
});
popupClose.addEventListener("click", clearSelection);
document.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape")
        clearSelection();
});
form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    const url = urlInput.value.trim();
    if (!url)
        return;
    history.replaceState(null, "", `?url=${encodeURIComponent(url)}`);
    void loadArticle(url);
});
const initial = new URLSearchParams(location.search).get("url");
if (initial) {
    urlInput.value = initial;
    void loadArticle(initial);
}
