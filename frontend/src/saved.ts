import { deleteSaved, listSaved } from "./api.js";
import type { SavedItem } from "./types.js";

const heading = document.getElementById("heading") as HTMLElement;
const statusEl = document.getElementById("status") as HTMLElement;
const listEl = document.getElementById("list") as HTMLElement;

function setStatus(msg: string, isError = false): void {
  statusEl.textContent = msg;
  statusEl.hidden = msg === "";
  statusEl.classList.toggle("error", isError);
}

function line(tag: string, className: string, text: string): HTMLElement {
  const el = document.createElement(tag);
  el.className = className;
  el.textContent = text;
  return el;
}

// Everything is built with textContent, and the article link is a relative app URL with the
// saved address encoded into it, so stored text is never parsed as HTML or used as a raw href.
function renderItem(item: SavedItem, onRemove: () => void): HTMLElement {
  const card = document.createElement("article");
  card.className = "saved-item";

  const meta = [item.pos, item.register].filter(Boolean).join(" · ");
  const head = line("p", "saved-lemma", item.lemma);
  if (meta) head.append(" ", line("span", "popup-muted", meta));
  card.append(head);

  if (item.gloss) card.append(line("p", "popup-gloss", item.gloss));
  card.append(line("p", "popup-meaning", item.meaning));
  if (item.note) card.append(line("p", "popup-note", item.note));
  if (item.sentence) card.append(line("blockquote", "saved-sentence", item.sentence));

  const foot = document.createElement("p");
  foot.className = "saved-foot";
  foot.append(new Date(item.savedAt).toLocaleDateString());
  if (item.articleUrl) {
    const a = document.createElement("a");
    a.href = `./?url=${encodeURIComponent(item.articleUrl)}`;
    a.textContent = "Open article";
    foot.append(" · ", a);
  }
  const remove = document.createElement("button");
  remove.type = "button";
  remove.className = "saved-remove";
  remove.textContent = "Remove";
  remove.addEventListener("click", onRemove);
  foot.append(remove);
  card.append(foot);
  return card;
}

function render(items: SavedItem[]): void {
  heading.textContent = items.length ? `Saved (${items.length})` : "Saved";
  if (items.length === 0) {
    listEl.replaceChildren(
      line("p", "popup-muted", "Nothing saved yet. Click a word while reading, then tap Save."),
    );
    return;
  }
  listEl.replaceChildren(
    ...items.map((item) =>
      renderItem(item, async () => {
        if (!confirm(`Remove “${item.lemma}” from your saved list?`)) return;
        try {
          await deleteSaved(item.id);
          await load();
        } catch (e) {
          setStatus(`Could not remove: ${(e as Error).message}`, true);
        }
      }),
    ),
  );
}

async function load(): Promise<void> {
  try {
    render(await listSaved());
    setStatus("");
  } catch (e) {
    setStatus(`Could not load the saved list: ${(e as Error).message}`, true);
  }
}

setStatus("Loading…");
void load();
