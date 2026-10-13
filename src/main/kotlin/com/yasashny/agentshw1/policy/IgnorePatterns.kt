package com.yasashny.agentshw1.policy

fun matchesIgnorePattern(relativePath: String, patterns: List<String>): Boolean {
    val normalized = relativePath.replace('\\', '/').trimStart('/')
    val fileName = normalized.substringAfterLast('/')
    return patterns.asSequence().map { it.trim() }.filter { it.isNotEmpty() }
        .any { pattern -> matches(pattern, normalized) || (fileName != normalized && matches(pattern, fileName)) }
}

private fun matches(pattern: String, path: String): Boolean {
    if (pattern.endsWith("/")) {
        val prefix = pattern.removeSuffix("/")
        return path == prefix || path.startsWith("$prefix/")
    }
    return Regex(globToRegex(pattern)).matches(path)
}

private fun globToRegex(glob: String): String =
    glob.map { c ->
        when (c) {
            '*' -> ".*"
            '?' -> "."
            else -> Regex.escape(c.toString())
        }
    }.joinToString("")
