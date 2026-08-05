module com.insula {
    requires javafx.controls;
    requires javafx.media;
    requires javafx.web;
    requires atlantafx.base;
    requires java.desktop;
    requires jdk.httpserver;
    requires jdk.jsobject; // netscape.javascript.JSObject, for the media bridge
    requires io.nayuki.qrcodegen;
    // Full-text search. Real JPMS modules, so no moditect entry is needed for the dist build.
    requires org.apache.lucene.core;
    requires org.apache.lucene.analysis.common;
    // Pure-Java WebP reader; found via the ImageIO service loader, so it must be resolved.
    requires com.twelvemonkeys.imageio.webp;
    requires java.logging;
    requires java.net.http;
    requires java.xml;
    // JSON, for GitHub's releases API alone. The streaming parser only — nothing here binds POJOs,
    // so there is no databind, and so no reflective access needing a package opened to it.
    requires com.fasterxml.jackson.core;
    requires org.tukaani.xz;
    requires com.github.luben.zstd_jni;
    // Optional, and static on purpose. jlibtorrent is an automatic module whose native library
    // lives in a *separate* jar with a name that is not a legal module name, so a linked image
    // cannot carry it — and JPMS resource encapsulation would hide the .so from it anyway.
    // BitTorrent is opt-in and off by default, HTTP is the guaranteed path, and TorrentTransport
    // already reports itself unavailable when the classes are missing.
    requires static jlibtorrent;

    exports com.insula.app to
            javafx.graphics;

    // JSObject.setMember dispatches reflectively into MediaBridge, the one JS->Java surface.
    opens com.insula.reader to
            javafx.web;
}
