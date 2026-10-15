export interface Lookup {
  lemma: string;
  pos: string;
  meaning: string;
  register: string;
  gloss: string;
  note: string;
}

/** A saved word or expression: the lookup answer plus where it was met. */
export interface SavedItem extends Lookup {
  id: string;
  word: string;
  sentence: string;
  articleUrl: string;
  savedAt: string;
}
