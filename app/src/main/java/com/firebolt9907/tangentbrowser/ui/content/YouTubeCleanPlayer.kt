package com.firebolt9907.tangentbrowser.ui.content

import androidx.core.net.toUri
import mozilla.components.concept.engine.EngineSession

/**
 * "Clean player" isolation for YouTube. The goal is to hide YouTube's own UI entirely and present
 * only the raw `<video>` surface, so that fullscreen and Picture-in-Picture show just the video
 * (PiP captures the whole EngineView surface, so the page itself must be showing only the video).
 *
 * Implementation is DOM-structure-agnostic on purpose: rather than target YouTube's ever-changing
 * `.ytp-*` class names, "clean mode" hides *everything* on the page and re-shows only the `<video>`
 * element, positioned fixed to fill the viewport. Clean mode is toggled by `fullscreenchange`
 * (entering DOM fullscreen turns it on; exiting turns it off), which is exactly the transition our
 * native [com.firebolt9907.tangentbrowser.ui.video.VideoFullscreenOverlay] rides on.
 *
 * Injected once per document on YouTube pages; guarded by `window.__tangentCleanPlayer`. Survives
 * YouTube's SPA navigations (same document), so it is re-armed via ContentHost's onLocationChange.
 */
internal fun String.isYouTubePageUrl(): Boolean {
    val host = toUri().host ?: return false
    return host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtu.be" || host.endsWith(".youtu.be")
}

internal fun EngineSession.injectYouTubeCleanPlayer() {
    val script = """
        javascript:(()=>{if(window.__tangentCleanPlayer)return;window.__tangentCleanPlayer=true;
        var css='html.tangent-clean,body.tangent-clean{overflow:hidden!important;background:#000!important;margin:0!important;padding:0!important}'
        +'body.tangent-clean *{visibility:hidden!important}'
        +'body.tangent-clean video{visibility:visible!important;position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100dvh!important;max-width:none!important;max-height:none!important;object-fit:contain!important;background:#000!important;z-index:2147483647!important}';
        function ensureStyle(){var s=document.getElementById('tangent-clean-style');if(!s){s=document.createElement('style');s.id='tangent-clean-style';s.textContent=css;(document.head||document.documentElement).appendChild(s);}}
        function setClean(on){ensureStyle();document.body&&document.body.classList.toggle('tangent-clean',on);}
        document.addEventListener('fullscreenchange',function(){setClean(!!document.fullscreenElement);},true);
        })()
    """.trimIndent().replace("\n", "")
    loadJavascriptUrl(script)
}
