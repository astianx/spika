package org.astianx.midori.one.chat

import java.util.Date
import org.jivesoftware.smackx.mam.MamManager

/** Checkpoint a page only after every message has returned from durable acceptance. */
internal class ChatArchiveCoordinator(private val records: ChatRecords, private val key: String) {
    val timestamp: Long? get() = records.get(key)?.toLongOrNull()
    val cursor: String? get() = records.get("$key/cursor")?.takeIf { it.isNotBlank() }

    fun arguments(): MamManager.MamQueryArgs {
        val builder = MamManager.MamQueryArgs.builder().setResultPageSizeTo(100)
        val after = cursor
        if (after != null) builder.afterUid(after)
        else timestamp?.let { builder.limitResultsSince(Date((it - 60_000).coerceAtLeast(0))) }
        // An initial sync starts at the beginning. queryLastPage silently loses older pages.
        return builder.build()
    }

    fun commitPage(latest: Long, after: String?) {
        records.durableBatch {
            if (latest > 0) records.put(key, latest.toString())
            if (!after.isNullOrBlank()) records.put("$key/cursor", after)
        }
    }
}
