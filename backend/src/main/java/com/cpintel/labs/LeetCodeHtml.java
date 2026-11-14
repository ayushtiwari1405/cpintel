package com.cpintel.labs;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * LeetCode's statement HTML, cut down to what a statement needs before it reaches a page.
 *
 * <p>It comes from a third party and is shown inside CPIntel, so nothing that can run survives:
 * no scripts, no event handlers, no styles, no links other than http(s). The page sanitises it
 * again before rendering; this is the copy every client gets.
 */
final class LeetCodeHtml {

    private static final Safelist ALLOWED = Safelist.relaxed()
        .addTags("sup", "sub", "font", "hr", "del", "s")
        .removeProtocols("img", "src", "http")
        .addAttributes("pre", "class")
        .addAttributes("code", "class");

    private LeetCodeHtml() {}

    static String clean(String html) {
        if (html == null || html.isBlank()) return null;
        return Jsoup.clean(html, "https://leetcode.com/", ALLOWED, new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
    }
}
