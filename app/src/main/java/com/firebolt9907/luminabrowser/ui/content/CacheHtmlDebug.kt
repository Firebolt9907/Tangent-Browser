package com.firebolt9907.luminabrowser.ui.content

import android.util.Log
import mozilla.components.concept.engine.EngineSession

private const val TAG = "CacheHtmlDebug"

/**
 * Hidden debug command: extracts the current page's parsed HTML and opens it as a data URI
 * in the same tab. Useful for inspecting what GeckoView actually received from Google Cache
 * when diagnosing paywall-bypass consistency issues.
 *
 * Activated by typing `debug:cache` in the address bar.
 */
internal fun EngineSession.extractCacheHtml() {
    val script = buildExtractCacheHtmlScript()
    Log.d(TAG, "extractCacheHtml: injecting script (${script.length} chars)")
    loadUrl(
        url = script,
        flags = EngineSession.LoadUrlFlags.select(
            EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL,
        ),
    )
}

/**
 * Builds the debug script that captures `document.documentElement.outerHTML`, encodes it as a
 * data URI, and navigates to it so the raw parsed HTML is visible as plain text.
 *
 * Pure function — no [EngineSession] receiver, no side effects.  Unit-testable.
 */
internal fun buildExtractCacheHtmlScript(): String {
    // The script writes a plain-text HTML page showing the raw source, then replaces the
    // document with it.  Using document.open/write/close avoids data-URI size limits and
    // races with pending JavaScript redirects on the cache page.
    return """
        javascript:(function(){
          try {
            var src = document.documentElement.outerHTML;
            if (!src || src.length === 0) {
              document.title = 'debug:cache — empty document';
              return;
            }
            document.open();
            document.write('<!DOCTYPE html><html><head>'
              + '<meta charset="utf-8">'
              + '<title>debug:cache — parsed HTML (' + src.length + ' chars)</title>'
              + '<style>'
              + 'body{margin:0;padding:16px;font:13px/1.5 monospace;background:#1a1a2e;color:#e0e0e0}'
              + 'h1{font-size:14px;color:#7f8c8d;border-bottom:1px solid #333;padding-bottom:8px}'
              + '</style></head><body>'
              + '<h1>debug:cache — ' + src.length + ' chars</h1>'
              + '<pre>' + src.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') + '</pre>'
              + '</body></html>');
            document.close();
          } catch(e) {
            document.title = 'debug:cache error: ' + e.message;
          }
        })();
    """.trimIndent().replace("\n", "")
}
