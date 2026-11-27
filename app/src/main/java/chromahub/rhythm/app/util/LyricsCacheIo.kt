package chromahub.rhythm.app.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Readers see a complete old or new JSON file, including when playback and downloading overlap. */
internal object LyricsCacheIo {
    /** Refresh a provider cache only if playback/editor has not changed it during the request. */
    @Synchronized fun replaceIfUnchanged(file: File, expected: String, json: String): Boolean {
        if(!file.exists() || file.readText()!=expected) return false
        write(file,json)
        return true
    }

    @Synchronized fun write(file: File, json: String, onlyIfMissing: Boolean = false) {
        if (onlyIfMissing && file.exists()) return
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile("lyrics-", ".tmp", file.parentFile)
        try {
            temporary.writeText(json)
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
}
