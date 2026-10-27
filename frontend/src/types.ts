/** A lookup answer. The first six fields describe the clicked word itself. */
export interface Lookup {
  lemma: string;
  pos: string;
  meaning: string;
  register: string;
  gloss: string;
  note: string;
}

/**
 * What POST /lookup returns: the word, plus the idiom it belongs to in this sentence, if any.
 * The idiom fields are empty when there is none, and null in answers cached before they existed.
 */
export interface LookupResult extends Lookup {
  idiom?: string | null;
  idiomMeaning?: string | null;
  idiomGloss?: string | null;
  idiomNote?: string | null;
}

/** A saved word or expression: the lookup answer plus where it was met. */
export interface SavedItem extends Lookup {
  id: string;
  word: string;
  sentence: string;
  articleUrl: string;
  savedAt: string;
}
