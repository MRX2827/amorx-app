package chromahub.rhythm.app.shared.presentation.screens.player

import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.util.LyricsParser
import chromahub.rhythm.app.util.RhythmLyricsParser

internal data class StageLine(val time: Long?, val text: String, val endTime: Long? = null)
internal data class StageCue(val index: Int = -1, val active: Boolean = false)

/** Blank timestamps end emphasis; they must not delete and rebuild the lyric viewport. */
internal class StageTimeline(val lines: List<StageLine>) {
    private val starts = lines.map { it.time ?: 0L }.toLongArray()
    private val previousVocal = IntArray(lines.size)
    init {
        var previous = -1
        lines.indices.forEach { i ->
            if(lines[i].text.isNotBlank()) previous = i
            previousVocal[i] = previous
        }
    }

    fun cue(positionMs: Long, offsetMs: Long): StageCue {
        val index = chromahub.rhythm.app.util.LyricClock.index(starts, positionMs, offsetMs)
        if(index < 0) return StageCue()
        val time = positionMs.coerceAtLeast(0) + offsetMs
        val line = lines[index]
        if(line.text.isNotBlank() && time < (line.endTime ?: Long.MAX_VALUE)) return StageCue(index, true)
        val previous = previousVocal[index]
        // The completed line stays in the same place, clearly inactive, until the next
        // actual vocal cue. This also survives long LRC blank markers and the outro.
        return if(previous >= 0) StageCue(previous, false) else StageCue()
    }

    fun history(cue: StageCue): List<StageLine> = if(cue.index < 0) emptyList() else
        lines.subList(0, cue.index).asReversed().asSequence().filter { it.text.isNotBlank() }.take(2).toList().asReversed()
}

internal fun stageLines(lyrics: LyricsData?): List<StageLine> {
    if (lyrics == null) return emptyList()
    val words = lyrics.wordByWordLyrics?.let { runCatching { RhythmLyricsParser.parseWordByWordLyrics(it) }.getOrNull() }.orEmpty()
    val foreground = words.filterNot { it.background }.ifEmpty { words }
    val wordLines = foreground.map { line ->
        // A provider may give the line a pre-roll timestamp. Words mark the actual vocal start.
        val start = maxOf(line.lineTimestamp, line.words.firstOrNull()?.timestamp ?: line.lineTimestamp)
        StageLine(start, buildString {
            line.words.forEachIndexed { i, word -> if(i>0 && !word.isPart) append(' '); append(word.text) }
        }, line.lineEndtime.takeIf { !line.endIsImplicit && it > start })
    }.filter { it.text.isNotBlank() }.sortedBy { it.time }
    if (wordLines.isNotEmpty()) return wordLines
    val synced = lyrics.syncedLyrics?.takeUnless { LyricsData(null, it).isErrorMessage() }?.let { runCatching { LyricsParser.parseLyrics(it) }.getOrNull() }.orEmpty()
    if (synced.isNotEmpty()) return synced.map { StageLine(it.timestamp, it.text, it.endTime) }.sortedBy { it.time }
    return lyrics.plainLyrics?.takeUnless { LyricsData(it, null).isErrorMessage() }.orEmpty().lines().filter { it.isNotBlank() }.map { StageLine(null, it) }
}
