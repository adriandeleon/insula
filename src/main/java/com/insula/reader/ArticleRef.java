package com.insula.reader;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A pointer to one article, complete enough to reopen and to display without opening anything.
 *
 * <p>Bookmarks and history both outlive the session that made them, so a reference has to survive
 * the archive being closed: it carries the archive's file and its title alongside the article's,
 * because a bookmarks list that cannot say which book an entry came from — or that has to open a
 * 20 GB archive to find out — is not usable.
 *
 * @param archiveFile the {@code .zim} the article lives in
 * @param articlePath the in-archive path, namespace included ({@code C/Walt_Disney})
 * @param title the article's own title, or its path when the archive gave none
 * @param bookTitle the archive's title, for display
 */
public record ArticleRef(Path archiveFile, String articlePath, String title, String bookTitle) {

    private static final String SEPARATOR = "|";

    public ArticleRef {
        title = title == null || title.isBlank() ? articlePath : title;
        bookTitle = bookTitle == null ? "" : bookTitle;
    }

    /** Identity for de-duplication: the same article in the same archive, regardless of title. */
    public String key() {
        return archiveFile.toAbsolutePath() + SEPARATOR + articlePath;
    }

    /**
     * A single line for a properties file. Every field is percent-encoded, so a path containing
     * the separator — or a newline, or an equals sign — round-trips rather than corrupting the
     * store.
     */
    public String encode() {
        return escape(archiveFile.toString())
                + SEPARATOR
                + escape(articlePath)
                + SEPARATOR
                + escape(title)
                + SEPARATOR
                + escape(bookTitle);
    }

    /** The inverse of {@link #encode}; null for anything malformed, which is simply skipped. */
    public static ArticleRef decode(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split("\\" + SEPARATOR, -1);
        if (parts.length < 3) {
            return null;
        }
        try {
            Path archive = Path.of(unescape(parts[0]));
            String path = unescape(parts[1]);
            if (path.isBlank()) {
                return null;
            }
            return new ArticleRef(archive, path, unescape(parts[2]), parts.length > 3 ? unescape(parts[3]) : "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String escape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
