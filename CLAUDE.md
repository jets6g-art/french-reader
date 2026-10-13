# French Reading Aid

A web app tool for reading French news articles (primarily ici.radio-canada.ca)
with click-to-explain word lookup or if it is part of idiomatic expression,
explain the idiom.

## What it is

Not a browser. The server fetches an article, extracts the readable content,
tokenizes it, and serves it as JSON. The frontend renders the tokens and handles
clicks. Clicking a word (or drag-selecting a phrase) sends the word plus its
containing sentence to the backend, which asks the Claude API to explain it *in
that context* and returns structured JSON for a popup.

Iframing the source site does not work — Radio-Canada sends X-Frame-Options /
CSP frame-ancestors, and cross-origin frames can't be instrumented anyway.

## Stack

Backend: Java, Spring Boot 3, jsoup, embedded Tomcat (fat JAR, `java -jar`).
Frontend: static TypeScript, built to plain files, served by Spring Boot from
`src/main/resources/static/`.

Explicitly not used:
- Struts 2 — nothing server-rendered here; `@RestController` covers it
- JSP — broken under embedded Tomcat in an executable JAR
- Any LLM orchestration framework — this is one plain HTTP call, not an agent

## API

Versioned from the start. Two endpoints.

    GET  /api/v1/article?url=<encoded>
      → { title, source, url, sentences: [ { id, tokens: [ { idx, text, ws } ] } ] }

    POST /api/v1/lookup
      body: { word, sentence, articleUrl }
      → { lemma, pos, meaning, register, gloss, note }

Stateless — no session. Every request carries what it needs, so a browser, the
iPad, and any future native client are interchangeable, and restarting the
server mid-article breaks nothing.

GET for article (cacheable, idempotent). POST for lookup — the sentence is too
long for a query string and shouldn't land in access logs.

## Backend responsibilities

1. **Fetch** — jsoup with a real User-Agent, 5s timeout. Respect robots.txt.
   Low volume, personal use.
2. **Extract** — site-specific CSS selectors for Radio-Canada first. Only reach
   for a generic readability algorithm if a second site gets added. Tuned
   selectors beat generic extraction on a known site.
3. **Tokenize** — French-aware. Must handle elision and contractions:
   `l'entreprise`, `qu'il`, `d'ailleurs`, `du`, `aux`. Preserve sentence
   boundaries and trailing whitespace so the frontend can reconstruct the text
   exactly.
4. **Lookup proxy** — holds the API key, builds the prompt, calls Claude,
   parses JSON out, caches.

## LLM call

Single-shot. Low temperature. Small max_tokens — the user is staring at a
spinner. Ask for JSON with: lemma, part of speech, meaning in this context,
register, English gloss, and a short grammatical note (e.g. "conditional
present, 3rd person plural") — the grammar note matters as much as the
definition for oral-test prep.

Let the model do the lemmatization. A bundled lexicon (Lefff) or CoreNLP is a
later optimization if latency or cost becomes a problem — don't start there.

## Caching

Non-negotiable, it's what makes the thing feel fast.

- Lookups keyed on `lemma + surface form`, NOT the full sentence — otherwise the
  hit rate is near zero. Words like *ainsi*, *néanmoins*, *toutefois* recur
  constantly.
- Extracted+tokenized articles cached by URL, so re-reading works even if the
  source or the API is down.

## Timeouts

5s on the article fetch, 10s on the LLM call, with a clear error in the popup.
A hung upstream must not leave the UI spinning. Article load and lookup happen
at different times, so LLM latency never blocks reading.

## Hosting

Runs on a local machine (Mac mini or Windows PC). Plain HTTP on 8080 is fine
for development.

Outbound (article fetch, Anthropic API) is ordinary HTTPS — no firewall config,
no port forwarding.

If the host is a Mac mini: disable sleep, or the iPad gets connection refused.

Frontend should be responsive and ship a PWA manifest — that gives a home-screen
icon and fullscreen on iPad, which may be all the "porting" ever needed.

## Open questions

- Is Radio-Canada's article body server-rendered? Check before assuming:
  `curl -s <article-url> | grep -c "<p"`. A healthy paragraph count means jsoup
  is sufficient. Near zero means headless rendering (Playwright has Java
  bindings).

## Build order

1. `GET /api/v1/article` returning real tokenized JSON for one Radio-Canada URL
2. Minimal frontend that renders it readably
3. `POST /api/v1/lookup` with a hardcoded response, wire up the popup
4. Real Claude API call
5. Caching
6. Drag-select for multi-word expressions (`mettre en œuvre`, `à même de`,
   `ne … pas`) — this is the feature that makes the tool genuinely useful
7. Saved word or idom list.
