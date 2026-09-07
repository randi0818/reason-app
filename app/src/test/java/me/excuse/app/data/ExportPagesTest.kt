package me.excuse.app.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPagesTest {
    private data class Record(val time: Long, val id: Long)

    @Test
    fun consumesEachPageBeforeFetchingTheNextAndPreservesTimestampTies() = runBlocking {
        val records = (1L..600L).map { Record(if (it < 520) -100 else 50, it) }
        val seen = mutableListOf<Record>()
        val cursors = mutableListOf<Pair<Long?, Long>>()
        val count = consumeExportPages(
            fetch = { time, id ->
                if (cursors.isNotEmpty()) assertEquals(id, seen.last().id)
                cursors += time to id
                records.filter { time == null || it.time > time || (it.time == time && it.id > id) }.take(256)
            },
            timestamp = { it.time },
            id = { it.id },
            consume = { seen += it },
        )
        assertEquals(600, count)
        assertEquals(records, seen)
        assertEquals(listOf(null to 0L, -100L to 256L, -100L to 512L, 50L to 600L), cursors)
    }

    @Test
    fun emptyExportDoesNotEmitAnyRecords() = runBlocking {
        val count = consumeExportPages<Record>(
            fetch = { _, _ -> emptyList() }, timestamp = { it.time }, id = { it.id },
            consume = { error("must not emit") },
        )
        assertEquals(0, count)
    }

    @Test
    fun failingWriterStopsFetchingImmediately() = runBlocking {
        var fetches = 0
        try {
            consumeExportPages(
                fetch = { _: Long?, _: Long -> fetches++; listOf(Record(0, fetches.toLong())) },
                timestamp = { it.time }, id = { it.id }, consume = { throw IOException("disk full") },
            )
            error("failure was swallowed")
        } catch (expected: IOException) {
            assertEquals("disk full", expected.message)
        }
        assertEquals(1, fetches)
    }

    @Test
    fun cancellationStopsBeforeReadingAnotherPage() {
        var fetches = 0
        try {
            runBlocking {
                val job = currentCoroutineContext()[Job]!!
                consumeExportPages(
                    fetch = { _: Long?, _: Long -> fetches++; listOf(Record(0, fetches.toLong())) },
                    timestamp = { it.time }, id = { it.id }, consume = { job.cancel() },
                )
            }
            error("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertTrue(fetches == 1)
        }
    }
}
