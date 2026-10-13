package com.yasashny.agentshw1

import com.yasashny.agentshw1.exec.BashExecutor
import com.yasashny.agentshw1.llm.LLMClient
import com.yasashny.agentshw1.loop.AgentRuntime
import com.yasashny.agentshw1.loop.CaptureUi
import com.yasashny.agentshw1.loop.Ui
import com.yasashny.agentshw1.policy.models.Policy
import com.yasashny.agentshw1.state.RunStore
import com.yasashny.agentshw1.state.models.BudgetLimits
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.state.models.RunState
import java.io.File
import kotlin.io.path.createTempDirectory

class TestEnv private constructor(val root: File) {

    val workspace: File = File(root, "workspace")
    val policyFile: File = File(root, "policy.json")
    val runsDir: File = File(root, ".agent_runs")

    companion object {

        fun create(
            policyJson: String = DEFAULT_TEST_POLICY,
            workspaceFiles: Map<String, String> = mapOf(
                "order.py" to BROKEN_ORDER,
                "test_order.py" to TEST_ORDER,
                "secret_notes/notes.txt" to "internal",
            ),
        ): TestEnv {
            val root = createTempDirectory("agent-hw1-test").toFile()
            val env = TestEnv(root)
            env.workspace.mkdirs()
            workspaceFiles.forEach { (path, content) ->
                val file = File(env.workspace, path)
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
            env.policyFile.writeText(policyJson)
            return env
        }
    }

    fun policy(): Policy = Policy.load(policyFile)

    fun store(): RunStore = RunStore(runsDir)

    fun state(runId: String): RunState = store().loadOrThrow(runId)

    fun newRun(
        llm: LLMClient,
        mode: RunMode = RunMode.AUTO,
        withPlan: Boolean = false,
        limits: BudgetLimits = BudgetLimits(),
        ui: Ui = CaptureUi(),
        task: String = "Fix the order total calculation so the tests pass. Do not modify tests.",
        bashExecutor: BashExecutor = BashExecutor(),
    ): AgentRuntime = AgentRuntime.startRun(
        store = store(),
        llm = llm,
        policy = policy(),
        workspaceRoot = workspace,
        runsDir = runsDir,
        mode = mode,
        userTask = task,
        withPlan = withPlan,
        limits = limits,
        ui = ui,
        bashExecutor = bashExecutor,
    )

    fun attach(runId: String, llm: LLMClient = FakeLlm(), ui: Ui = CaptureUi()): AgentRuntime =
        AgentRuntime.attach(store(), runId, llm, policy(), ui)

    fun wsFile(path: String): File = File(workspace, path)
}
