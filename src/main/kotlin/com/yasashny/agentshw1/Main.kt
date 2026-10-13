package com.yasashny.agentshw1

import com.yasashny.agentshw1.core.CrashHook
import com.yasashny.agentshw1.journal.JournalEngine
import com.yasashny.agentshw1.llm.LLMClient
import com.yasashny.agentshw1.llm.LLMException
import com.yasashny.agentshw1.llm.OpenAiCompatibleClient
import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolDescriptor
import com.yasashny.agentshw1.loop.AgentRuntime
import com.yasashny.agentshw1.loop.ConsoleUi
import com.yasashny.agentshw1.loop.renderChecklist
import com.yasashny.agentshw1.policy.models.Policy
import com.yasashny.agentshw1.settings.Settings
import com.yasashny.agentshw1.state.RunStore
import com.yasashny.agentshw1.state.models.BudgetLimits
import com.yasashny.agentshw1.state.models.RunState
import com.yasashny.agentshw1.state.models.RunStatus
import java.io.File
import kotlin.system.exitProcess

private const val EXIT_ERROR = 1
private const val EXIT_USAGE = 2
private const val EXIT_CONFLICT = 3
private const val EXIT_SIMULATED_CRASH = 99

private const val STATUS_COLUMN_WIDTH = 22
private const val PHASE_COLUMN_WIDTH = 10
private const val RUNS_DIR_ENV = "AGENT_RUNS_DIR"

private object NoopLlm : LLMClient {
    override fun chat(messages: List<StoredMessage>, tools: List<ToolDescriptor>): ModelReply =
        throw LLMException("this command does not use the model")
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(EXIT_USAGE)
    }
    try {
        dispatch(args.toList())
    } catch (e: CrashHook.HardCrash) {
        println("[crash] ${e.message}")
        println("[crash] the run state on disk is what it was at the last atomic write; resume with: agent resume <runId>")
        exitProcess(EXIT_SIMULATED_CRASH)
    } catch (e: Throwable) {
        println("[error] ${e::class.simpleName}: ${e.message}")
        exitProcess(EXIT_ERROR)
    }
}

private fun dispatch(args: List<String>) {
    when (args[0]) {
        "run" -> cmdRun(args.drop(1))
        "resume" -> cmdResume(args.drop(1))
        "approve" -> cmdDecision(args.drop(1), approved = true)
        "deny" -> cmdDecision(args.drop(1), approved = false)
        "approve-plan" -> cmdPlanDecision(args.drop(1), approve = true)
        "reject-plan" -> cmdPlanDecision(args.drop(1), approve = false)
        "status" -> cmdStatus(args.drop(1))
        "tasks" -> cmdTasks(args.drop(1))
        "diff" -> cmdDiff(args.drop(1))
        "rollback" -> cmdRollback(args.drop(1))
        "runs" -> cmdRuns()
        "help", "--help", "-h" -> usage()
        else -> {
            println("unknown command: ${args[0]}")
            usage()
            exitProcess(EXIT_USAGE)
        }
    }
}

private fun cmdRun(args: List<String>) {
    val options = parseRunOptions(args)
    val limits = parseLimits(options.limitsSpec)
    val policy = loadPolicy(options.policyFile ?: defaultPolicyPath())
    val workspace = options.workspace.absoluteFile.canonicalFile
    val runsDir = runsDirFor(policy.sourcePath, options.runsDirOverride ?: System.getenv(RUNS_DIR_ENV))
    policy.validate(workspace, runsDir) { throw IllegalArgumentException("invalid policy: $it") }

    val runtime = AgentRuntime.startRun(
        store = RunStore(runsDir),
        llm = OpenAiCompatibleClient.fromSettings(loadSettings()),
        policy = policy,
        workspaceRoot = workspace,
        runsDir = runsDir,
        mode = options.mode,
        userTask = options.task,
        withPlan = options.withPlan,
        limits = limits,
        ui = ConsoleUi(),
    )
    runtime.runLoop()
    finishWithExitCode(runtime.state.status)
}

private fun cmdResume(args: List<String>) {
    val options = parseResumeOptions(args)
    val store = RunStore(findRunsDir(options.runId, options.policyFile))
    val state = store.loadOrThrow(options.runId)
    val policy = loadPolicy(options.policyFile ?: File(state.policyPath))
    val runtime = AgentRuntime.attach(store, options.runId, OpenAiCompatibleClient.fromSettings(loadSettings()), policy, ConsoleUi())
    if (options.resolutions.isNotEmpty()) {
        runtime.resolveIndeterminate(options.resolutions, options.attribute)
    }
    if (!runtime.prepareResume()) {
        finishWithExitCode(runtime.state.status)
        return
    }
    runtime.runLoop()
    finishWithExitCode(runtime.state.status)
}

private fun finishWithExitCode(status: RunStatus) {
    when (status) {
        RunStatus.FAILED, RunStatus.BUDGET_EXCEEDED, RunStatus.ABORTED_BY_USER -> exitProcess(EXIT_ERROR)
        else -> return
    }
}

private fun cmdDecision(args: List<String>, approved: Boolean) {
    val runId = runIdArg(args, if (approved) "approve" else "deny")
    val store = runStoreFor(runId)
    val state = store.loadOrThrow(runId)
    val runtime = attachWithoutModel(store, runId, state)
    val confirmation = state.pendingConfirmation
        ?: throw IllegalArgumentException("run $runId has no pending confirmation")
    check(runtime.setConfirmationDecision(runId, confirmation.callId, approved)) { "failed to record the decision" }
    println("decision recorded: ${if (approved) "APPROVED" else "DENIED"} for ${confirmation.name} (${confirmation.callId})")
    println("continue with: agent resume $runId")
}

private fun cmdPlanDecision(args: List<String>, approve: Boolean) {
    val runId = runIdArg(args, if (approve) "approve-plan" else "reject-plan")
    val runtime = runtimeFor(runId)
    if (approve) runtime.approvePlan() else runtime.rejectPlan()
    println(
        if (approve) "plan approved; continue with: agent resume $runId"
        else "plan rejected; run terminated",
    )
}

private fun cmdStatus(args: List<String>) {
    val state = loadRun(args)
    println("run:        ${state.runId}")
    println("status:     ${state.status}")
    println("phase:      ${state.phase}  mode: ${state.mode}  plan: ${state.withPlan}")
    println("task:       ${state.userTask}")
    println(state.budget.summary())
    state.pendingConfirmation?.let { pc ->
        println("pending:    ${pc.name}(${pc.args}) decision=${pc.decision ?: "waiting for user"}")
    }
    val indeterminate = state.calls.filter { it.isIndeterminate }
    if (indeterminate.isNotEmpty()) {
        println("indeterminate calls (need resolution):")
        indeterminate.forEach { println("  - ${it.callId}: ${it.name} [${it.status}]") }
    }
    if (state.tasks.isNotEmpty()) println(renderChecklist(state.tasks))
    state.failure?.let { println("failure:    $it") }
    state.finalAnswer?.let { println("answer:     $it") }
}

private fun cmdTasks(args: List<String>) {
    val state = loadRun(args)
    if (state.tasks.isEmpty()) {
        println("(run ${state.runId} has no task list — it was started without a plan)")
    } else {
        println(renderChecklist(state.tasks))
    }
}

private fun cmdDiff(args: List<String>) {
    val runId = runIdArg(args, "diff")
    val runtime = runtimeFor(runId)
    println(runtime.renderJournalDiff())
}

private fun cmdRollback(args: List<String>) {
    val runId = runIdArg(args, "rollback")
    val runtime = runtimeFor(runId)
    when (val result = runtime.rollback()) {
        is JournalEngine.RollbackResult.Success -> {
            println("rollback of $runId complete:")
            result.restored.forEach { println("  restored $it") }
            result.removed.forEach { println("  removed   $it") }
            if (result.restored.isEmpty() && result.removed.isEmpty()) println("  (nothing to restore)")
        }
        is JournalEngine.RollbackResult.Conflict -> {
            println("[conflict] ${result.message}")
            exitProcess(EXIT_CONFLICT)
        }
    }
}

private fun cmdRuns() {
    val dir = runsDirFor(defaultPolicyPath(), System.getenv(RUNS_DIR_ENV))
    val runs = RunStore(dir).listRuns()
    if (runs.isEmpty()) {
        println("no runs in ${dir.canonicalPath}")
        return
    }
    runs.forEach { run ->
        println(
            "${run.runId}  ${run.status.name.padEnd(STATUS_COLUMN_WIDTH)} " +
                "phase=${run.phase.name.padEnd(PHASE_COLUMN_WIDTH)} ${run.budget.summary()}",
        )
    }
}

private fun runIdArg(args: List<String>, command: String): String =
    args.firstOrNull()?.takeIf { !it.startsWith("--") }
        ?: throw IllegalArgumentException("$command <runId>")

private fun loadRun(args: List<String>): RunState {
    val runId = runIdArg(args, "run")
    return runStoreFor(runId).loadOrThrow(runId)
}

private fun runStoreFor(runId: String): RunStore = RunStore(findRunsDir(runId, null))

private fun attachWithoutModel(store: RunStore, runId: String, state: RunState): AgentRuntime =
    AgentRuntime.attach(store, runId, NoopLlm, loadPolicy(File(state.policyPath)), ConsoleUi())

private fun runtimeFor(runId: String): AgentRuntime {
    val store = runStoreFor(runId)
    return attachWithoutModel(store, runId, store.loadOrThrow(runId))
}

private fun loadSettings(): Settings = Settings.load(Settings.defaultPath())

private fun defaultPolicyPath(): File =
    File(System.getenv("AGENT_POLICY") ?: "policy.json").absoluteFile

private fun loadPolicy(path: File): Policy {
    if (!path.exists()) throw IllegalArgumentException("policy file not found: ${path.canonicalPath}")
    return Policy.load(path)
}

private fun runsDirFor(policy: File, override: String?): File {
    val dir = override?.let { File(it).absoluteFile }
        ?: File(policy.absoluteFile.parentFile ?: File("."), ".agent_runs")
    return dir.canonicalFile
}

private fun findRunsDir(runId: String, policyOverride: File?): File {
    val candidates = buildList<File> {
        if (policyOverride != null) add(runsDirFor(policyOverride.absoluteFile, null))
        System.getenv(RUNS_DIR_ENV)?.let { add(File(it).absoluteFile) }
        add(runsDirFor(defaultPolicyPath(), null))
        add(File(".agent_runs").absoluteFile)
    }
    val found = candidates.firstOrNull { File(it.canonicalFile, "$runId/state.json").exists() }
    return (found ?: candidates.first()).canonicalFile
}

private fun parseLimits(spec: String?): BudgetLimits {
    if (spec == null) return BudgetLimits()
    var limits = BudgetLimits()
    for (pair in spec.split(",").filterNot { it.isBlank() }) {
        val parts = pair.split("=", limit = 2)
        if (parts.size != 2) {
            throw IllegalArgumentException("invalid limit entry '$pair' (use model=N,tools=N,effects=N,output=N)")
        }
        val key = parts[0].trim()
        val rawValue = parts[1].trim()
        val value = rawValue.toLongOrNull()
            ?: throw IllegalArgumentException("invalid limit value for '$key': '$rawValue'")
        if (value < 0) {
            throw IllegalArgumentException("invalid limit value for '$key': '$rawValue' (must be >= 0)")
        }
        if (key != "output" && value > Int.MAX_VALUE) {
            throw IllegalArgumentException("invalid limit value for '$key': '$rawValue' (too large)")
        }
        limits = when (key) {
            "model" -> limits.copy(modelRequests = value.toInt())
            "tools" -> limits.copy(toolCalls = value.toInt())
            "effects" -> limits.copy(sideEffects = value.toInt())
            "output" -> limits.copy(outputBytes = value)
            else -> throw IllegalArgumentException("unknown limit key '$key' (use model|tools|effects|output)")
        }
    }
    return limits
}

private fun usage() {
    println(
        """
        Console coding agent (homework 1)

        Commands:
          run --task "<task text>" [--plan] [--mode safe|auto] [--workspace DIR] [--policy FILE]
              [--limits model=N,tools=N,effects=N,output=N] [--runs-dir DIR]
          resume <runId> [--resolve <callId>=retry|fail|abort]... [--attribute yes|no] [--policy FILE]
          approve <runId> | deny <runId>          confirm/deny a pending action
          approve-plan <runId> | reject-plan <runId>
          status <runId> | tasks <runId> | diff <runId> | rollback <runId> | runs

        Configuration (settings.json, see settings.example.json):
          llm.baseUrl   e.g. https://api.z.ai/api/paas/v4 or http://localhost:11434/v1 (Ollama)
          llm.apiKey    your API key (any non-empty string for local Ollama)
          llm.model     model name, e.g. glm-5.3
        Other:
          AGENT_POLICY  policy file override (default: ./policy.json)
          AGENT_CRASH_POINT / AGENT_CRASH_CALL   controlled failure injection (tests/demo)

        Build & run:
          ./gradlew installDist
          build/install/agents-hw1/bin/agents-hw1 <command> ...
          (or) ./gradlew run --args='<command> ...'
        """.trimIndent(),
    )
}
