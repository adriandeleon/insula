package com.insula.zim;

import java.io.IOException;

/** A structurally invalid or unsupported ZIM file. */
public class ZimFormatException extends IOException {
    public ZimFormatException(String message) {
        super(message);
    }
}
