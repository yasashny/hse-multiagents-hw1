package com.yasashny.agentshw1.policy

import com.yasashny.agentshw1.policy.models.Policy
import java.io.File

fun resolvePath(rawPath: String, baseDir: File): File {
    val file = File(rawPath)
    val absolute = if (file.isAbsolute) file else File(baseDir, rawPath)
    return absolute.canonicalFile
}

class PathChecker(
    private val policy: Policy,
    private val protectedPaths: List<File>,
) {

    fun checkPath(rawPath: String, baseDir: File): Decision {
        val canonical: File = try {
            resolvePath(rawPath, baseDir)
        } catch (e: Exception) {
            return Decision.Deny("cannot resolve path '$rawPath': ${e.message}")
        }
        return checkCanonical(canonical, rawPath)
    }

    fun checkCanonical(canonical: File, rawForLog: String? = null): Decision {
        val display = rawForLog ?: canonical.path
        for (protected in protectedPaths) {
            val protectedCanonical = protected.canonicalFile
            if (Policy.isInside(protectedCanonical, canonical)) {
                return Decision.Deny(
                    "path '$display' is protected (policy/state storage) and cannot be touched by tools",
                )
            }
        }
        if (policy.directoriesAllow.none { Policy.isInside(it, canonical) }) {
            return Decision.Deny(
                "path '$display' (${canonical.canonicalPath}) is outside every allowed directory (default deny)",
            )
        }
        val denied = policy.directoriesDeny.firstOrNull { Policy.isInside(it, canonical) }
        if (denied != null) {
            return Decision.Deny(
                "path '$display' is inside denied directory ${denied.canonicalPath} (deny wins over allow)",
            )
        }
        return Decision.Allow
    }
}
