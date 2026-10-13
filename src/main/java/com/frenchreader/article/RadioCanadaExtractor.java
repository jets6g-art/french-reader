package com.frenchreader.article;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Selectors worked out from the live page, not guessed. On ici.radio-canada.ca/nouvelle/...:
 * <ul>
 *   <li>title: the single {@code h1}</li>
 *   <li>the lede and the body are the two {@code div.flow-root} blocks; the readable content is
 *       the direct children of the {@code div} inside each: {@code p}, {@code h2},
 *       {@code blockquote} (pull-quotes) and {@code div.framed} (sidebar with p/h2)</li>
 *   <li>captions, credits, ads and tracking markers are sibling {@code div}s (or nested deeper),
 *       so reading direct children only skips them without depending on the hashed
 *       {@code sc-*} class names, which change between deploys</li>
 * </ul>
 */
final class RadioCanadaExtractor {

    record Block(String kind, String text) {
    }

    record Extracted(String title, List<Block> blocks) {
    }

    private static final String CONTENT_CONTAINERS = "div.flow-root > div";

    Extracted extract(Document doc) {
        Element h1 = doc.selectFirst("h1");
        String title = h1 != null ? text(h1) : doc.title();

        List<Block> blocks = new ArrayList<>();
        for (Element container : doc.select(CONTENT_CONTAINERS)) {
            for (Element child : container.children()) {
                collect(child, blocks);
            }
        }
        // The article ends with an "À lire et à regarder :" heading over a <ul> of related links,
        // which we don't extract; don't leave the heading dangling.
        while (!blocks.isEmpty() && blocks.get(blocks.size() - 1).kind().equals("heading")) {
            blocks.remove(blocks.size() - 1);
        }
        return new Extracted(title, blocks);
    }

    private void collect(Element el, List<Block> out) {
        switch (el.tagName()) {
            case "p" -> add(out, "p", el);
            case "h2" -> add(out, "heading", el);
            case "blockquote" -> {
                for (Element p : el.children()) {
                    if (p.tagName().equals("p")) {
                        add(out, "quote", p);
                    }
                }
            }
            case "div" -> {
                if (el.hasClass("framed")) {
                    for (Element inner : el.children()) {
                        collect(inner, out);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void add(List<Block> out, String kind, Element el) {
        String t = text(el);
        if (!t.isEmpty()) {
            out.add(new Block(kind, t));
        }
    }

    /**
     * Collapses ASCII whitespace only. Element.text() would also fold NBSP/NNBSP into plain
     * spaces, and French typography depends on them (M.&nbsp;L'Heureux, 550,50&nbsp;$).
     */
    private static String text(Element el) {
        return el.wholeText().replaceAll("[ \\t\\r\\n\\f]+", " ").strip();
    }
}
