package com.frenchreader.article;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.frenchreader.article.RadioCanadaExtractor.Block;
import com.frenchreader.article.RadioCanadaExtractor.Extracted;

/**
 * For sites without their own selectors: a small readability-style heuristic. Each substantial
 * paragraph adds its length to its parent's score and half of it to its grandparent's (bodies
 * often wrap paragraphs in a div each); the top scorer is taken as the article body, and its
 * paragraphs, headings and quotes are read in document order.
 */
final class GenericExtractor {

    /** Page chrome that is never article text. */
    private static final String NOISE = "script, style, noscript, template, nav, header, footer, aside, "
            + "form, figure, iframe, button, [hidden], [aria-hidden=true]";
    /** Shorter paragraphs (bylines, "Partager", captions) don't vote for where the body is. */
    private static final int MIN_SCORING_LENGTH = 25;

    Extracted extract(Document page) {
        Document doc = page.clone(); // removing noise must not touch the caller's document
        String title = title(doc);
        doc.select(NOISE).remove();

        List<Block> blocks = new ArrayList<>();
        Element body = bestContainer(doc);
        if (body != null) {
            for (Element el : body.select("p, h2, h3, h4, blockquote")) {
                boolean inQuote = el.parents().stream().anyMatch(p -> p.tagName().equals("blockquote"));
                switch (el.tagName()) {
                    case "p" -> {
                        if (!inQuote) {
                            add(blocks, "p", el);
                        }
                    }
                    case "blockquote" -> {
                        if (!inQuote) {
                            add(blocks, "quote", el);
                        }
                    }
                    default -> add(blocks, "heading", el);
                }
            }
        }
        while (!blocks.isEmpty() && blocks.get(blocks.size() - 1).kind().equals("heading")) {
            blocks.remove(blocks.size() - 1);
        }
        return new Extracted(title, blocks);
    }

    private static String title(Document doc) {
        // The site's logo is often an h1 in the page header; the story's own h1 is in article/main.
        Element h1 = doc.selectFirst("article h1, main h1");
        if (h1 == null) {
            h1 = doc.selectFirst("h1");
        }
        if (h1 != null && !RadioCanadaExtractor.text(h1).isEmpty()) {
            return RadioCanadaExtractor.text(h1);
        }
        Element og = doc.selectFirst("meta[property=og:title]");
        if (og != null && !og.attr("content").isBlank()) {
            return og.attr("content").strip();
        }
        return doc.title().strip();
    }

    private static Element bestContainer(Document doc) {
        Map<Element, Integer> scores = new IdentityHashMap<>();
        for (Element p : doc.select("p")) {
            int length = RadioCanadaExtractor.text(p).length();
            Element parent = p.parent();
            if (length < MIN_SCORING_LENGTH || parent == null) {
                continue;
            }
            scores.merge(parent, length, Integer::sum);
            if (parent.parent() != null) {
                scores.merge(parent.parent(), length / 2, Integer::sum);
            }
        }
        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static void add(List<Block> out, String kind, Element el) {
        String t = RadioCanadaExtractor.text(el);
        if (!t.isEmpty()) {
            out.add(new Block(kind, t));
        }
    }
}
