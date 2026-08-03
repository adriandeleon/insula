package com.insula.fulltext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * A searchable index of one archive's article text.
 *
 * <p>Built rather than read: a ZIM carries a Xapian index in its {@code X} namespace, but Xapian is
 * C++ and binding to it would put this project back where jlibtorrent has it — a native library
 * that crashes the whole app and cannot be reasoned about from Java. Re-indexing costs a pass over
 * the archive once, and buys a search that is entirely ours.
 *
 * <p>Titles are indexed alongside the body and weighted above it, because someone typing two words
 * usually means the article about them rather than an article that mentions them.
 */
public final class FullTextIndex implements AutoCloseable {

    /** Bumped when the fields or the analysis change, so a stale index is rebuilt rather than read. */
    public static final int FORMAT_VERSION = 1;

    private static final String PATH = "path";
    private static final String TITLE = "title";
    private static final String BODY = "body";

    /** How much more a title match is worth than a body match. */
    private static final float TITLE_BOOST = 4f;

    /** Enough for any real search; a pasted paragraph is not a query. */
    private static final int MAX_TERMS = 32;

    /** One hit: enough to open the article and to show why it matched. */
    public record Hit(String path, String title, float score) {}

    private final Directory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;

    private FullTextIndex(Directory directory, DirectoryReader reader) {
        this.directory = directory;
        this.reader = reader;
        this.searcher = new IndexSearcher(reader);
    }

    /** Opens an index for searching, or throws when there is nothing usable at that path. */
    public static FullTextIndex open(Path indexDir) throws IOException {
        Directory directory = FSDirectory.open(indexDir);
        try {
            return new FullTextIndex(directory, DirectoryReader.open(directory));
        } catch (IOException e) {
            directory.close();
            throw e;
        }
    }

    /** A writer for building one. The caller feeds it and closes it. */
    public static Builder builder(Path indexDir) throws IOException {
        return new Builder(indexDir);
    }

    /** How many articles are in the index. */
    public int size() {
        return reader.numDocs();
    }

    /**
     * The best matches for a query.
     *
     * <p>A query that will not parse is treated as the words it contains rather than refused:
     * somebody typing {@code C++ (programming)} into a search box means those words, not a syntax
     * error, and an error message where results should be is the least useful possible answer.
     */
    public List<Hit> search(String query, int limit) throws IOException {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Query parsed = parse(query);
        ScoreDoc[] docs = searcher.search(parsed, limit).scoreDocs;
        List<Hit> hits = new ArrayList<>(docs.length);
        StoredFields stored = searcher.storedFields();
        for (ScoreDoc doc : docs) {
            Document d = stored.document(doc.doc);
            hits.add(new Hit(d.get(PATH), d.get(TITLE), doc.score));
        }
        return List.copyOf(hits);
    }

    /**
     * Builds the query from the words someone typed, with no query syntax at all.
     *
     * <p>Lucene's {@code QueryParser} is deliberately not used. A reader's search box is used the
     * way a web search box is used — words, not expressions — and every piece of syntax it
     * understands is a way for ordinary text to behave surprisingly: {@code C++ (programming} is an
     * unbalanced bracket, {@code either/or} is a regex, and a query ending in a half-typed
     * {@code AND} is a parse error. Escaping is not enough to fix that last one, because the bare
     * boolean keywords are words, not punctuation, and survive it.
     *
     * <p>So the query is built from analysed tokens directly: whatever the analyzer makes of the
     * text becomes terms, and terms are all it can ever be. Predictable in exchange for the
     * handful of people who would have used {@code +term -term}.
     */
    private Query parse(String query) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return new BooleanQuery.Builder().build();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String term : terms) {
            builder.add(new TermQuery(new Term(BODY, term)), BooleanClause.Occur.SHOULD);
            builder.add(new BoostQuery(new TermQuery(new Term(TITLE, term)), TITLE_BOOST), BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    /** The query text as the same tokens the index was built from. */
    private static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        try (StandardAnalyzer analyzer = new StandardAnalyzer();
                TokenStream stream = analyzer.tokenStream(BODY, query)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken() && terms.size() < MAX_TERMS) {
                terms.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            // Analysing an in-memory string cannot really fail; a query of no terms finds nothing,
            // which is a better answer than an exception thrown at somebody who was only typing.
            return List.of();
        }
        return terms;
    }

    @Override
    public void close() throws IOException {
        try {
            reader.close();
        } finally {
            directory.close();
        }
    }

    /** Writes an index. Not thread-safe: one indexing pass owns one builder. */
    public static final class Builder implements AutoCloseable {

        private final Directory directory;
        private final IndexWriter writer;

        private Builder(Path indexDir) throws IOException {
            this.directory = FSDirectory.open(indexDir);
            IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            // Always from scratch. An index half-built by a run that was cancelled or crashed is
            // worse than none: it answers, and it answers incompletely.
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            this.writer = new IndexWriter(directory, config);
        }

        /** Adds one article. Its path is stored so a hit can be opened. */
        public void add(String path, String title, String text) throws IOException {
            Document doc = new Document();
            doc.add(new StringField(PATH, path, Field.Store.YES));
            doc.add(new TextField(TITLE, title == null ? "" : title, Field.Store.YES));
            doc.add(new TextField(BODY, text == null ? "" : text, Field.Store.NO));
            // The body is indexed but not stored: it is the archive's job to hold the article, and
            // storing it again would roughly double what the index costs on disk.
            doc.add(new StoredField("v", FORMAT_VERSION));
            writer.addDocument(doc);
        }

        @Override
        public void close() throws IOException {
            try {
                writer.close();
            } finally {
                directory.close();
            }
        }
    }
}
