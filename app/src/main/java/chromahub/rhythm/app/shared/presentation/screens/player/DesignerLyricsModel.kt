package chromahub.rhythm.app.shared.presentation.screens.player

import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.util.LyricsParser
import chromahub.rhythm.app.util.RhythmLyricsParser

internal data class StageLine(val time: Long?, val text: String)
internal fun stageLines(lyrics: LyricsData?): List<StageLine> {
    if (lyrics == null) return emptyList()
    val words = lyrics.wordByWordLyrics?.let { runCatching { RhythmLyricsParser.parseWordByWordLyrics(it) }.getOrNull() }.orEmpty()
    val foreground = words.filterNot { it.background }.ifEmpty { words }
    val wordLines = foreground.map { StageLine(it.lineTimestamp, buildString { it.words.forEachIndexed { i, word -> if(i>0 && !word.isPart) append(' '); append(word.text) } }) }.filter { it.text.isNotBlank() }.sortedBy { it.time }
    if (wordLines.isNotEmpty()) return wordLines
    val synced = lyrics.syncedLyrics?.takeUnless { LyricsData(null, it).isErrorMessage() }?.let { runCatching { LyricsParser.parseLyrics(it) }.getOrNull() }.orEmpty()
    if (synced.isNotEmpty()) return synced.map { StageLine(it.timestamp, it.text) }.sortedBy { it.time }
    return lyrics.plainLyrics?.takeUnless { LyricsData(it, null).isErrorMessage() }.orEmpty().lines().filter { it.isNotBlank() }.map { StageLine(null, it) }
}
