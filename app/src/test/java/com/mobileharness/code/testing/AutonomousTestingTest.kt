package com.mobileharness.code.testing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousTestingTest {

    @Test
    fun discovery_recognizesBuildSystems() {
        assertEquals(BuildSystem.GRADLE, TestDiscovery.detect(setOf("app/build.gradle.kts")))
        assertEquals(BuildSystem.MAVEN, TestDiscovery.detect(setOf("pom.xml")))
        assertEquals(BuildSystem.NPM, TestDiscovery.detect(setOf("package.json")))
        assertEquals(BuildSystem.PNPM, TestDiscovery.detect(setOf("pnpm-lock.yaml")))
        assertEquals(BuildSystem.YARN, TestDiscovery.detect(setOf("yarn.lock")))
        assertEquals(BuildSystem.CARGO, TestDiscovery.detect(setOf("Cargo.toml")))
        assertEquals(BuildSystem.GO, TestDiscovery.detect(setOf("go.mod")))
        assertEquals(BuildSystem.CMAKE, TestDiscovery.detect(setOf("CMakeLists.txt")))
        assertEquals(BuildSystem.PYTEST, TestDiscovery.detect(setOf("test_foo.py")))
        assertEquals(BuildSystem.UNKNOWN, TestDiscovery.detect(setOf("readme.txt")))
    }

    @Test
    fun planner_buildsStepsPerSystem() {
        assertEquals(3, TestPlanner.plan(BuildSystem.GRADLE).steps.size)
        assertEquals(2, TestPlanner.plan(BuildSystem.MAVEN).steps.size)
        assertEquals(0, TestPlanner.plan(BuildSystem.UNKNOWN).steps.size)
    }

    @Test
    fun failureAnalyzer_findsFailures() = runBlocking {
        val runner = TestRunner { command ->
            if (command.contains("gradlew test")) 1 to "FAILED: some test failed\nstack trace" else 0 to "ok"
        }
        val plan = TestPlanner.plan(BuildSystem.GRADLE)
        val results = runner.run(plan)
        val failures = FailureAnalyzer.analyze(results)
        assertEquals(1, failures.size)
        assertTrue(failures[0].message.contains("FAILED"))
        assertTrue(FailureAnalyzer.anyFailed(results))
    }

    @Test
    fun repairLoop_succeedsAfterOneRepair() = runBlocking {
        var attempt = 0
        val runner = TestRunner { _ ->
            attempt++
            if (attempt == 1) 1 to "FAILED: compilation error" else 0 to "ok"
        }
        val executor = RepairExecutor { true }
        val regression = RegressionChecker(runner)
        val loop = RepairLoop(runner, executor, regression, maxRepairs = 3)
        val outcome = loop.run(TestPlanner.plan(BuildSystem.GRADLE))
        assertTrue(outcome.succeeded)
        assertEquals(1, outcome.repairsApplied)
        assertTrue(outcome.summary.contains("1 repair"))
    }

    @Test
    fun repairLoop_stopsWhenNoRepairApplied() = runBlocking {
        val runner = TestRunner { _ -> 1 to "FAILED: unknown" }
        val executor = RepairExecutor { false }
        val loop = RepairLoop(runner, executor, RegressionChecker(runner), maxRepairs = 5)
        val outcome = loop.run(TestPlanner.plan(BuildSystem.GRADLE))
        assertFalse(outcome.succeeded)
        assertEquals(0, outcome.repairsApplied)
    }

    @Test
    fun repairLoop_respectsMaxRepairs() = runBlocking {
        var repairs = 0
        val runner = TestRunner { _ -> 1 to "FAILED: still broken" }
        val executor = RepairExecutor { repairs++; true }
        val loop = RepairLoop(runner, executor, RegressionChecker(runner), maxRepairs = 2)
        val outcome = loop.run(TestPlanner.plan(BuildSystem.GRADLE))
        assertEquals(2, outcome.repairsApplied)
        assertFalse(outcome.succeeded)
    }

    @Test
    fun historyStore_recordsBounded() {
        val store = TestHistoryStore(maxEntries = 2)
        repeat(3) { store.record(TestHistoryEntry(it.toLong(), BuildSystem.GRADLE, 1, 1, 0, 0, "success")) }
        assertEquals(2, store.snapshot().size)
    }
}
