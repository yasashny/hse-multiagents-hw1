package com.yasashny.agentshw1.core

object CrashHook {

    const val ENV_POINT = "AGENT_CRASH_POINT"
    const val ENV_CALL = "AGENT_CRASH_CALL"

    private const val DEFAULT_ORDINAL = 1

    class HardCrash(message: String) : RuntimeException(message)

    private fun config(key: String): String? =
        (System.getProperty(key) ?: System.getenv(key))?.trim()?.takeIf { it.isNotEmpty() }

    private fun targetOrdinal(): Int = config(ENV_CALL)?.toIntOrNull() ?: DEFAULT_ORDINAL

    fun activePointFor(dispatchOrdinal: Int): CrashPoint? =
        CrashPoint.fromConfig(config(ENV_POINT))?.takeIf { dispatchOrdinal == targetOrdinal() }

    fun crash(point: CrashPoint, detail: String): Nothing =
        throw HardCrash("SIMULATED CRASH (${point.configValue}): $detail")
}
