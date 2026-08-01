package com.offlinewiki.zim;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The MIME type list: consecutive zero-terminated strings starting at {@code mimeListPos},
 * terminated by an empty string. A dirent's u16 mimetype indexes into this list; values
 * {@code >= 0xFFFD} are special markers, not indices.
 */
final class MimeList {

    /** Dirent mimetype marker: redirect entry. */
    static final int REDIRECT = 0xFFFF;
    /** Dirent mimetype marker: link target (legacy, no content). */
    static final int LINK_TARGET = 0xFFFE;
    /** Dirent mimetype marker: deleted entry (legacy, no content). */
    static final int DELETED = 0xFFFD;

    private final List<String> types;

    private MimeList(List<String> types) {
        this.types = types;
    }

    static MimeList parse(LittleEndianFile in, long mimeListPos) throws IOException {
        List<String> types = new ArrayList<>();
        long pos = mimeListPos;
        while (true) {
            LittleEndianFile.ZString s = in.zeroTerminated(pos);
            if (s.value().isEmpty()) {
                return new MimeList(types);
            }
            types.add(s.value());
            pos = s.end();
        }
    }

    String byIndex(int index) {
        if (index >= DELETED) {
            return "";
        }
        if (index < 0 || index >= types.size()) {
            return "application/octet-stream";
        }
        return types.get(index);
    }

    int size() {
        return types.size();
    }
}
