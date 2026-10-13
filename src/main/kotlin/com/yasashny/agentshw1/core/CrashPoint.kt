package com.yasashny.agentshw1.core

enum class CrashPoint(val configValue: String) {
    AFTER_DEBIT_BEFORE_EXEC("after_debit_before_exec"),
    AFTER_EXEC_BEFORE_SAVE("after_exec_before_save");

    companion object {
        fun fromConfig(value: String?): CrashPoint? =
            entries.firstOrNull { it.configValue == value }
    }
}
