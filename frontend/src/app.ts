import { deleteSaved, listSaved, saveItem, streamLookup, translateParagraph } from "./api.js";
import type { Lookup, LookupResult } from "./types.js";

interface Token {
  idx: number;
  text: string;
  ws: string;
}

interface Sentence {
  id: number;
  paragraph: number;
  kind: "p" | "heading" | "quote";
  tokens: Token[];
}

interface Article {
  title: string;
  titleTokens: Token[];
  source: string;
  url: string;
  sentences: Sentence[];
}

const form = document.getElementById("load-form") as HTMLFormElement;
const urlInput = document.getElementById("url") as HTMLInputElement;
const statusEl = document.getElementById("status") as HTMLElement;
const articleEl = document.getElementById("article") as HTMLElement;
const popupEl = document.getElementById("popup") as HTMLElement;
const popupBody = document.getElementById("popup-body") as HTMLElement;
const popupClose = document.getElementById("popup-close") as HTMLButtonElement;
const savedLink = document.getElementById("saved-link") as HTMLAnchorElement;
const pasteToggle = document.getElementById("paste-toggle") as HTMLButtonElement;
const textForm = document.getElementById("text-form") as HTMLFormElement;
const textInput = document.getElementById("article-text") as HTMLTextAreaElement;
const textCancel = document.getElementById("text-cancel") as HTMLButtonElement;

const HAS_WORD_CHAR = /[\p{L}\p{N}]/u;
const TITLE_SID = -1; // article sentence ids start at 0
const LOOKUP_TIMEOUT_MS = 10_000; // matches the backend's 10s LLM budget
// Pasted text has no URL to reload from, so this browser keeps it until a URL is read instead.
const PASTED_KEY = "french-reader.pasted-text";
// Words already looked up stay highlighted for review: token idx per article, in this browser only.
const MARKS_KEY = "french-reader.marked-words";
const MAX_MARKED_ARTICLES = 100;

// Sentences by id for the current article, so a click can recover its containing sentence.
let sentencesById = new Map<number, Sentence>();
let articleUrl = "";
let selected: HTMLElement | null = null;
// Whether the open word was unmarked before this click, so a click that turns into a double-click
// (a translation) doesn't leave a highlight behind.
let selectedIsNew = false;
// Looked-up words of the current article and the storage key they are kept under.
let marked = new Set<number>();
let marksKey = "";
// The in-flight lookup; a newer click aborts it so a slow reply can't overwrite a newer popup.
let lookupInFlight: AbortController | null = null;

// Saved entries by normalized lemma -> id, so the popup knows whether to offer Save or Remove.
const savedByLemma = new Map<string, string>();
const lemmaKey = (s: string): string => s.normalize("NFC").trim().toLowerCase().replace(/’/g, "'");

function updateSavedLink(): void {
  savedLink.textContent = savedByLemma.size ? `Saved (${savedByLemma.size})` : "Saved";
}

async function refreshSaved(): Promise<void> {
  try {
    const items = await listSaved();
    savedByLemma.clear();
    for (const i of items) savedByLemma.set(lemmaKey(i.lemma), i.id);
    updateSavedLink();
  } catch {
    // Reading doesn't depend on the list; Save still works and corrects the state itself.
  }
}

function readMarks(): Record<string, number[]> {
  try {
    return (JSON.parse(localStorage.getItem(MARKS_KEY) ?? "{}") as Record<string, number[]>) ?? {};
  } catch {
    return {};
  }
}

function saveMarks(): void {
  try {
    const all = readMarks();
    delete all[marksKey]; // re-added last: key order is recency, oldest articles drop off first
    if (marked.size) all[marksKey] = [...marked];
    const keys = Object.keys(all);
    for (const k of keys.slice(0, Math.max(0, keys.length - MAX_MARKED_ARTICLES))) delete all[k];
    localStorage.setItem(MARKS_KEY, JSON.stringify(all));
  } catch {
    // private window or storage blocked: highlights last until the page is left
  }
}

/** Pasted text has no URL, so its highlights are keyed by a hash of the text itself. */
function articleKey(article: Article): string {
  if (article.url) return article.url;
  const text = [article.titleTokens, ...article.sentences.map((s) => s.tokens)]
    .flat()
    .map((t) => t.text + t.ws)
    .join("");
  let h = 0x811c9dc5; // FNV-1a
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 0x01000193);
  return `pasted:${(h >>> 0).toString(36)}`;
}

function setMarked(word: HTMLElement, on: boolean): void {
  word.classList.toggle("looked-up", on);
  const idx = Number(word.dataset.idx);
  if (on) marked.add(idx);
  else marked.delete(idx);
  saveMarks();
}

function setStatus(msg: string, isError = false): void {
  statusEl.textContent = msg;
  statusEl.hidden = msg === "";
  statusEl.classList.toggle("error", isError);
}

function loadArticle(url: string): Promise<void> {
  return showArticle((signal) => fetch(`/api/v1/article?url=${encodeURIComponent(url)}`, { signal }));
}

function loadText(text: string): Promise<void> {
  return showArticle((signal) =>
    fetch("/api/v1/article/text", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
      signal,
    }),
  );
}

async function showArticle(request: (signal: AbortSignal) => Promise<Response>): Promise<void> {
  setStatus("Loading…");
  articleEl.replaceChildren();
  clearSelection();
  try {
    const res = await request(AbortSignal.timeout(15000));
    if (!res.ok) {
      let reason = `HTTP ${res.status}`;
      try {
        const body = await res.json();
        if (body.message) reason = body.message;
      } catch {
        // non-JSON error body; keep the status line
      }
      throw new Error(reason);
    }
    render((await res.json()) as Article);
    setStatus("");
  } catch (e) {
    const timedOut = e instanceof DOMException && e.name === "TimeoutError";
    setStatus(timedOut ? "Timed out loading the article." : `Could not load article: ${(e as Error).message}`, true);
  }
}

// Everything is built with createElement/textContent — article text is never parsed as HTML.
function render(article: Article): void {
  document.title = `${article.title || article.source} — French Reader`;
  sentencesById = new Map(article.sentences.map((s) => [s.id, s]));
  // The title is one clickable "sentence" with a reserved id, so word clicks treat it like the body.
  const titleSentence: Sentence = { id: TITLE_SID, paragraph: -1, kind: "heading", tokens: article.titleTokens };
  sentencesById.set(TITLE_SID, titleSentence);
  articleUrl = article.url;
  marksKey = articleKey(article);
  marked = new Set(readMarks()[marksKey] ?? []);

  const header = document.createElement("header");
  if (article.titleTokens.length) {
    // Pasted text may have no headline.
    const h1 = document.createElement("h1");
    h1.dataset.para = String(titleSentence.paragraph);
    h1.append(renderSentence(titleSentence));
    header.append(h1);
  }
  const source = document.createElement("p");
  source.className = "source";
  source.textContent = article.source;
  header.append(source);

  const blocks: Node[] = [header];
  let current: HTMLElement | null = null;
  let currentParagraph = -1;
  for (const sentence of article.sentences) {
    if (sentence.paragraph !== currentParagraph) {
      currentParagraph = sentence.paragraph;
      current = document.createElement(
        sentence.kind === "heading" ? "h2" : sentence.kind === "quote" ? "blockquote" : "p",
      );
      current.dataset.para = String(currentParagraph);
      blocks.push(current);
    }
    current!.append(renderSentence(sentence));
  }
  articleEl.replaceChildren(...blocks);
}

function renderSentence(sentence: Sentence): HTMLElement {
  const el = document.createElement("span");
  el.className = "s";
  el.dataset.sid = String(sentence.id);
  for (const t of sentence.tokens) {
    if (HAS_WORD_CHAR.test(t.text)) {
      const w = document.createElement("span");
      w.className = marked.has(t.idx) ? "w looked-up" : "w";
      w.dataset.idx = String(t.idx);
      w.textContent = t.text;
      el.append(w);
    } else {
      el.append(t.text); // punctuation: plain text, not clickable
    }
    // Trailing whitespace stays outside the word span so highlights don't include it.
    if (t.ws) el.append(t.ws);
  }
  return el;
}

function clearSelection(): void {
  lookupInFlight?.abort();
  lookupInFlight = null;
  selected?.classList.remove("selected");
  selected = null;
  popupEl.hidden = true;
}

function popupLine(className: string, text: string): HTMLElement {
  const p = document.createElement("p");
  p.className = className;
  p.textContent = text;
  return p;
}

/** The clicked word, with an Unmark button for a word clicked by accident. */
function wordHeading(word: string): HTMLElement {
  const heading = popupLine("popup-word", word);
  const unmark = document.createElement("button");
  unmark.type = "button";
  unmark.className = "unmark";
  unmark.textContent = "Unmark";
  unmark.title = "Remove this word's highlight";
  unmark.addEventListener("click", unmarkSelected);
  heading.append(unmark);
  return heading;
}

function unmarkSelected(): void {
  if (selected) setMarked(selected, false);
  clearSelection();
}

function showLoading(word: string): void {
  popupBody.classList.remove("has-idiom");
  popupBody.replaceChildren(wordHeading(word), popupLine("popup-muted", "Looking up…"));
  popupEl.hidden = false;
}

function showError(word: string, message: string, retry: () => void): void {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = "Retry";
  button.addEventListener("click", retry);
  popupBody.classList.remove("has-idiom");
  popupBody.replaceChildren(wordHeading(word), popupLine("popup-error", message), button);
}

function saveButton(word: string, sentence: string, r: Lookup): HTMLElement {
  const wrap = document.createElement("div");
  const button = document.createElement("button");
  button.type = "button";
  const error = popupLine("popup-error", "");
  error.hidden = true;

  const key = lemmaKey(r.lemma);
  const render = (): void => {
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
      } else {
        const item = await saveItem(r, { word, sentence, articleUrl });
        savedByLemma.set(key, item.id);
      }
    } catch (e) {
      error.textContent = `Could not update the saved list: ${(e as Error).message}`;
      error.hidden = false;
    } finally {
      button.disabled = false;
      render();
      updateSavedLink();
    }
  });
  wrap.append(button, error);
  return wrap;
}

function wordLines(word: string, r: Partial<LookupResult>): HTMLElement[] {
  const meta = [r.lemma && r.lemma !== word ? `→ ${r.lemma}` : "", r.pos, r.register].filter(Boolean).join(" · ");
  const lines = [wordHeading(word)];
  if (meta) lines.push(popupLine("popup-muted", meta));
  if (r.meaning) lines.push(popupLine("popup-meaning", r.meaning));
  if (r.gloss) lines.push(popupLine("popup-gloss", r.gloss));
  if (r.note) lines.push(popupLine("popup-note", r.note));
  return lines;
}

/** The idiom the word belongs to, below the word itself; nothing when there is none. */
function idiomSection(r: Partial<LookupResult>, save?: HTMLElement): HTMLElement[] {
  if (!r.idiom) return [];
  const section = document.createElement("div");
  section.className = "popup-idiom";
  section.append(popupLine("popup-idiom-label", "Part of the idiom"), popupLine("popup-word", r.idiom));
  if (r.idiomMeaning) section.append(popupLine("popup-meaning", r.idiomMeaning));
  if (r.idiomGloss) section.append(popupLine("popup-gloss", r.idiomGloss));
  if (r.idiomNote) section.append(popupLine("popup-note", r.idiomNote));
  if (save) section.append(save);
  return [section];
}

/** The word and the idiom are saved as separate entries, each with its own Save button. */
const wordEntry = (r: LookupResult): Lookup => ({
  lemma: r.lemma, pos: r.pos, meaning: r.meaning, register: r.register, gloss: r.gloss, note: r.note,
});
const idiomEntry = (r: LookupResult): Lookup => ({
  lemma: r.idiom ?? "", pos: "idiom", meaning: r.idiomMeaning ?? "", register: "",
  gloss: r.idiomGloss ?? "", note: r.idiomNote ?? "",
});

/** What has arrived so far, with a marker that the rest is still coming. */
/**
 * Fills the popup with the word's column and, when there is one, the idiom's column beside it
 * (side by side keeps the popup about as tall as a word-only answer; see .has-idiom in style.css).
 */
function showColumns(wordColumn: HTMLElement[], idiom: HTMLElement[]): void {
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

function showPartial(word: string, fields: Partial<LookupResult>): void {
  showColumns([...wordLines(word, fields), popupLine("popup-muted", "…")], idiomSection(fields));
}

function showResult(word: string, sentence: string, r: LookupResult): void {
  const idiomSave = r.idiom && r.idiomMeaning ? saveButton(word, sentence, idiomEntry(r)) : undefined;
  showColumns([...wordLines(word, r), saveButton(word, sentence, wordEntry(r))], idiomSection(r, idiomSave));
}

async function lookup(word: string, sentence: string): Promise<void> {
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
    const partial: Partial<LookupResult> = {};
    const result = await streamLookup({ word, sentence, articleUrl }, ctl.signal, (name, value) => {
      partial[name] = value;
      if (lookupInFlight === ctl) showPartial(word, partial);
    });
    if (lookupInFlight === ctl) showResult(word, sentence, result);
  } catch (e) {
    if (lookupInFlight !== ctl) return; // superseded or closed: nothing to report
    const message = timedOut
      ? `Lookup timed out after ${LOOKUP_TIMEOUT_MS / 1000}s.`
      : `Lookup failed: ${(e as Error).message}`;
    showError(word, message, () => void lookup(word, sentence));
  } finally {
    clearTimeout(timer);
  }
}

// A single click waits this long before looking the word up, so the first click of a double-click
// (which translates the paragraph instead) never shows the word popup. Most double-clicks land well
// inside it; a slower one still works, it just flashes the popup first.
const DOUBLE_CLICK_MS = 300;
let pendingClick: number | undefined;

articleEl.addEventListener("click", (ev) => {
  if (ev.detail > 1) return; // second click of a double-click: that one translates instead
  const word = (ev.target as HTMLElement).closest<HTMLElement>(".w");
  if (!word) return;
  const sentence = sentencesById.get(Number(word.closest<HTMLElement>(".s")!.dataset.sid));
  if (!sentence) return;

  clearTimeout(pendingClick);
  pendingClick = setTimeout(() => {
    if (word === selected) {
      // Clicking the open word again takes it back, for a word clicked by accident.
      unmarkSelected();
      return;
    }
    selected?.classList.remove("selected");
    selected = word;
    selectedIsNew = !word.classList.contains("looked-up");
    word.classList.add("selected");
    setMarked(word, true);

    const sentenceText = sentence.tokens.map((t) => t.text + t.ws).join("").trim();
    void lookup(word.textContent ?? "", sentenceText);
  }, DOUBLE_CLICK_MS);
});

// Double-clicking a word translates its whole paragraph into a box just below it, which then stays.
// The first click of the pair is still waiting (see DOUBLE_CLICK_MS): cancel it.

function paragraphText(para: number): string {
  return [...sentencesById.values()]
    .filter((s) => s.paragraph === para)
    .sort((a, b) => a.id - b.id)
    .map((s) => s.tokens.map((t) => t.text + t.ws).join(""))
    .join("")
    .trim();
}

async function fillTranslation(box: HTMLElement, paragraph: string): Promise<void> {
  const label = popupLine("translation-label", "English translation");
  box.replaceChildren(label, popupLine("translation-pending", "Translating…"));
  try {
    const text = await translateParagraph(paragraph, articleUrl);
    box.replaceChildren(label, popupLine("translation-text", text));
  } catch (e) {
    const timedOut = e instanceof DOMException && e.name === "TimeoutError";
    const retry = document.createElement("button");
    retry.type = "button";
    retry.textContent = "Retry";
    retry.addEventListener("click", () => void fillTranslation(box, paragraph));
    const message = timedOut ? "Translation timed out." : `Translation failed: ${(e as Error).message}`;
    box.replaceChildren(label, popupLine("popup-error", message), retry);
  }
}

// Keep a double-click from also selecting (highlighting) the word's text.
articleEl.addEventListener("mousedown", (ev) => {
  if (ev.detail > 1 && (ev.target as HTMLElement).closest(".w")) ev.preventDefault();
});

articleEl.addEventListener("dblclick", (ev) => {
  const word = (ev.target as HTMLElement).closest<HTMLElement>(".w");
  const block = word?.closest<HTMLElement>("[data-para]");
  if (!block) return;
  clearTimeout(pendingClick);
  // In case a slow double-click let the lookup start anyway: undo that click's highlight too.
  if (selected && selectedIsNew) setMarked(selected, false);
  clearSelection();

  // Already translated (or translating): keep it; a failed one has its own Retry button.
  if (block.nextElementSibling?.classList.contains("translation")) return;
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
  if (!height || !selected) return;
  const overlap = selected.getBoundingClientRect().bottom - (window.innerHeight - height - POPUP_GAP_PX);
  if (overlap > 0) window.scrollBy({ top: overlap, behavior: "smooth" });
}).observe(popupEl);

popupClose.addEventListener("click", clearSelection);
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape") clearSelection();
});

function storePasted(text: string | null): void {
  try {
    if (text === null) localStorage.removeItem(PASTED_KEY);
    else localStorage.setItem(PASTED_KEY, text);
  } catch {
    // private window or storage blocked: the text just won't survive a reload
  }
}

function storedPasted(): string | null {
  try {
    return localStorage.getItem(PASTED_KEY);
  } catch {
    return null;
  }
}

function showTextForm(show: boolean): void {
  textForm.hidden = !show;
  pasteToggle.setAttribute("aria-expanded", String(show));
  if (show) textInput.focus();
}

form.addEventListener("submit", (ev) => {
  ev.preventDefault();
  const url = urlInput.value.trim();
  if (!url) return;
  showTextForm(false);
  storePasted(null);
  history.replaceState(null, "", `?url=${encodeURIComponent(url)}`);
  void loadArticle(url);
});

pasteToggle.addEventListener("click", () => showTextForm(textForm.hidden));
textCancel.addEventListener("click", () => showTextForm(false));

textForm.addEventListener("submit", (ev) => {
  ev.preventDefault();
  const text = textInput.value.trim();
  if (!text) return;
  showTextForm(false);
  storePasted(text);
  urlInput.value = "";
  history.replaceState(null, "", location.pathname);
  void loadText(text);
});

void refreshSaved();

const initial = new URLSearchParams(location.search).get("url");
const pasted = storedPasted();
if (initial) {
  urlInput.value = initial;
  void loadArticle(initial);
} else if (pasted) {
  textInput.value = pasted;
  void loadText(pasted);
}
