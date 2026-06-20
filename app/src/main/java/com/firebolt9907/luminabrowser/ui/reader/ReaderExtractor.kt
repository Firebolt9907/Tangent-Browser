package com.firebolt9907.luminabrowser.ui.reader

import android.util.Log
import mozilla.components.concept.engine.EngineSession
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult
import org.json.JSONObject
import org.json.JSONArray

object ReaderExtractor {
    private fun getGeckoSession(engineSession: EngineSession): GeckoSession? {
        if (engineSession !is GeckoEngineSession) return null
        try {
            val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
            field.isAccessible = true
            return field.get(engineSession) as? GeckoSession
        } catch (e: Exception) {
            for (field in GeckoEngineSession::class.java.declaredFields) {
                if (GeckoSession::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    return field.get(engineSession) as? GeckoSession
                }
            }
            for (method in GeckoEngineSession::class.java.declaredMethods) {
                if (GeckoSession::class.java.isAssignableFrom(method.returnType) && method.parameterCount == 0) {
                    method.isAccessible = true
                    return method.invoke(engineSession) as? GeckoSession
                }
            }
        }
        return null
    }

    fun registerPromptDelegate(engineSession: EngineSession, tabId: String) {
        val geckoSession = getGeckoSession(engineSession) ?: return
        val oldDelegate = geckoSession.promptDelegate

        // Avoid wrapping multiple times
        if (oldDelegate is ReaderPromptDelegate) return

        geckoSession.promptDelegate = ReaderPromptDelegate(oldDelegate, tabId)
    }

    fun extractReaderContent(engineSession: EngineSession, tabId: String) {
        val js = """
            javascript:(function() {
                function findArticleElement() {
                    let bestElement = null;
                    let bestScore = 0;
                    const articles = document.querySelectorAll('article');
                    for (let art of articles) {
                        const pCount = art.querySelectorAll('p').length;
                        const textLen = art.textContent.length;
                        const score = pCount * 100 + textLen;
                        if (score > bestScore) {
                            bestScore = score;
                            bestElement = art;
                        }
                    }
                    if (bestElement && bestScore > 200) {
                        return bestElement;
                    }
                    const candidates = document.querySelectorAll('div, section, main');
                    for (let cand of candidates) {
                        let pCount = 0;
                        let textLen = 0;
                        for (let child of cand.childNodes) {
                            if (child.nodeName === 'P') {
                                pCount++;
                                textLen += child.textContent.length;
                            }
                        }
                        const score = pCount * 100 + textLen;
                        if (score > bestScore) {
                            bestScore = score;
                            bestElement = cand;
                        }
                    }
                    return bestElement || document.body;
                }
                const articleEl = findArticleElement();
                if (!articleEl) return;
                const paragraphs = articleEl.querySelectorAll('p');
                if (paragraphs.length < 2 && articleEl.textContent.length < 1000) {
                    return;
                }
                let title = '';
                const h1 = articleEl.querySelector('h1') || document.querySelector('h1');
                if (h1) {
                    title = h1.textContent.trim();
                } else {
                    title = document.title;
                }
                let byline = null;
                const bylineEl = document.querySelector('[class*="author" i], [class*="byline" i], [itemprop="author"], [rel="author"]');
                if (bylineEl) {
                    byline = bylineEl.textContent.trim();
                }
                const blocks = [];
                function walk(node) {
                    if (!node) return;
                    const name = node.nodeName;
                    if (name === 'P') {
                        const text = node.textContent.trim();
                        if (text.length > 0) {
                            blocks.push({ type: 'p', text: text });
                        }
                    } else if (/^H[1-6]${'$'}/.test(name)) {
                        const level = parseInt(name.substring(1));
                        const text = node.textContent.trim();
                        if (text.length > 0) {
                            blocks.push({ type: 'h', level: level, text: text });
                        }
                    } else if (name === 'BLOCKQUOTE') {
                        const text = node.textContent.trim();
                        if (text.length > 0) {
                            blocks.push({ type: 'bq', text: text });
                        }
                    } else if (name === 'UL' || name === 'OL') {
                        const ordered = (name === 'OL');
                        let index = 0;
                        for (let child of node.childNodes) {
                            if (child.nodeName === 'LI') {
                                const text = child.textContent.trim();
                                if (text.length > 0) {
                                    blocks.push({ type: 'li', text: text, ordered: ordered, index: index++ });
                                }
                            }
                        }
                    } else {
                        for (let child of node.childNodes) {
                            walk(child);
                        }
                    }
                }
                if (articleEl === document.body) {
                    const contentEls = articleEl.querySelectorAll('p, h1, h2, h3, h4, h5, h6, blockquote, ul, ol');
                    contentEls.forEach(el => {
                        if (el.parentElement && (el.parentElement.nodeName === 'UL' || el.parentElement.nodeName === 'OL' || el.parentElement.nodeName === 'BLOCKQUOTE')) {
                            return;
                        }
                        if (el.nodeName === 'P') {
                            const text = el.textContent.trim();
                            if (text.length > 0) blocks.push({ type: 'p', text: text });
                        } else if (/^H[1-6]${'$'}/.test(el.nodeName)) {
                            blocks.push({ type: 'h', level: parseInt(el.nodeName.substring(1)), text: el.textContent.trim() });
                        } else if (el.nodeName === 'BLOCKQUOTE') {
                            blocks.push({ type: 'bq', text: el.textContent.trim() });
                        } else if (el.nodeName === 'UL' || el.nodeName === 'OL') {
                            const ordered = (el.nodeName === 'OL');
                            let index = 0;
                            for (let child of el.childNodes) {
                                if (child.nodeName === 'LI') {
                                    blocks.push({ type: 'li', text: child.textContent.trim(), ordered: ordered, index: index++ });
                                }
                            }
                        }
                    });
                } else {
                    for (let child of articleEl.childNodes) {
                        walk(child);
                    }
                }
                if (blocks.length > 0) {
                    const articleData = {
                        title: title,
                        byline: byline,
                        blocks: blocks
                    };
                    prompt("lumina-reader", JSON.stringify(articleData));
                }
            })();
        """.trimIndent().replace("\n", " ")

        engineSession.loadUrl(
            url = js,
            flags = EngineSession.LoadUrlFlags.select(
                EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL,
                EngineSession.LoadUrlFlags.LOAD_FLAGS_REPLACE_HISTORY,
            ),
        )
    }

    private class ReaderPromptDelegate(
        private val oldDelegate: GeckoSession.PromptDelegate?,
        private val tabId: String
    ) : GeckoSession.PromptDelegate {
        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            if (prompt.message == "lumina-reader") {
                val defaultValue = prompt.defaultValue
                if (defaultValue != null) {
                    try {
                        val article = parseArticleJson(defaultValue)
                        if (article != null) {
                            ReaderManager.instance.setReaderEligible(tabId, article)
                        }
                    } catch (e: Exception) {
                        Log.w("ReaderExtractor", "Failed to parse reader article JSON", e)
                    }
                }
                return GeckoResult.fromValue(prompt.confirm("ok"))
            }
            return oldDelegate?.onTextPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onAlertPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onButtonPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onChoicePrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onBeforeUnloadPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onRepostConfirmPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onRepostConfirmPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }
    }

    private fun parseArticleJson(jsonStr: String): ReaderArticle? {
        val root = JSONObject(jsonStr)
        val title = root.optString("title", "")
        val byline = root.optString("byline").takeIf { it.isNotEmpty() && it != "null" }
        val blocksJson = root.optJSONArray("blocks") ?: return null

        val blocks = ArrayList<ReaderBlock>()
        for (i in 0 until blocksJson.length()) {
            val obj = blocksJson.optJSONObject(i) ?: continue
            val type = obj.optString("type")
            when (type) {
                "p" -> {
                    val text = obj.optString("text")
                    blocks.add(ReaderBlock.Paragraph(text))
                }
                "h" -> {
                    val level = obj.optInt("level", 2)
                    val text = obj.optString("text")
                    blocks.add(ReaderBlock.Heading(level, text))
                }
                "bq" -> {
                    val text = obj.optString("text")
                    blocks.add(ReaderBlock.Blockquote(text))
                }
                "li" -> {
                    val text = obj.optString("text")
                    val ordered = obj.optBoolean("ordered", false)
                    val index = obj.optInt("index", 0)
                    blocks.add(ReaderBlock.ListItem(text, ordered, index))
                }
            }
        }
        return ReaderArticle(title, byline, blocks)
    }
}
