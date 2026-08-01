package com.insula.zim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only facade over one ZIM archive: path lookup (binary search on the path pointer list),
 * title prefix search (title pointer list, or the {@code X/listing/titleOrdered/v1} front-article
 * listing when present), redirect resolution, blob access, and metadata.
 *
 * <p>All reads are positional and thread-safe; instances may be shared between the UI and the
 * HTTP server threads.
 */
public final class ZimArchive implements AutoCloseable {

    private static final int MAX_REDIRECT_DEPTH = 16;
    private static final int TITLE_LISTING_MAX = 64 * 1024 * 1024;

    private final Path file;
    private final LittleEndianFile in;
    private final ZimHeader header;
    private final MimeList mimeList;
    private final ClusterStore clusters;

    /** Front-article title listing (u32 entry indices, title-sorted); null = use the header list. */
    private volatile byte[] titleListingV1;

    private volatile boolean titleListingResolved;

    private ZimArchive(Path file, LittleEndianFile in, ZimHeader header, MimeList mimeList) {
        this.file = file;
        this.in = in;
        this.header = header;
        this.mimeList = mimeList;
        this.clusters = new ClusterStore(in, header);
    }

    public static ZimArchive open(Path file) throws IOException {
        LittleEndianFile in = new LittleEndianFile(file);
        try {
            ZimHeader header = ZimHeader.parse(in);
            MimeList mimeList = MimeList.parse(in, header.mimeListPos());
            return new ZimArchive(file, in, header, mimeList);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    public Path file() {
        return file;
    }

    public ZimHeader header() {
        return header;
    }

    public long entryCount() {
        return header.entryCount();
    }

    /** The namespace article content lives in: {@code C} (new scheme) or {@code A} (old scheme). */
    public char contentNamespace() {
        return header.newNamespaceScheme() ? 'C' : 'A';
    }

    // ---------------------------------------------------------------- dirents

    public Dirent direntAt(long index) throws IOException {
        if (index < 0 || index >= header.entryCount()) {
            throw new ZimFormatException("Entry index " + index + " out of range");
        }
        long direntOffset = in.u64(header.pathPtrPos() + 8 * index);
        return Dirent.parse(in, index, direntOffset);
    }

    /** Follows a redirect chain (bounded) to the content entry. */
    public Dirent resolve(Dirent dirent) throws IOException {
        Dirent d = dirent;
        for (int depth = 0; d.isRedirect() && depth < MAX_REDIRECT_DEPTH; depth++) {
            d = direntAt(d.redirectIndex());
        }
        if (d.isRedirect()) {
            throw new ZimFormatException("Redirect chain too deep at " + dirent.fullPath());
        }
        return d;
    }

    // ---------------------------------------------------------------- lookup

    /** Binary search on the path pointer list (sorted by namespace byte, then path bytes). */
    public Optional<Dirent> entryByPath(char namespace, String path) throws IOException {
        byte[] wanted = pathKey(namespace, path);
        long lo = 0;
        long hi = header.entryCount() - 1;
        while (lo <= hi) {
            long mid = (lo + hi) >>> 1;
            Dirent d = direntAt(mid);
            int cmp = Arrays.compareUnsigned(pathKey(d.namespace(), d.path()), wanted);
            if (cmp == 0) {
                return Optional.of(d);
            }
            if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Optional.empty();
    }

    /** Looks up {@code "N/rest/of/path"}. */
    public Optional<Dirent> entryByUrl(String fullPath) throws IOException {
        if (fullPath.length() < 2 || fullPath.charAt(1) != '/') {
            return Optional.empty();
        }
        return entryByPath(fullPath.charAt(0), fullPath.substring(2));
    }

    private static byte[] pathKey(char namespace, String path) {
        byte[] p = path.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[p.length + 1];
        key[0] = (byte) namespace;
        System.arraycopy(p, 0, key, 1, p.length);
        return key;
    }

    // ---------------------------------------------------------------- content

    public byte[] content(Dirent dirent) throws IOException {
        Dirent d = resolve(dirent);
        if (!d.hasContent()) {
            throw new ZimFormatException("Entry has no content: " + d.fullPath());
        }
        return clusters.blob(d.clusterNumber(), d.blobNumber());
    }

    public String mimeType(Dirent dirent) {
        return mimeList.byIndex(dirent.mimeType());
    }

    public Optional<Dirent> mainPage() throws IOException {
        if (header.hasMainPage()) {
            return Optional.of(resolve(direntAt(header.mainPage())));
        }
        Optional<Dirent> wellKnown = entryByPath('W', "mainPage");
        if (wellKnown.isPresent()) {
            return Optional.of(resolve(wellKnown.get()));
        }
        return Optional.empty();
    }

    /** {@code M}-namespace metadata (Title, Description, Language, ...), UTF-8. */
    public Optional<String> metadata(String name) throws IOException {
        Optional<Dirent> entry = entryByPath('M', name);
        if (entry.isEmpty() || !resolveQuietly(entry.get()).hasContent()) {
            return Optional.empty();
        }
        return Optional.of(new String(content(entry.get()), StandardCharsets.UTF_8));
    }

    private Dirent resolveQuietly(Dirent d) throws IOException {
        return d.isRedirect() ? resolve(d) : d;
    }

    // ---------------------------------------------------------------- search

    public record SearchResult(long entryIndex, String title, String fullPath) {}

    /**
     * Title prefix search. Tries the query as typed plus a first-letter-capitalized variant
     * (titles are byte-ordered and case-sensitive), merging by entry index.
     */
    public List<SearchResult> searchByTitle(String query, int limit) throws IOException {
        // Keyed by resolved path so a redirect and its target (same title) collapse to one row.
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (String variant : queryVariants(query)) {
            for (SearchResult r : searchExactPrefix(variant, limit)) {
                merged.putIfAbsent(r.fullPath(), r);
            }
            if (merged.size() >= limit) {
                break;
            }
        }
        return merged.values().stream().limit(limit).toList();
    }

    private static List<String> queryVariants(String query) {
        List<String> variants = new ArrayList<>(2);
        variants.add(query);
        if (!query.isEmpty()) {
            String capitalized = query.substring(0, 1).toUpperCase(Locale.ROOT) + query.substring(1);
            if (!capitalized.equals(query)) {
                variants.add(capitalized);
            }
        }
        return variants;
    }

    private List<SearchResult> searchExactPrefix(String prefix, int limit) throws IOException {
        byte[] listing = frontArticleListing();
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] wanted;
        TitleIndex index;
        if (listing != null) {
            index = i -> u32(listing, i);
            wanted = prefixBytes;
        } else {
            index = i -> in.u32(header.titlePtrPos() + 4L * i);
            wanted = pathKey(contentNamespace(), prefix); // (ns, title) ordering
        }
        int size = listing != null ? listing.length / 4 : Math.toIntExact(header.entryCount());

        int lo = lowerBound(index, size, wanted, listing != null);
        List<SearchResult> results = new ArrayList<>();
        for (int i = lo; i < size && results.size() < limit; i++) {
            Dirent d = direntAt(index.entryIndexAt(i));
            byte[] key = titleKey(d, listing != null);
            if (!startsWith(key, wanted)) {
                break;
            }
            results.add(new SearchResult(d.index(), d.title(), resolve(d).fullPath()));
        }
        return results;
    }

    private interface TitleIndex {
        long entryIndexAt(int i) throws IOException;
    }

    private int lowerBound(TitleIndex index, int size, byte[] wanted, boolean bareTitle) throws IOException {
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            byte[] key = titleKey(direntAt(index.entryIndexAt(mid)), bareTitle);
            if (Arrays.compareUnsigned(key, wanted) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static byte[] titleKey(Dirent d, boolean bareTitle) {
        byte[] title = d.title().getBytes(StandardCharsets.UTF_8);
        if (bareTitle) {
            return title;
        }
        byte[] key = new byte[title.length + 1];
        key[0] = (byte) d.namespace();
        System.arraycopy(title, 0, key, 1, title.length);
        return key;
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }
        int mismatch = Arrays.mismatch(key, prefix);
        return mismatch == -1 || mismatch >= prefix.length;
    }

    private static long u32(byte[] data, int index) {
        return java.nio.ByteBuffer.wrap(data)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .getInt(index * 4)
                & 0xFFFFFFFFL;
    }

    /** The {@code X/listing/titleOrdered/v1} blob (front articles, title-sorted), if present. */
    private byte[] frontArticleListing() throws IOException {
        if (!titleListingResolved) {
            synchronized (this) {
                if (!titleListingResolved) {
                    byte[] loaded = null;
                    Optional<Dirent> listing = entryByPath('X', "listing/titleOrdered/v1");
                    if (listing.isPresent() && resolveQuietly(listing.get()).hasContent()) {
                        byte[] bytes = content(listing.get());
                        if (bytes.length <= TITLE_LISTING_MAX && bytes.length % 4 == 0) {
                            loaded = bytes;
                        }
                    }
                    titleListingV1 = loaded;
                    titleListingResolved = true;
                }
            }
        }
        return titleListingV1;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
