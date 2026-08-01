module com.offlinewiki {
    requires javafx.controls;
    requires javafx.web;
    requires atlantafx.base;
    requires jdk.httpserver;
    requires java.xml;
    requires org.tukaani.xz;
    requires com.github.luben.zstd_jni;

    exports com.offlinewiki.app to
            javafx.graphics;
}
