package com.insula.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses a Kiwix OPDS v2 acquisition feed (Atom) into {@link ZimEntry} records.
 *
 * <p>XXE-hardened: the feed is remote input, so DOCTYPE and external entities are disallowed.
 * An entry without a usable acquisition link is skipped rather than failing the whole feed —
 * one malformed entry must not cost the user the other 199.
 */
public final class OpdsCatalogParser {

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String ACQUISITION_REL = "http://opds-spec.org/acquisition/open-access";
    private static final String ILLUSTRATION_REL = "http://opds-spec.org/image/thumbnail";

    private OpdsCatalogParser() {}

    /** The feed's {@code totalResults}, or {@code -1} when absent — drives paging. */
    public static long totalResults(Element feed) {
        NodeList nodes = feed.getElementsByTagNameNS("*", "totalResults");
        if (nodes.getLength() == 0) {
            return -1;
        }
        return parseLong(nodes.item(0).getTextContent(), -1);
    }

    public static Feed parse(InputStream in) throws IOException {
        Element feed = documentElement(in);
        List<ZimEntry> entries = new ArrayList<>();
        NodeList entryNodes = feed.getElementsByTagNameNS(ATOM, "entry");
        for (int i = 0; i < entryNodes.getLength(); i++) {
            ZimEntry entry = toEntry((Element) entryNodes.item(i));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new Feed(List.copyOf(entries), totalResults(feed));
    }

    /** A parsed page of the catalog. */
    public record Feed(List<ZimEntry> entries, long totalResults) {}

    private static Element documentElement(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(
                    (publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            // Report parse failures by throwing, not by printing to stderr (the JDK default).
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return builder.parse(in).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Malformed OPDS feed: " + e.getMessage(), e);
        }
    }

    private static ZimEntry toEntry(Element entry) {
        Element acquisition = acquisitionLink(entry);
        if (acquisition == null) {
            return null;
        }
        String href = acquisition.getAttribute("href");
        if (href.isBlank()) {
            return null;
        }
        return new ZimEntry(
                child(entry, "id"),
                child(entry, "title"),
                child(entry, "summary"),
                child(entry, "name"),
                child(entry, "flavour"),
                splitLanguages(child(entry, "language")),
                child(entry, "category"),
                parseLong(child(entry, "articleCount"), 0),
                parseLong(child(entry, "mediaCount"), 0),
                href,
                parseLong(acquisition.getAttribute("length"), 0),
                child(entry, "updated"),
                linkHref(entry, ILLUSTRATION_REL));
    }

    /** The href of the first link with the given rel, or {@code ""}. */
    private static String linkHref(Element entry, String rel) {
        NodeList links = entry.getElementsByTagNameNS(ATOM, "link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            if (rel.equals(link.getAttribute("rel"))) {
                return link.getAttribute("href");
            }
        }
        return "";
    }

    private static Element acquisitionLink(Element entry) {
        NodeList links = entry.getElementsByTagNameNS(ATOM, "link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            if (ACQUISITION_REL.equals(link.getAttribute("rel"))) {
                return link;
            }
        }
        return null;
    }

    /** Direct-child text, so a nested {@code <author><name>} never shadows the entry's own field. */
    private static String child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                String text = n.getTextContent();
                return text == null ? "" : text.strip();
            }
        }
        return "";
    }

    /** "eng,spa,ara" → [eng, spa, ara], de-duplicated (the catalog repeats codes). */
    private static List<String> splitLanguages(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
