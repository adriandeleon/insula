package com.insula.reader;

/**
 * Replaces media the engine cannot decode with a placeholder that offers the user a way out.
 *
 * <p><b>Why this exists.</b> JavaFX's WebView plays only what JavaFX Media plays — in practice
 * MP4/H.264 — while Kiwix archives ship <b>WebM</b>. Measured on TED's archive: every one of its
 * nine videos is VP9 + Vorbis, and {@code canPlayType('video/webm')} answers {@code ""}. WebKit
 * then renders its own dead-end message, "No compatible source was found for this media".
 *
 * <p>The archives anticipate exactly this — Kiwix bundles the <b>ogv.js</b> WASM decoders
 * (vp8/vp9/av1/theora/opus/vorbis) as a video.js fallback tech for engines without WebM. That
 * escape hatch is closed here too: JavaFX's WebKit reports {@code typeof WebAssembly ===
 * "undefined"}, so those decoders cannot run at all. There is no in-page way to play this video.
 *
 * <p>So the honest move is to say so and hand the file to a program that <em>can</em> play it. The
 * bytes are already being served over the loopback server, so any real player (VLC, mpv, a
 * browser) opens that URL directly — no temp file, no copy, no transcode. A poster frame is kept
 * when the markup has one, because losing the visual entirely would read as a broken page.
 *
 * <p>Pure: every method returns a script, so the DOM surgery is unit-testable as text.
 */
public final class MediaFallback {

    /** Name the bridge object takes on {@code window}; the placeholder button calls through it. */
    public static final String BRIDGE = "insulaHost";

    private MediaFallback() {}

    /**
     * Rewrites every {@code <video>}/{@code <audio>} the engine cannot play into a placeholder.
     * Evaluates to the number replaced, so the caller can report honestly rather than guess.
     *
     * <p>The decision is delegated to the engine itself ({@code canPlayType} over each declared
     * source) rather than to a format list of ours: the engine is the authority on what it can
     * decode, and hardcoding "webm is bad" would also swallow the MP4 that a future archive ships.
     */
    public static String installScript(String label, String buttonText) {
        return """
                (function() {
                  if (window.__insulaMediaDone) { return 0; }
                  var media = document.querySelectorAll('video, audio');
                  var replaced = 0;
                  for (var i = 0; i < media.length; i++) {
                    var el = media[i];
                    var sources = [];
                    if (el.getAttribute('src')) { sources.push([el.getAttribute('src'), el.getAttribute('type')]); }
                    var tags = el.querySelectorAll('source');
                    for (var j = 0; j < tags.length; j++) {
                      sources.push([tags[j].getAttribute('src'), tags[j].getAttribute('type')]);
                    }
                    if (sources.length === 0) { continue; }

                    var playable = false, url = null;
                    for (var k = 0; k < sources.length; k++) {
                      var src = sources[k][0];
                      if (!src) { continue; }
                      var type = sources[k][1];
                      // Ask the engine, not a list of ours: it is the authority on its own codecs.
                      if (type && el.canPlayType(type) !== '') { playable = true; break; }
                      if (!type && el.canPlayType('') !== '') { playable = true; break; }
                      // A document with no usable base (about:blank) throws here; the raw src is
                      // still worth showing rather than losing the whole placeholder.
                      if (url === null) {
                        try { url = new URL(src, document.baseURI).href; } catch (e) { url = src; }
                      }
                    }
                    if (playable || url === null) { continue; }

                    var poster = el.getAttribute('poster');
                    var box = document.createElement('div');
                    box.className = 'insula-media-fallback';
                    box.setAttribute('style', 'position:relative;display:block;max-width:100%%;'
                      + 'border:1px solid rgba(128,128,128,0.4);border-radius:8px;overflow:hidden;'
                      + 'background:#101216;color:#e8eaed;font-family:system-ui,sans-serif;');

                    if (poster) {
                      var img = document.createElement('img');
                      try { img.src = new URL(poster, document.baseURI).href; } catch (e) { img.src = poster; }
                      img.setAttribute('style', 'display:block;width:100%%;height:auto;opacity:0.55;');
                      box.appendChild(img);
                    }

                    var bar = document.createElement('div');
                    bar.setAttribute('style', 'display:flex;align-items:center;gap:12px;padding:12px 14px;');

                    var button = document.createElement('button');
                    button.textContent = %s;
                    button.setAttribute('style', 'cursor:pointer;border:0;border-radius:6px;padding:8px 14px;'
                      + 'font-size:14px;font-weight:600;background:#3b82f6;color:#fff;');
                    button.setAttribute('data-insula-src', url);
                    button.onclick = function() {
                      try { window.%s.playExternal(this.getAttribute('data-insula-src')); } catch (e) {}
                      return false;
                    };
                    bar.appendChild(button);

                    var note = document.createElement('span');
                    note.textContent = %s;
                    note.setAttribute('style', 'font-size:13px;opacity:0.75;');
                    bar.appendChild(note);
                    box.appendChild(bar);

                    el.parentNode.replaceChild(box, el);
                    replaced++;
                  }
                  window.__insulaMediaDone = true;
                  return replaced;
                })();
                """.formatted(
                        WebViewRenderer.quote(buttonText == null ? "Play" : buttonText),
                        BRIDGE,
                        WebViewRenderer.quote(label == null ? "" : label));
    }

    /**
     * The standing message. It says "outside Insula" rather than naming a player because the URL
     * goes to the desktop's handler for {@code http}, which is usually the browser rather than
     * VLC — promising a video player would be a promise the platform does not keep.
     */
    public static String defaultLabel() {
        return "This video format can't play in Insula — opens outside the app";
    }
}
