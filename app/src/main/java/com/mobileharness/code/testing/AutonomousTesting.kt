package com.mobileharness.code.testing

import java.util.UUID

/**
 * Autonomous Testing & Repair.
 *
 * Detects a project's build/test system, plans a bounded test run, executes it through an injected
 * command runner, analyzes failures, plans a *minimal* repair, applies it, and re-runs to check for
 * regressions — all within a hard attempt budget so a failing project can never loop forever.
 *
 * This is pure JVM logic. The Android layer supplies the actual command executor (the PRoot terminal
 * or RuntimeBridge); this package owns discovery, planning, analysis and the repair loop. It never
 * performs git push/release — those stay gated by the user's existing rules.
 */

/** The recognized build/test systems, in detection order. */
enum class BuildSystem(val id: String, val label: String) {
    GRADLE("gradle", "Gradle"),
    MAVEN("maven", "Maven"),
    NPM("npm", "npm"),
    PNPM("pnpm", "pnpm"),
    YARN("yarn", "Yarn"),
    PYTEST("pytest", "pytest"),
    UNITTEST("unittest", "Python unittest"),
    CARGO("cargo", "Cargo"),
    GO("go", "Go"),
    CMAKE("cmake", "CMake"),
    UNKNOWN("unknown", "Unknown"),
}

data class TestStep(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val command: String,
    val kind: String = "test", // test | build | lint | static | dependency | license
)

data class TestPlan(
    val buildSystem: BuildSystem,
    val steps: List<TestStep>,
    val note: String = "",
)

/** Detects the build/test system from a project's files (case-insensitive filenames). */
object TestDiscovery {
    fun detect(files: Set<String>): BuildSystem {
        val names = files.map { it.lowercase() }
        return when {
            names.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") } -> BuildSystem.GRADLE
            names.any { it == "pom.xml" } -> BuildSystem.MAVEN
            names.any { it == "pnpm-lock.yaml" || it == "pnpm-workspace.yaml" } -> BuildSystem.PNPM
            names.any { it == "yarn.lock" } -> BuildSystem.YARN
            names.any { it == "package.json" } -> BuildSystem.NPM
            names.any { it == "cargo.toml" } -> BuildSystem.CARGO
            names.any { it == "go.mod" } -> BuildSystem.GO
            names.any { it == "cmakelists.txt" } -> BuildSystem.CMAKE
            names.any { it.startsWith("test_") && it.endsWith(".py") || it.endsWith("_test.py") || it == "pytest.ini" || it == "tox.ini" || it == "pyproject.toml" } -> BuildSystem.PYTEST
            else -> BuildSystem.UNKNOWN
        }
    }
}

/** Plans the standard verification steps for a detected build system. */
object TestPlanner {
    fun plan(buildSystem: BuildSystem): TestPlan = when (buildSystem) {
        BuildSystem.GRADLE -> TestPlan(
            buildSystem,
            listOf(
                TestStep(name = "unit tests", command = "./gradlew test", kind = "test"),
                TestStep(name = "build", command = "./gradlew assemble", kind = "build"),
                TestStep(name = "lint", command = "./gradlew lint", kind = "lint"),
            ),
        )
        BuildSystem.MAVEN -> TestPlan(
            buildSystem,
            listOf(TestStep(name = "tests", command = "mvn test", kind = "test"), TestStep(name = "package", command = "mvn package", kind = "build")),
        )
        BuildSystem.NPM, BuildSystem.PNPM -> TestPlan(
            buildSystem,
            listOf(TestStep(name = "tests", command = if (buildSystem == BuildSystem.PNPM) "pnpm test" else "npm test", kind = "test")),
        )
        BuildSystem.YARN -> TestPlan(
            buildSystem,
            listOf(TestStep(name = "tests", command = "yarn test", kind = "test")),
        )
        BuildSystem.PYTEST -> TestPlan(
            buildSystem,
            listOf(TestStep(name = "pytest", command = "pytest", kind = "test")),
        )
        BuildSystem.UNITTEST -> TestPlan(
            buildSystem,
            listOf(TestStep(name = "unittest", command = "python -m unittest discover", kind = "test")),
        )
        BuildSystem.CARGO -> TestPlan(
            buildSystem,
            listOf(TestStep(name = "cargo test", command = "cargo test", kind = "test")),
        )
        BuildSystem.GO -> TestPlan(
            buildSystem,
            listOf(TestStep(name = "go test", command = "go test ./...", kind = "test")),
        )
        BuildSystem.CMAKE -> TestPlan(
            buildSystem,
            listOf(TestStep(name = "ctest", command = "ctest", kind = "test")),
        )
        BuildSystem.UNKNOWN -> TestPlan(buildSystem, emptyList(), "No recognized build/test system.")
    }
}

/** Outcome of a single test step. */
data class StepResult(
    val step: TestStep,
    val exitCode: Int,
    val output: String,
    val durationMillis: Long,
) {
    val passed: Boolean get() = exitCode == 0
}

/** Runs steps through an injected executor and returns per-step results. */
class TestRunner(private val run: suspend (String) -> Pair<Int, String>) {
    suspend fun run(plan: TestPlan): List<StepResult> {
        val results = mutableListOf<StepResult>()
        for (step in plan.steps) {
            val started = System.currentTimeMillis()
            val (exit, output) = run(step.command)
            results += StepResult(step, exit, output, System.currentTimeMillis() - started)
        }
        return results
    }
}

/** Extracts a machine-usable failure summary from raw test output. */
object FailureAnalyzer {
    data class Failure(val stepName: String, val message: String, val context: String = "")

    private val FAILURE_MARKERS = listOf(
        "FAILED", "ERROR:", "error:", "Exception:", "AssertionError", "BUILD FAILED",
        "FAIL: ", "Traceback (most recent call last)", "✗", "✘", "panic:", "not ok ",
    )

    fun analyze(results: List<StepResult>): List<Failure> {
        val failures = mutableListOf<Failure>()
        for (result in results) {
            if (result.passed) continue
            val lines = result.output.lineSequence().toList()
            val firstMarker = lines.firstOrNull { line -> FAILURE_MARKERS.any { line.contains(it) } }
            val message = firstMarker?.trim()?.take(300) ?: "Step \"${result.step.name}\" failed with exit code ${result.exitCode}."
            failures += Failure(result.step.name, message, result.output.take(2_000))
        }
        return failures
    }

    fun anyFailed(results: List<StepResult>): Boolean = results.any { !it.passed }
}

/** A minimal, human-reviewable repair proposal — never auto-applied without a guard. */
data class RepairProposal(
    val failure: FailureAnalyzer.Failure,
    val description: String,
    val targetFile: String? = null,
    val patch: String? = null,
    val confidence: String = "low",
)

/** Plans a conservative repair for a failure (rule-based; no arbitrary code rewriting). */
object RepairPlanner {
    fun propose(failure: FailureAnalyzer.Failure): RepairProposal {
        val message = failure.message.lowercase()
        val description: String
        val targetFile: String? = null
        val patch: String? = null
        val confidence: String
        when {
            message.contains("cannot resolve symbol") || message.contains("unresolved reference") -> {
                description = "Add or correct the missing import/symbol in the failing file."
                confidence = "low"
            }
            message.contains("compilation error") || message.contains("syntax error") -> {
                description = "Fix the reported syntax/compilation error in the failing source file."
                confidence = "low"
            }
            message.contains("assertion") || (message.contains("expected") && message.contains("actual")) -> {
                description = "Reconcile the failing assertion with the actual behavior."
                confidence = "low"
            }
            message.contains("license") -> {
                description = "Add or correct the dependency license metadata (licenseCheck)."
                confidence = "medium"
            }
            message.contains("lint") -> {
                description = "Resolve the reported lint issue."
                confidence = "low"
            }
            else -> {
                description = "Investigate and fix the reported failure."
                confidence = "low"
            }
        }
        return RepairProposal(failure, description, targetFile, patch, confidence)
    }
}

/** Applies a repair through an injected, permission-checked editor; returns the new content result. */
class RepairExecutor(private val applyPatch: suspend (RepairProposal) -> Boolean) {
    suspend fun apply(proposal: RepairProposal): Boolean = applyPatch(proposal)
}

/** Re-runs the plan after a repair and reports whether any regression appeared. */
class RegressionChecker(private val runner: TestRunner) {
    suspend fun check(before: List<StepResult>, plan: TestPlan): Pair<List<StepResult>, Boolean> {
        val after = runner.run(plan)
        val beforePassed = before.filter { it.passed }.map { it.step.id }.toSet()
        val afterFailed = after.filter { !it.passed }.map { it.step.id }.toSet()
        val regression = afterFailed.any { it in beforePassed }
        return after to regression
    }
}

/** A recorded test/repair cycle for the history store. */
data class TestHistoryEntry(
    val timestampMillis: Long,
    val buildSystem: BuildSystem,
    val stepsRun: Int,
    val passed: Int,
    val failed: Int,
    val repairsApplied: Int,
    val result: String,
)

/** Bounded, append-only history of test runs (Android layer persists it if desired). */
class TestHistoryStore(private val maxEntries: Int = 200) {
    private val entries = ArrayDeque<TestHistoryEntry>()

    fun record(entry: TestHistoryEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    fun snapshot(): List<TestHistoryEntry> = entries.toList()

    fun clear() = entries.clear()
}

/**
 * The autonomous repair loop: run → analyze → propose → apply → re-run, bounded by [maxRepairs]
 * (default 3) so a stubborn failure can never loop forever. It returns the final results plus a
 * machine-readable summary, and stops early when no repair could be proposed or applied.
 */
class RepairLoop(
    private val runner: TestRunner,
    private val executor: RepairExecutor,
    private val regression: RegressionChecker,
    private val history: TestHistoryStore = TestHistoryStore(),
    private val maxRepairs: Int = 3,
) {
    data class Outcome(
        val results: List<StepResult>,
        val repairsApplied: Int,
        val summary: String,
        val succeeded: Boolean,
    )

    suspend fun run(plan: TestPlan, buildSystem: BuildSystem = plan.buildSystem): Outcome {
        var results = runner.run(plan)
        var repairs = 0
        while (repairs < maxRepairs && FailureAnalyzer.anyFailed(results)) {
            val failures = FailureAnalyzer.analyze(results)
            val proposal = failures.firstOrNull()?.let { RepairPlanner.propose(it) } ?: break
            val applied = runCatching { executor.apply(proposal) }.getOrDefault(false)
            if (!applied) break
            repairs++
            val (after, regressed) = regression.check(results, plan)
            results = after
            if (regressed) break
        }
        val failedCount = results.count { !it.passed }
        val succeeded = failedCount == 0
        val summary = buildString {
            append(succeeded.takeIf { it }?.let { "All tests passed" } ?: "$failedCount step(s) still failing")
            append(" after $repairs repair(s).")
        }
        history.record(
            TestHistoryEntry(
                timestampMillis = System.currentTimeMillis(),
                buildSystem = buildSystem,
                stepsRun = results.size,
                passed = results.count { it.passed },
                failed = failedCount,
                repairsApplied = repairs,
                result = if (succeeded) "success" else "failed",
            ),
        )
        return Outcome(results, repairs, summary, succeeded)
    }
}
