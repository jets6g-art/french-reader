import { deleteSaved, listSaved, saveItem, streamLookup, translateParagraph } from "./api.js";
const form = document.getElementById("load-form");
const urlInput = document.getElementById("url");
const statusEl = document.getElementById("status");
const articleEl = document.getElementById("article");
const popupEl = document.getElementById("popup");
const popupBody = document.getElementById("popup-body");
const popupClose = document.getElementById("popup-close");
const savedLink = document.getElementById("saved-link");
const HAS_WORD_CHAR = /[\p{L}\p{N}]/u;
const TITLE_SID = -1; // article sentence ids start at 0
const LOOKUP_TIMEOUT_MS = 10000; // matches the backend's 10s LLM budget
// Sentences by id for the current article, so a click can recover its containing sentence.
let sentencesById = new Map();
let articleUrl = "";
let selected = null;
// The in-flight lookup; a newer click aborts it so a slow reply can't overwrite a newer popup.
let lookupInFlight = null;
// Saved entries by normalized lemma -> id, so the popup knows whether to offer Save or Remove.
const savedByLemma = new Map();
const lemmaKey = (s) => s.normalize("NFC").trim().toLowerCase().replace(/’/g, "'");
function updateSavedLink() {
    savedLink.textContent = savedByLemma.size ? `Saved (${savedByLemma.size})` : "Saved";
}
async function refreshSaved() {
    try {
        const items = await listSaved();
        savedByLemma.clear();
        for (const i of items)
            savedByLemma.set(lemmaKey(i.lemma), i.id);
        updateSavedLink();
    }
    catch {
        // Reading doesn't depend on the list; Save still works and corrects the state itself.
    }
}
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
    // The title is one clickable "sentence" with a reserved id, so word clicks treat it like the body.
    const titleSentence = { id: TITLE_SID, paragraph: -1, kind: "heading", tokens: article.titleTokens };
    sentencesById.set(TITLE_SID, titleSentence);
    articleUrl = article.url;
    const header = document.createElement("header");
    const h1 = document.createElement("h1");
    h1.dataset.para = String(titleSentence.paragraph);
    h1.append(renderSentence(titleSentence));
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
            current.dataset.para = String(currentParagraph);
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
    popupBody.classList.remove("has-idiom");
    popupBody.replaceChildren(popupLine("popup-word", word), popupLine("popup-muted", "Looking up…"));
    popupEl.hidden = false;
}
function showError(word, message, retry) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Retry";
    button.addEventListener("click", retry);
    popupBody.classList.remove("has-idiom");
    popupBody.replaceChildren(popupLine("popup-word", word), popupLine("popup-error", message), button);
}
function saveButton(word, sentence, r) {
    const wrap = document.createElement("div");
    const button = document.createElement("button");
    button.type = "button";
    const error = popupLine("popup-error", "");
    error.hidden = true;
    const key = lemmaKey(r.lemma);
    const render = () => {
        button.textContent = savedByLemma.has(key) ? "★ Saved · tap to remove" : "☆ Save";
    };
    render();
    button.addEventListener("click", async () => {
        button.disabled = true;
        error.hidden = true;
        try {
            const id = savedByLemma.get(key);
            if (id) {
                await deleteSaved(id);
                savedByLemma.delete(key);
            }
            else {
                const item = await saveItem(r, { word, sentence, articleUrl });
                savedByLemma.set(key, item.id);
            }
        }
        catch (e) {
            error.textContent = `Could not update the saved list: ${e.message}`;
            error.hidden = false;
        }
        finally {
            button.disabled = false;
            render();
            updateSavedLink();
        }
    });
    wrap.append(button, error);
    return wrap;
}
function wordLines(word, r) {
    const meta = [r.lemma && r.lemma !== word ? `→ ${r.lemma}` : "", r.pos, r.register].filter(Boolean).join(" · ");
    const lines = [popupLine("popup-word", word)];
    if (meta)
        lines.push(popupLine("popup-muted", meta));
    if (r.meaning)
        lines.push(popupLine("popup-meaning", r.meaning));
    if (r.gloss)
        lines.push(popupLine("popup-gloss", r.gloss));
    if (r.note)
        lines.push(popupLine("popup-note", r.note));
    return lines;
}
/** The idiom the word belongs to, below the word itself; nothing when there is none. */
function idiomSection(r, save) {
    if (!r.idiom)
        return [];
    const section = document.createElement("div");
    section.className = "popup-idiom";
    section.append(popupLine("popup-idiom-label", "Part of the idiom"), popupLine("popup-word", r.idiom));
    if (r.idiomMeaning)
        section.append(popupLine("popup-meaning", r.idiomMeaning));
    if (r.idiomGloss)
        section.append(popupLine("popup-gloss", r.idiomGloss));
    if (r.idiomNote)
        section.append(popupLine("popup-note", r.idiomNote));
    if (save)
        section.append(save);
    return [section];
}
/** The word and the idiom are saved as separate entries, each with its own Save button. */
const wordEntry = (r) => ({
    lemma: r.lemma, pos: r.pos, meaning: r.meaning, register: r.register, gloss: r.gloss, note: r.note,
});
const idiomEntry = (r) => ({
    lemma: r.idiom ?? "", pos: "idiom", meaning: r.idiomMeaning ?? "", register: "",
    gloss: r.idiomGloss ?? "", note: r.idiomNote ?? "",
});
/** What has arrived so far, with a marker that the rest is still coming. */
/**
 * Fills the popup with the word's column and, when there is one, the idiom's column beside it
 * (side by side keeps the popup about as tall as a word-only answer; see .has-idiom in style.css).
 */
function showColumns(wordColumn, idiom) {
    popupBody.classList.toggle("has-idiom", idiom.length > 0);
    if (!idiom.length) {
        popupBody.replaceChildren(...wordColumn);
        return;
    }
    const word = document.createElement("div");
    word.className = "popup-word-col";
    word.append(...wordColumn);
    popupBody.replaceChildren(word, ...idiom);
}
function showPartial(word, fields) {
    showColumns([...wordLines(word, fields), popupLine("popup-muted", "…")], idiomSection(fields));
}
function showResult(word, sentence, r) {
    const idiomSave = r.idiom && r.idiomMeaning ? saveButton(word, sentence, idiomEntry(r)) : undefined;
    showColumns([...wordLines(word, r), saveButton(word, sentence, wordEntry(r))], idiomSection(r, idiomSave));
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
        const partial = {};
        const result = await streamLookup({ word, sentence, articleUrl }, ctl.signal, (name, value) => {
            partial[name] = value;
            if (lookupInFlight === ctl)
                showPartial(word, partial);
        });
        if (lookupInFlight === ctl)
            showResult(word, sentence, result);
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
// A single click waits this long before looking the word up, so the first click of a double-click
// (which translates the paragraph instead) never shows the word popup. Most double-clicks land well
// inside it; a slower one still works, it just flashes the popup first.
const DOUBLE_CLICK_MS = 300;
let pendingClick;
articleEl.addEventListener("click", (ev) => {
    if (ev.detail > 1)
        return; // second click of a double-click: that one translates instead
    const word = ev.target.closest(".w");
    if (!word)
        return;
    const sentence = sentencesById.get(Number(word.closest(".s").dataset.sid));
    if (!sentence)
        return;
    clearTimeout(pendingClick);
    pendingClick = setTimeout(() => {
        selected?.classList.remove("selected");
        selected = word;
        word.classList.add("selected");
        const sentenceText = sentence.tokens.map((t) => t.text + t.ws).join("").trim();
        void lookup(word.textContent ?? "", sentenceText);
    }, DOUBLE_CLICK_MS);
});
// Double-clicking a word translates its whole paragraph into a box just below it, which then stays.
// The first click of the pair is still waiting (see DOUBLE_CLICK_MS): cancel it.
function paragraphText(para) {
    return [...sentencesById.values()]
        .filter((s) => s.paragraph === para)
        .sort((a, b) => a.id - b.id)
        .map((s) => s.tokens.map((t) => t.text + t.ws).join(""))
        .join("")
        .trim();
}
async function fillTranslation(box, paragraph) {
    const label = popupLine("translation-label", "English translation");
    box.replaceChildren(label, popupLine("translation-pending", "Translating…"));
    try {
        const text = await translateParagraph(paragraph, articleUrl);
        box.replaceChildren(label, popupLine("translation-text", text));
    }
    catch (e) {
        const timedOut = e instanceof DOMException && e.name === "TimeoutError";
        const retry = document.createElement("button");
        retry.type = "button";
        retry.textContent = "Retry";
        retry.addEventListener("click", () => void fillTranslation(box, paragraph));
        const message = timedOut ? "Translation timed out." : `Translation failed: ${e.message}`;
        box.replaceChildren(label, popupLine("popup-error", message), retry);
    }
}
// Keep a double-click from also selecting (highlighting) the word's text.
articleEl.addEventListener("mousedown", (ev) => {
    if (ev.detail > 1 && ev.target.closest(".w"))
        ev.preventDefault();
});
articleEl.addEventListener("dblclick", (ev) => {
    const word = ev.target.closest(".w");
    const block = word?.closest("[data-para]");
    if (!block)
        return;
    clearTimeout(pendingClick);
    clearSelection(); // in case a slow double-click let the lookup start anyway
    // Already translated (or translating): keep it; a failed one has its own Retry button.
    if (block.nextElementSibling?.classList.contains("translation"))
        return;
    const box = document.createElement("div");
    box.className = "translation";
    box.lang = "en";
    block.after(box);
    void fillTranslation(box, paragraphText(Number(block.dataset.para)));
});
// The popup covers the bottom of the screen. While it is open, pad the page by its height so the end
// of the article can still be scrolled above it, and lift the selected word out from under it. This
// reruns whenever the popup changes size, including while a streamed answer fills it in.
const POPUP_GAP_PX = 16;
new ResizeObserver(() => {
    const height = popupEl.hidden ? 0 : popupEl.offsetHeight;
    document.body.style.paddingBottom = height ? `${height + POPUP_GAP_PX}px` : "";
    if (!height || !selected)
        return;
    const overlap = selected.getBoundingClientRect().bottom - (window.innerHeight - height - POPUP_GAP_PX);
    if (overlap > 0)
        window.scrollBy({ top: overlap, behavior: "smooth" });
}).observe(popupEl);
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
void refreshSaved();
const initial = new URLSearchParams(location.search).get("url");
if (initial) {
    urlInput.value = initial;
    void loadArticle(initial);
}
