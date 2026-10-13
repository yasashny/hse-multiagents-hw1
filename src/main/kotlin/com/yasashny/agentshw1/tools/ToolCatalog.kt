package com.yasashny.agentshw1.tools

object ToolCatalog {

    fun defaultTools(): Map<ToolName, Tool> = listOf(
        ListFilesTool(),
        ReadFileTool(),
        WriteFileTool(),
        DeleteFileTool(),
        BashTool(),
        SubmitPlanTool(),
        CompleteTaskTool(),
    ).associateBy { it.id }

    fun sideEffectIds(): Set<ToolName> =
        defaultTools().values.filter { it.sideEffect }.map { it.id }.toSet()
}
