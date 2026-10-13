package com.yasashny.agentshw1.journal

import com.yasashny.agentshw1.core.TextDiff
import com.yasashny.agentshw1.core.b64
import com.yasashny.agentshw1.core.sha256
import com.yasashny.agentshw1.core.unb64
import com.yasashny.agentshw1.policy.Decision
import com.yasashny.agentshw1.policy.PathChecker
import com.yasashny.agentshw1.policy.matchesIgnorePattern
import com.yasashny.agentshw1.policy.models.Policy
import com.yasashny.agentshw1.state.models.FileEntry
import com.yasashny.agentshw1.state.models.RunState
import com.yasashny.agentshw1.state.models.RunStatus
import java.io.File

class JournalEngine(
    private val workspaceRoot: File,
    private val policy: Policy,
) {

    data class Tree(val hashes: Map<String, String>, val contents: Map<String, String>) {

        fun diff(other: Tree): TreeDiff {
            val created = other.hashes.keys - hashes.keys
            val deleted = hashes.keys - other.hashes.keys
            val changed = hashes.keys.intersect(other.hashes.keys)
                .filter { hashes[it] != other.hashes[it] }
            return TreeDiff(changed.sorted(), created.sorted(), deleted.sorted())
        }
    }

    data class TreeDiff(
        val changed: List<String>,
        val created: List<String>,
        val deleted: List<String>,
    ) {
        fun isEmpty() = changed.isEmpty() && created.isEmpty() && deleted.isEmpty()

        fun allPaths() = changed + created + deleted

        fun describe(): String {
            if (isEmpty()) return "no differences"
            val changes = changed.map { "changed $it" } +
                created.map { "created $it" } +
                deleted.map { "deleted $it" }
            return "external modifications detected: ${changes.joinToString(", ")}"
        }
    }

    sealed interface RollbackResult {
        data class Success(val restored: List<String>, val removed: List<String>) : RollbackResult
        data class Conflict(val message: String) : RollbackResult
    }

    fun scan(): Tree {
        val hashes = mutableMapOf<String, String>()
        val contents = mutableMapOf<String, String>()
        for (dir in policy.directoriesAllow) {
            walk(dir, hashes, contents)
        }
        return Tree(hashes, contents)
    }

    private fun lastTree(state: RunState): Tree =
        Tree(state.journal.lastSnapshot, state.journal.lastSnapshotContents)

    fun preCheck(state: RunState): TreeDiff? {
        val diff = lastTree(state).diff(scan())
        return if (diff.isEmpty()) null else diff
    }

    fun postAction(state: RunState): List<String> {
        val diff = applyTree(state, attributeToRun = true)
        return diff.allPaths()
    }

    fun absorbCurrentTree(state: RunState, attributeToRun: Boolean): TreeDiff =
        applyTree(state, attributeToRun)

    fun renderDiff(state: RunState): String {
        if (state.journal.entries.isEmpty()) return "(this run changed no files)"
        val prefix = if (state.status == RunStatus.ROLLED_BACK) {
            "(run was rolled back: the changes below were undone on disk)\n\n"
        } else {
            ""
        }
        return prefix + state.journal.entries.joinToString("\n\n") { entry ->
            val unchanged = entry.originalHash == entry.finalHash
            val header = "${entry.path}: " + when {
                !entry.existedBefore && entry.finalContentB64 != null -> "CREATED by this run"
                entry.existedBefore && entry.finalContentB64 == null -> "DELETED by this run"
                unchanged -> "(net unchanged)"
                else -> "MODIFIED by this run"
            }
            if (unchanged) {
                header
            } else {
                val before = entry.originalContentB64?.let { unb64(it) }
                val after = entry.finalContentB64?.let { unb64(it) }
                if (before?.isUtf8Text() == false || after?.isUtf8Text() == false) {
                    header + "\n(binary content: ${before?.size ?: 0} bytes -> ${after?.size ?: 0} bytes; text diff omitted)"
                } else {
                    header + "\n" + TextDiff.unifiedDiff(
                        before?.let { String(it, Charsets.UTF_8) },
                        after?.let { String(it, Charsets.UTF_8) },
                        entry.path,
                    )
                }
            }
        }
    }

    fun rollback(state: RunState, pathChecker: PathChecker): RollbackResult {
        val touched = state.journal.entries.filter { it.changedOverRun() }
        if (touched.isEmpty()) return RollbackResult.Success(emptyList(), emptyList())

        for (entry in touched) {
            when (val decision = pathChecker.checkCanonical(File(workspaceRoot, entry.path))) {
                is Decision.Deny -> return RollbackResult.Conflict(
                    "rollback aborted: path '${entry.path}' no longer allowed: ${decision.reason}",
                )
                Decision.Allow -> {}
            }
        }
        for (entry in touched) {
            val file = File(workspaceRoot, entry.path)
            val currentHash = if (file.exists()) sha256(file) else null
            if (currentHash != entry.finalHash) {
                return RollbackResult.Conflict(
                    "rollback aborted: '${entry.path}' was modified externally after this run's last action " +
                        "(current sha256=${currentHash ?: "<deleted>"}, expected ${entry.finalHash ?: "<deleted>"}); " +
                        "no file was overwritten",
                )
            }
        }

        val restored = mutableListOf<String>()
        val removed = mutableListOf<String>()
        for (entry in touched.sortedBy { it.path }) {
            val file = File(workspaceRoot, entry.path)
            if (entry.existedBefore) {
                val original = entry.originalContentB64 ?: continue
                file.parentFile?.mkdirs()
                file.writeBytes(unb64(original))
                restored.add(entry.path)
            } else {
                if (file.exists()) file.delete()
                removed.add(entry.path)
            }
        }
        return RollbackResult.Success(restored, removed)
    }

    private fun applyTree(state: RunState, attributeToRun: Boolean): TreeDiff {
        val before = lastTree(state)
        val after = scan()
        val diff = before.diff(after)
        val entries =
            if (diff.isEmpty() || !attributeToRun) {
                state.journal.entries
            } else {
                val updated = state.journal.entries.toMutableList()
                for (path in diff.allPaths().sorted()) {
                    upsertEntry(updated, path, before, after)
                }
                updated
            }
        state.journal = state.journal.copy(
            lastSnapshot = after.hashes,
            lastSnapshotContents = after.contents,
            entries = entries,
        )
        return diff
    }

    private fun upsertEntry(
        entries: MutableList<FileEntry>,
        path: String,
        before: Tree,
        after: Tree,
    ) {
        val index = entries.indexOfFirst { it.path == path }
        if (index >= 0) {
            entries[index] = entries[index].copy(
                finalHash = after.hashes[path],
                finalContentB64 = after.contents[path],
            )
        } else {
            entries.add(
                FileEntry(
                    path = path,
                    existedBefore = before.hashes.containsKey(path),
                    originalHash = before.hashes[path],
                    originalContentB64 = before.contents[path],
                    finalHash = after.hashes[path],
                    finalContentB64 = after.contents[path],
                ),
            )
        }
    }

    private fun walk(
        current: File,
        hashes: MutableMap<String, String>,
        contents: MutableMap<String, String>,
    ) {
        if (!current.exists()) return
        val relative = relativeToWorkspace(current) ?: return
        if (matchesIgnorePattern(relative, policy.journalIgnore)) return
        if (current.isDirectory) {
            if (insideDeniedDir(current)) return
            current.listFiles()?.sortedBy { it.name }?.forEach { walk(it, hashes, contents) }
        } else {
            if (insideDeniedDir(current.parentFile ?: current)) return
            val bytes = try {
                current.readBytes()
            } catch (e: Exception) {
                return
            }
            hashes[relative] = sha256(bytes)
            contents[relative] = b64(bytes)
        }
    }

    private fun insideDeniedDir(dir: File): Boolean =
        policy.directoriesDeny.any { Policy.isInside(it, dir) }

    private fun relativeToWorkspace(file: File): String? {
        val rootPath = workspaceRoot.canonicalPath
        val workspacePath = rootPath.removeSuffix(File.separator) + File.separator
        val path = file.canonicalPath
        return when {
            path.startsWith(workspacePath) -> path.removePrefix(workspacePath).replace(File.separatorChar, '/')
            path == rootPath -> ""
            else -> null
        }
    }

    private fun decode(base64: String): String = String(unb64(base64), Charsets.UTF_8)

    private fun ByteArray.isUtf8Text(): Boolean = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(this))
        true
    } catch (e: java.nio.charset.CharacterCodingException) {
        false
    }

    private fun FileEntry.changedOverRun(): Boolean =
        originalHash != finalHash || existedBefore != (finalContentB64 != null)
}
