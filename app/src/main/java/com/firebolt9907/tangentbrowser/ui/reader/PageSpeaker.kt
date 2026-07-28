package com.firebolt9907.tangentbrowser.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads a reader-extracted [ReaderArticle] aloud with the platform TextToSpeech engine — Safari's
 * "Listen to Page". Singleton so playback state survives the aA sheet opening/closing; the TTS
 * engine is created lazily on first use and reused. Uses the application context, so it is not tied
 * to any Activity. [speaking] drives the play/stop button state.
 */
object PageSpeaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: List<String>? = null
    private var speakingTabId: String? = null

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Start reading if idle, stop if already speaking. */
    fun toggle(context: Context, article: ReaderArticle, tabId: String? = null) {
        if (_speaking.value) stop() else speak(context, article, tabId)
    }

    fun speak(context: Context, article: ReaderArticle, tabId: String? = null) {
        val utterances = buildUtterances(article)
        if (utterances.isEmpty()) return
        speakingTabId = tabId
        val engine = tts
        if (engine != null && ready) {
            enqueue(engine, utterances)
        } else {
            pending = utterances
            if (engine == null) initEngine(context.applicationContext)
        }
    }

    fun stop() {
        pending = null
        speakingTabId = null
        tts?.stop()
        _speaking.value = false
    }

    /** Stop only if the given tab is the one currently being read (e.g. it navigated away). */
    fun stopForTab(tabId: String) {
        if (speakingTabId == tabId) stop()
    }

    /**
     * Release the TextToSpeech engine (and its bound system service connection) when nothing is
     * playing. Called on memory pressure; the engine is recreated lazily on the next [speak].
     */
    fun releaseIfIdle() {
        if (_speaking.value) return
        pending = null
        speakingTabId = null
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun initEngine(appContext: Context) {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            val engine = tts
            if (ready && engine != null) {
                engine.language = Locale.getDefault()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _speaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        // Only the final chunk clears the speaking flag; intermediate chunks keep it set.
                        if (utteranceId == LAST_ID) _speaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _speaking.value = false
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _speaking.value = false
                    }
                })
                pending?.let { enqueue(engine, it) }
                pending = null
            } else {
                _speaking.value = false
                pending = null
            }
        }
    }

    private fun enqueue(engine: TextToSpeech, utterances: List<String>) {
        engine.stop()
        _speaking.value = true
        utterances.forEachIndexed { i, text ->
            val id = if (i == utterances.lastIndex) LAST_ID else "u$i"
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(text, mode, null, id)
        }
    }

    private fun buildUtterances(article: ReaderArticle): List<String> {
        val parts = ArrayList<String>()
        if (article.title.isNotBlank()) parts.add(article.title)
        article.blocks.forEach { block ->
            when (block) {
                is ReaderBlock.Heading -> parts.add(block.text)
                is ReaderBlock.Paragraph -> parts.add(block.text)
                is ReaderBlock.ListItem -> parts.add(block.text)
                is ReaderBlock.Blockquote -> parts.add(block.text)
                is ReaderBlock.Code -> Unit
                is ReaderBlock.Divider -> Unit
                is ReaderBlock.Table -> block.rows.forEach { row -> parts.add(row.joinToString(", ")) }
                is ReaderBlock.Image -> Unit
                // Speak a figure's caption (charts/diagrams/equations have no readable text otherwise).
                is ReaderBlock.Figure -> block.caption?.let { parts.add(it) }
                // Social thread: read the author, then the post/comment body.
                is ReaderBlock.SocialPost -> {
                    parts.add(block.author)
                    block.title?.let { parts.add(it) }
                    if (block.text.isNotBlank()) parts.add(block.text)
                }
                is ReaderBlock.SocialComment -> {
                    parts.add(block.author)
                    if (block.text.isNotBlank()) parts.add(block.text)
                }
            }
        }
        // TextToSpeech silently drops utterances longer than getMaxSpeechInputLength (~4000 chars),
        // so split long paragraphs on word boundaries to avoid skipping content.
        return parts.filter { it.isNotBlank() }.flatMap { chunk(it) }
    }

    private fun chunk(text: String): List<String> {
        val max = 3800
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                val lastSpace = text.lastIndexOf(' ', end)
                if (lastSpace > start) end = lastSpace
            }
            out.add(text.substring(start, end).trim())
            start = end
        }
        return out.filter { it.isNotEmpty() }
    }

    private const val LAST_ID = "tangent-tts-last"
}
