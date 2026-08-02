module com.insula {
    requires javafx.controls;
    requires javafx.web;
    requires atlantafx.base;
    requires java.desktop;
    requires jdk.httpserver;
    requires io.nayuki.qrcodegen;
    // Pure-Java WebP reader; found via the ImageIO service loader, so it must be resolved.
    requires com.twelvemonkeys.imageio.webp;
    requires java.logging;
    requires java.net.http;
    requires java.xml;
    requires org.tukaani.xz;
    requires com.github.luben.zstd_jni;
    // Automatic module derived from the jar name; jlibtorrent declares no Automatic-Module-Name.
    requires jlibtorrent;

    exports com.insula.app to
            javafx.graphics;
}
