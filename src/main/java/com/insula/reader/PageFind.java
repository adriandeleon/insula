package com.insula.reader;

/**
 * Find-in-page, as the script that does it.
 *
 * <p>Kept here as text rather than woven into the renderer because the whole feature is one piece
 * of JavaScript, and having it in one readable place is the difference between a maintainable
 * search and a pile of string concatenation at a call site.
 *
 * <p>It walks the document's text nodes and wraps hits in a marked span, rather than using
 * {@code window.find}: that moves the browser's own selection, leaves nothing highlighted to page
 * between, and gives no count — so "3 of 17" would be impossible and the reader would have no idea
 * whether there was anything further down.
 *
 * <p>Script and editable regions are skipped. Wrapping text inside a {@code <script>} would change
 * what the page executes, and marking inside an input would edit what somebody typed.
 */
public final class PageFind {

    /** The class the marks carry, so the stylesheet and the clear pass can find them. */
    public static final String MARK_CLASS = "insula-find-hit";

    public static final String CURRENT_CLASS = "insula-find-current";

    private PageFind() {}

    /** Installs the helper. Idempotent: reloading a page drops it, so it is re-run per search. */
    public static String installScript() {
        return """
                (function () {
                  if (window.__insulaFind) { return; }
                  var MARK = '%s', CUR = '%s';
                  var hits = [], at = -1;

                  function clear() {
                    document.querySelectorAll('.' + MARK).forEach(function (el) {
                      var text = document.createTextNode(el.textContent);
                      el.parentNode.replaceChild(text, el);
                    });
                    // Splitting a text node to wrap a hit leaves the rest as separate siblings;
                    // without this a second search cannot match across the seam of the first.
                    document.body.normalize();
                    hits = []; at = -1;
                  }

                  function search(query) {
                    clear();
                    if (!query) { return 0; }
                    var needle = query.toLowerCase();
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                      acceptNode: function (node) {
                        if (!node.nodeValue || !node.nodeValue.trim()) { return NodeFilter.FILTER_REJECT; }
                        var p = node.parentNode;
                        while (p && p !== document.body) {
                          var tag = p.nodeName;
                          if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'TEXTAREA' || p.isContentEditable) {
                            return NodeFilter.FILTER_REJECT;
                          }
                          p = p.parentNode;
                        }
                        return node.nodeValue.toLowerCase().indexOf(needle) >= 0
                          ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
                      }
                    });
                    var targets = [];
                    while (walker.nextNode()) { targets.push(walker.currentNode); }
                    targets.forEach(function (node) {
                      var text = node.nodeValue, lower = text.toLowerCase(), from = 0, frag = document.createDocumentFragment();
                      for (var i = lower.indexOf(needle); i >= 0; i = lower.indexOf(needle, from)) {
                        frag.appendChild(document.createTextNode(text.slice(from, i)));
                        var mark = document.createElement('span');
                        mark.className = MARK;
                        mark.textContent = text.slice(i, i + needle.length);
                        frag.appendChild(mark);
                        hits.push(mark);
                        from = i + needle.length;
                      }
                      frag.appendChild(document.createTextNode(text.slice(from)));
                      node.parentNode.replaceChild(frag, node);
                    });
                    return hits.length;
                  }

                  function show(index) {
                    if (!hits.length) { return 0; }
                    if (at >= 0 && hits[at]) { hits[at].classList.remove(CUR); }
                    at = ((index %% hits.length) + hits.length) %% hits.length;
                    hits[at].classList.add(CUR);
                    hits[at].scrollIntoView({ block: 'center' });
                    return at + 1;
                  }

                  window.__insulaFind = {
                    search: search,
                    clear: clear,
                    next: function () { return show(at + 1); },
                    previous: function () { return show(at - 1); },
                    count: function () { return hits.length; }
                  };
                })();
                """.formatted(MARK_CLASS, CURRENT_CLASS);
    }

    /** The highlight styling, injected like any other reader stylesheet. */
    public static String css() {
        return """
               .%s { background: #ffe066 !important; color: #14161a !important; }
               .%s { background: #ff9f1a !important; color: #14161a !important; }
               """.formatted(MARK_CLASS, CURRENT_CLASS);
    }

    /** A JavaScript string literal for arbitrary user text. */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                // U+2028 and U+2029 terminate a JavaScript string literal even though
                // Java sees one ordinary character. Written numerically because a
                // \\u escape in the source is processed by the Java lexer itself and
                // would put a real line separator in this file.
                case 0x2028 -> out.append("\\u2028");
                case 0x2029 -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
