package com.yasashny.agentshw1.policy

import com.yasashny.agentshw1.policy.models.Policy

object CommandChecker {

    private val forbiddenChars = setOf(
        '|', '&', ';', '<', '>', '`', '(', ')', '$',
        '"', '\'', '*', '?', '~', '#', '{', '}', '[', ']', '\\',
    )

    fun normalize(command: String): String =
        command.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            .trim().split(Regex(" +")).joinToString(" ")

    fun check(policy: Policy, command: String): Decision {
        val normalized = normalize(command)
        if (normalized.isEmpty()) return Decision.Deny("empty command")
        val forbidden = normalized.firstOrNull { it in forbiddenChars }
        if (forbidden != null) {
            return Decision.Deny(
                "command contains forbidden bash syntax '$forbidden': only exact simple commands " +
                    "from policy commands.allow are permitted",
            )
        }
        val firstToken = normalized.substringBefore(' ')
        if (normalized in policy.commandsDeny || firstToken in policy.commandsDeny) {
            return Decision.Deny("command is denied by policy commands.deny: $normalized")
        }
        if (normalized in policy.commandsAllow) return Decision.Allow
        return Decision.Deny("command is not in policy commands.allow (default deny): $normalized")
    }
}
