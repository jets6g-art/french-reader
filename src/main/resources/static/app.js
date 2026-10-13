"use strict";
const form = document.getElementById("load-form");
const urlInput = document.getElementById("url");
const statusEl = document.getElementById("status");
const articleEl = document.getElementById("article");
const selectionEl = document.getElementById("selection");
const HAS_WORD_CHAR = /[\p{L}\p{N}]/u;
// Sentences by id for the current article, so a click can recover its containing sentence.
let sentencesById = new Map();
let selected = null;
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
    selected?.classList.remove("selected");
    selected = null;
    selectionEl.hidden = true;
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
    // Step 3 will POST { word, sentence, articleUrl } to /api/v1/lookup from here.
    const sentenceText = sentence.tokens.map((t) => t.text + t.ws).join("").trim();
    selectionEl.replaceChildren();
    const strong = document.createElement("strong");
    strong.textContent = word.textContent;
    selectionEl.append(strong, ` — ${sentenceText}`);
    selectionEl.hidden = false;
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
