package com.yasashny.agentshw1.tools

enum class ToolName(val wire: String) {
    LIST_FILES("list_files"),
    READ_FILE("read_file"),
    WRITE_FILE("write_file"),
    DELETE_FILE("delete_file"),
    BASH("bash"),
    SUBMIT_PLAN("submit_plan"),
    COMPLETE_TASK("complete_task");

    companion object {
        fun fromWire(value: String): ToolName? = entries.firstOrNull { it.wire == value }
    }
}
