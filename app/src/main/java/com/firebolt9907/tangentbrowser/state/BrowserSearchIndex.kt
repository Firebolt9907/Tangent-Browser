package com.firebolt9907.tangentbrowser.state

import android.content.Context
import android.os.Build
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import androidx.appsearch.platformstorage.PlatformStorage
import androidx.concurrent.futures.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.BookmarksStorage
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.concept.storage.VisitType

/**
 * Indexes visited pages and bookmarks into Android **AppSearch** so they can surface in the system /
 * launcher search box. On API 31+ it uses [PlatformStorage] (globally visible to system search
 * surfaces by default); on older devices it falls back to app-private [LocalStorage] so the index
 * still exists for any in-app use.
 *
 * Entirely best-effort: every operation is wrapped so an AppSearch failure (service unavailable on a
 * given device, migration hiccup, quota) can never disturb browsing. Re-indexing is keyed by a
 * stable per-URL id, so repeated refreshes update in place rather than duplicating.
 */
object BrowserSearchIndex {
    private const val DB_NAME = "tangent_browser_index"
    private const val NAMESPACE = "browsing"
    private const val SCHEMA_TYPE = "BrowserEntry"
    private const val HISTORY_LIMIT = 500

    data class Entry(
        val url: String,
        val title: String,
        val kind: String,
        val lastUsedMillis: Long,
    )

    private val schema: AppSearchSchema = AppSearchSchema.Builder(SCHEMA_TYPE)
        .addProperty(
            AppSearchSchema.StringPropertyConfig.Builder("url")
                .setCardinality(AppSearchSchema.StringPropertyConfig.CARDINALITY_REQUIRED)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                .build(),
        )
        .addProperty(
            AppSearchSchema.StringPropertyConfig.Builder("title")
                .setCardinality(AppSearchSchema.StringPropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                .build(),
        )
        .addProperty(
            AppSearchSchema.StringPropertyConfig.Builder("kind")
                .setCardinality(AppSearchSchema.StringPropertyConfig.CARDINALITY_OPTIONAL)
                .build(),
        )
        .addProperty(
            AppSearchSchema.LongPropertyConfig.Builder("lastUsed")
                .setCardinality(AppSearchSchema.LongPropertyConfig.CARDINALITY_OPTIONAL)
                .build(),
        )
        .build()

    private suspend fun openSession(context: Context): AppSearchSession =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PlatformStorage.createSearchSessionAsync(
                PlatformStorage.SearchContext.Builder(context, DB_NAME).build(),
            ).await()
        } else {
            LocalStorage.createSearchSessionAsync(
                LocalStorage.SearchContext.Builder(context, DB_NAME).build(),
            ).await()
        }

    /**
     * Pull recent history + all bookmarks and rebuild the index. Reads run off the caller's thread on
     * [Dispatchers.IO]. No-ops (silently) on any failure.
     */
    suspend fun refresh(
        context: Context,
        historyStorage: HistoryStorage,
        bookmarksStorage: BookmarksStorage,
    ) {
        val entries = runCatching { collectEntries(historyStorage, bookmarksStorage) }
            .getOrNull()
            ?: return
        reindex(context.applicationContext, entries)
    }

    private suspend fun collectEntries(
        historyStorage: HistoryStorage,
        bookmarksStorage: BookmarksStorage,
    ): List<Entry> = withContext(Dispatchers.IO) {
        val history = historyStorage.getVisitsPaginated(
            offset = 0,
            count = HISTORY_LIMIT.toLong(),
            excludeTypes = listOf(
                VisitType.RELOAD,
                VisitType.REDIRECT_PERMANENT,
                VisitType.REDIRECT_TEMPORARY,
                VisitType.EMBED,
                VisitType.DOWNLOAD,
            ),
        ).map { visit ->
            Entry(
                url = visit.url,
                title = visit.title.orEmpty(),
                kind = "history",
                lastUsedMillis = visit.visitTime,
            )
        }

        val bookmarks = bookmarksStorage.getTree(BookmarkRoot.Mobile.id, recursive = true)
            .getOrNull()
            ?.children
            ?.filter { it.type == BookmarkNodeType.ITEM && !it.url.isNullOrBlank() }
            ?.map { node ->
                Entry(
                    url = node.url.orEmpty(),
                    title = node.title.orEmpty(),
                    kind = "bookmark",
                    lastUsedMillis = node.dateAdded ?: 0L,
                )
            }
            .orEmpty()

        // Bookmarks win over history for the same URL (a bookmark is the stronger signal).
        (history + bookmarks).associateBy { it.url }.values.toList()
    }

    /** Replace the whole index with [entries]. Runs on IO; never throws. */
    suspend fun reindex(context: Context, entries: List<Entry>): Unit = withContext(Dispatchers.IO) {
        runCatching {
            openSession(context).use { session ->
                // setForceOverride so an (unlikely) incompatible schema change can't hard-fail; on an
                // unchanged schema this is a no-op.
                session.setSchemaAsync(
                    SetSchemaRequest.Builder().addSchemas(schema).setForceOverride(true).build(),
                ).await()

                entries.chunked(100).forEach { chunk ->
                    val docs = chunk.mapNotNull { entry ->
                        if (entry.url.isBlank()) return@mapNotNull null
                        GenericDocument.Builder<GenericDocument.Builder<*>>(
                            NAMESPACE, docId(entry.url), SCHEMA_TYPE,
                        )
                            .setPropertyString("url", entry.url)
                            .setPropertyString("title", entry.title)
                            .setPropertyString("kind", entry.kind)
                            .setPropertyLong("lastUsed", entry.lastUsedMillis)
                            .setCreationTimestampMillis(
                                entry.lastUsedMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            )
                            .build()
                    }
                    if (docs.isNotEmpty()) {
                        session.putAsync(
                            PutDocumentsRequest.Builder().addGenericDocuments(docs).build(),
                        ).await()
                    }
                }
                session.requestFlushAsync().await()
            }
        }
        Unit
    }

    // AppSearch ids must be printable ASCII; a URL isn't, so key on its hash. Stable per URL, so a
    // re-index updates the same document instead of creating a duplicate.
    private fun docId(url: String): String = url.hashCode().toUInt().toString(16)
}
