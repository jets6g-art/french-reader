import { deleteSaved, listSaved, saveItem } from "./api.js";
import type { Lookup } from "./types.js";

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

const HAS_WORD_CHAR = /[\p{L}\p{N}]/u;
const LOOKUP_TIMEOUT_MS = 10_000; // matches the backend's 10s LLM budget

// Sentences by id for the current article, so a click can recover its containing sentence.
let sentencesById = new Map<number, Sentence>();
let articleUrl = "";
let selected: HTMLElement | null = null;
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

function setStatus(msg: string, isError = false): void {
  statusEl.textContent = msg;
  statusEl.hidden = msg === "";
  statusEl.classList.toggle("error", isError);
}

async function loadArticle(url: string): Promise<void> {
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

  const blocks: Node[] = [header];
  let current: HTMLElement | null = null;
  let currentParagraph = -1;
  for (const sentence of article.sentences) {
    if (sentence.paragraph !== currentParagraph) {
      currentParagraph = sentence.paragraph;
      current = document.createElement(
        sentence.kind === "heading" ? "h2" : sentence.kind === "quote" ? "blockquote" : "p",
      );
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
      w.className = "w";
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

function showLoading(word: string): void {
  popupBody.replaceChildren(popupLine("popup-word", word), popupLine("popup-muted", "Looking up…"));
  popupEl.hidden = false;
}

function showError(word: string, message: string, retry: () => void): void {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = "Retry";
  button.addEventListener("click", retry);
  popupBody.replaceChildren(popupLine("popup-word", word), popupLine("popup-error", message), button);
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

function showResult(word: string, sentence: string, r: Lookup): void {
  const meta = [r.lemma !== word ? `→ ${r.lemma}` : "", r.pos, r.register].filter(Boolean).join(" · ");
  popupBody.replaceChildren(
    popupLine("popup-word", word),
    popupLine("popup-muted", meta),
    popupLine("popup-meaning", r.meaning),
    popupLine("popup-gloss", r.gloss),
    popupLine("popup-note", r.note),
    saveButton(word, sentence, r),
  );
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
        if (body.message) reason = body.message;
      } catch {
        // non-JSON error body; keep the status line
      }
      throw new Error(reason);
    }
    const result = (await res.json()) as Lookup;
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

articleEl.addEventListener("click", (ev) => {
  const word = (ev.target as HTMLElement).closest<HTMLElement>(".w");
  if (!word) return;
  const sentence = sentencesById.get(Number(word.closest<HTMLElement>(".s")!.dataset.sid));
  if (!sentence) return;

  selected?.classList.remove("selected");
  selected = word;
  word.classList.add("selected");

  const sentenceText = sentence.tokens.map((t) => t.text + t.ws).join("").trim();
  void lookup(word.textContent ?? "", sentenceText);
});

popupClose.addEventListener("click", clearSelection);
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape") clearSelection();
});

form.addEventListener("submit", (ev) => {
  ev.preventDefault();
  const url = urlInput.value.trim();
  if (!url) return;
  history.replaceState(null, "", `?url=${encodeURIComponent(url)}`);
  void loadArticle(url);
});

void refreshSaved();

const initial = new URLSearchParams(location.search).get("url");
if (initial) {
  urlInput.value = initial;
  void loadArticle(initial);
}
