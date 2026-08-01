module com.insula {
    requires javafx.controls;
    requires javafx.web;
    requires atlantafx.base;
    requires jdk.httpserver;
    requires java.logging;
    requires java.net.http;
    requires java.xml;
    requires org.tukaani.xz;
    requires com.github.luben.zstd_jni;

    exports com.insula.app to
            javafx.graphics;
}
