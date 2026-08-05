package com.insula.update;

/**
 * A published release of Insula itself.
 *
 * @param version the tag with any leading {@code v} stripped — {@code v0.2.0} becomes {@code 0.2.0}
 * @param url where to get it, or {@code ""} when the release carried no link
 * @param name the release's title, or {@code ""}
 */
public record ReleaseInfo(String version, String url, String name) {}
