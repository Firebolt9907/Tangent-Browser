package com.firebolt9907.luminabrowser.ui.content

import android.net.Uri
import mozilla.components.concept.engine.EngineSession

private const val LuminaAmoInstallerId = "lumina-amo-addon-installer"

internal fun String.isAmoPageUrl(): Boolean {
    val host = Uri.parse(this).host ?: return false
    return host == "addons.mozilla.org" || host.endsWith(".addons.mozilla.org")
}

internal fun EngineSession.injectAmoAddonInstaller() {
    val script = """
        javascript:(()=>{if(window.$LuminaAmoInstallerId)return;window.$LuminaAmoInstallerId=true;function slug(){const p=location.pathname.split('/').filter(Boolean);const i=p.indexOf('addon');return i>=0?p[i+1]:null}function latestXpi(s){return 'https://addons.mozilla.org/firefox/downloads/latest/'+encodeURIComponent(s)+'/latest.xpi?src=lumina-amo-button'}function installUrlFrom(target){const link=target.closest&&target.closest('a[href]');if(link){const href=link.href||'';if(href.includes('/firefox/downloads/latest/')||href.endsWith('.xpi'))return href}const button=target.closest&&target.closest('a,button,[role="button"]');if(!button)return null;const label=((button.innerText||button.textContent||button.getAttribute('aria-label')||button.title||'')+'').toLowerCase();if(!label.includes('add to firefox')&&!label.includes('get the extension'))return null;const s=slug();return s?latestXpi(s):null}document.addEventListener('click',e=>{const url=installUrlFrom(e.target);if(!url)return;e.preventDefault();e.stopPropagation();location.href=url},true)})()
    """.trimIndent()
    loadUrl(
        url = script,
        flags = EngineSession.LoadUrlFlags.select(
            EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL,
            EngineSession.LoadUrlFlags.LOAD_FLAGS_REPLACE_HISTORY,
        ),
    )
}
