package com.insula.download;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses a MirrorBrain {@code .meta4} (IETF Metalink, RFC 5854). XXE-hardened like the OPDS
 * parser — this is remote input.
 *
 * <p>Only {@code http}/{@code https} mirrors are kept: the sidecars also advertise {@code ftp} and
 * {@code rsync}, which {@code java.net.http} cannot fetch, and a transport that picked one would
 * simply fail. Mirrors are ordered by descending {@code priority} (Metalink's lower number = more
 * preferred), which is MirrorBrain's geographic ranking.
 */
public final class MetalinkParser {

    private MetalinkParser() {}

    public static Metalink parse(InputStream in) throws IOException {
        Element root = documentElement(in);
        Element file = firstChildNamed(root, "file");
        if (file == null) {
            throw new IOException("Metalink has no <file> element");
        }

        String name = file.getAttribute("name");
        long size = parseLong(textOf(file, "size"), 0);
        String sha256 = "";
        String md5 = "";
        for (Element hash : childrenNamed(file, "hash")) {
            String type = hash.getAttribute("type").toLowerCase(Locale.ROOT);
            String value = hash.getTextContent().strip().toLowerCase(Locale.ROOT);
            if ("sha-256".equals(type) || "sha256".equals(type)) {
                sha256 = value;
            } else if ("md5".equals(type)) {
                md5 = value;
            }
        }

        record Mirror(String url, int priority) {}
        List<Mirror> mirrors = new ArrayList<>();
        for (Element url : childrenNamed(file, "url")) {
            String value = url.getTextContent().strip();
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                mirrors.add(new Mirror(value, (int) parseLong(url.getAttribute("priority"), Integer.MAX_VALUE)));
            }
        }
        mirrors.sort(Comparator.comparingInt(Mirror::priority));

        long pieceLength = 0;
        String pieceType = "";
        List<String> pieceHashes = new ArrayList<>();
        Element pieces = firstChildNamed(file, "pieces");
        if (pieces != null) {
            pieceLength = parseLong(pieces.getAttribute("length"), 0);
            pieceType = pieces.getAttribute("type").toLowerCase(Locale.ROOT);
            for (Element hash : childrenNamed(pieces, "hash")) {
                pieceHashes.add(hash.getTextContent().strip().toLowerCase(Locale.ROOT));
            }
        }

        return new Metalink(
                name,
                size,
                sha256,
                md5,
                mirrors.stream().map(Mirror::url).toList(),
                pieceLength,
                pieceType,
                pieceHashes);
    }

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
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return builder.parse(in).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Malformed Metalink: " + e.getMessage(), e);
        }
    }

    private static Element firstChildNamed(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> childrenNamed(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String textOf(Element parent, String localName) {
        Element child = firstChildNamed(parent, localName);
        return child == null ? "" : child.getTextContent().strip();
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
