package ai.opencode.feature.composer

import ai.opencode.core.log.AppLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The bytes behind [ComposerPersistence] (iOS `ComposerPersistenceStorage`):
 * `filesDir/support/composer-state.json` and one blob per inline attachment in
 * `ComposerAttachments/<uuid>`. App-private files are encrypted at rest; `:app`'s backup rules
 * exclude both.
 */
class ComposerPersistenceStorage(
    val identity: String,
    val readStateData: () -> ByteArray?,
    val writeStateData: (ByteArray?) -> Unit,
    val readAttachment: (UUID) -> ByteArray?,
    /** Writes [writes], then removes every blob not in `keeping`. */
    val writeAttachments: (writes: Map<UUID, ByteArray>, keeping: Set<UUID>) -> Unit,
    val removeAll: () -> Unit,
) {
    companion object {
        const val STATE_FILE_NAME = "composer-state.json"
        const val ATTACHMENTS_DIRECTORY_NAME = "ComposerAttachments"

        fun live(baseDirectory: File): ComposerPersistenceStorage {
            val stateFile = File(baseDirectory, STATE_FILE_NAME)
            val attachmentsDirectory = File(baseDirectory, ATTACHMENTS_DIRECTORY_NAME)
            return ComposerPersistenceStorage(
                identity = baseDirectory.path,
                readStateData = { runCatching { stateFile.takeIf(File::exists)?.readBytes() }.getOrNull() },
                writeStateData = { data ->
                    if (data == null) {
                        stateFile.delete()
                    } else {
                        try {
                            writeAtomically(stateFile, data, baseDirectory)
                        } catch (error: Exception) {
                            AppLog.app.error { "Couldn’t write composer state: ${error::class.java.simpleName}" }
                        }
                    }
                },
                readAttachment = { id -> runCatching { File(attachmentsDirectory, id.toString()).takeIf(File::exists)?.readBytes() }.getOrNull() },
                writeAttachments = { writes, keeping ->
                    if (writes.isNotEmpty()) {
                        attachmentsDirectory.mkdirs()
                        for ((id, data) in writes) {
                            try {
                                writeAtomically(File(attachmentsDirectory, id.toString()), data, baseDirectory)
                            } catch (error: Exception) {
                                AppLog.app.error { "Couldn’t write composer attachment (${data.size} bytes): ${error::class.java.simpleName}" }
                            }
                        }
                    }
                    attachmentsDirectory.listFiles()?.let { files ->
                        for (file in files) {
                            val id = runCatching { UUID.fromString(file.name) }.getOrNull()
                            if (id != null && id in keeping) continue
                            file.delete()
                        }
                        if (keeping.isEmpty()) attachmentsDirectory.delete()
                    }
                },
                removeAll = {
                    stateFile.delete()
                    attachmentsDirectory.deleteRecursively()
                },
            )
        }

        fun inMemory(): ComposerPersistenceStorage {
            val lock = Any()
            var stateData: ByteArray? = null
            val attachments = HashMap<UUID, ByteArray>()
            return ComposerPersistenceStorage(
                identity = "in-memory-${UUID.randomUUID()}",
                readStateData = { synchronized(lock) { stateData } },
                writeStateData = { data -> synchronized(lock) { stateData = data } },
                readAttachment = { id -> synchronized(lock) { attachments[id] } },
                writeAttachments = { writes, keeping ->
                    synchronized(lock) {
                        attachments.putAll(writes)
                        attachments.keys.retainAll(keeping)
                    }
                },
                removeAll = {
                    synchronized(lock) {
                        stateData = null
                        attachments.clear()
                    }
                },
            )
        }

        /**
         * Writes a uniquely named temporary file and moves it over [target]. The temp file sits in
         * [tempDirectory] (never the attachments folder, whose sweep removes non-UUID names), so
         * racing writes never clash.
         */
        private fun writeAtomically(target: File, data: ByteArray, tempDirectory: File) {
            target.parentFile?.mkdirs()
            tempDirectory.mkdirs()
            val temp = File.createTempFile(".${target.name}.", ".tmp", tempDirectory)
            try {
                temp.writeBytes(data)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
    }
}
