package com.mobileharness.code.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCommandRiskTest {

    @Test fun readOnlyCommandsAreRead() {
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("getprop ro.build.version.release"))
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("pm list packages"))
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("dumpsys battery"))
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("df -h"))
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("cat /proc/meminfo"))
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("ps -A"))
    }

    @Test fun controlCommandsAreControl() {
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of("monkey -p com.android.settings 1"))
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of("input keyevent 3"))
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of("screencap -p /sdcard/screen.png"))
    }

    @Test fun settingsAndWindowQueriesAreReadOnly() {
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("settings list system"))
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("settings get system volume_ring"))
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("wm size"))
        assertEquals(DeviceCommandRisk.READ, DeviceCommandRisk.of("wm density"))
    }

    @Test fun destructiveCommandsAreDestructive() {
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("pm uninstall com.example.app"))
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("pm clear com.example.app"))
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("reboot"))
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("rm /sdcard/file"))
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("rm -rf /data/data/com.foo"))
    }

    @Test fun destructiveThroughWrapperIsDetected() {
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("sh -c 'pm uninstall com.example.app'"))
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("timeout 10 rm /sdcard/x"))
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("busybox rm -rf /tmp"))
    }

    @Test fun commandSubstitutionIsNeverRead() {
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of("echo $(rm -rf /)"))
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of("getprop `rm -rf /`"))
    }

    @Test fun outputRedirectIsNeverRead() {
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of("getprop > /sdcard/out.txt"))
    }

    @Test fun pipelineWithDestructiveIsDestructive() {
        assertEquals(DeviceCommandRisk.DESTRUCTIVE, DeviceCommandRisk.of("ls | rm -rf /tmp"))
    }

    @Test fun unknownCommandFailsClosedToControl() {
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of("someunknownbinary --flag"))
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of(""))
        assertEquals(DeviceCommandRisk.CONTROL, DeviceCommandRisk.of("   "))
    }

    @Test fun catalogRisksAreConsistentWithClassifier() {
        DeviceCommandCatalog.all().forEach { cmd ->
            assertEquals("catalog risk for ${cmd.id} must match classifier", cmd.risk, DeviceCommandRisk.of(cmd.command))
        }
    }
}

class DeviceControlSecurityTest {

    private fun state() = DeviceApprovalState()

    @Test fun readNeverRequiresConfirmation() {
        val approval = state()
        assertEquals(true, approval.requiresConfirmation(DeviceCommandRisk.READ).not())
        val decision = DeviceControlSecurity.evaluate("getprop ro.build.version.release", approval)
        assertEquals(true, decision is DeviceControlSecurity.Decision.Run)
    }

    @Test fun controlRequiresConfirmationOnceThenIsRemembered() {
        val approval = state()
        assertEquals(true, approval.requiresConfirmation(DeviceCommandRisk.CONTROL))
        assertEquals(true, DeviceControlSecurity.evaluate("input keyevent 3", approval) is DeviceControlSecurity.Decision.Confirm)

        approval.approve(DeviceCommandRisk.CONTROL)
        assertEquals(false, approval.requiresConfirmation(DeviceCommandRisk.CONTROL))
        assertEquals(true, DeviceControlSecurity.evaluate("input keyevent 4", approval) is DeviceControlSecurity.Decision.Run)
    }

    @Test fun destructiveAlwaysRequiresConfirmation() {
        val approval = state()
        approval.approve(DeviceCommandRisk.CONTROL)
        approval.approve(DeviceCommandRisk.DESTRUCTIVE) // must not be remembered
        assertEquals(true, approval.requiresConfirmation(DeviceCommandRisk.DESTRUCTIVE))
        assertEquals(true, DeviceControlSecurity.evaluate("reboot", approval) is DeviceControlSecurity.Decision.Confirm)
    }

    @Test fun resetClearsControlApproval() {
        val approval = state()
        approval.approve(DeviceCommandRisk.CONTROL)
        assertEquals(false, approval.requiresConfirmation(DeviceCommandRisk.CONTROL))
        approval.reset()
        assertEquals(true, approval.requiresConfirmation(DeviceCommandRisk.CONTROL))
    }
}

class DeviceAuditLogTest {

    @Test fun auditLogIsBounded() {
        val log = DeviceAuditLog(maxEntries = 3)
        repeat(10) { i ->
            DeviceControlSecurity.audit(log, "getprop foo$i", "shizuku", DeviceCommandOutcome.ALLOWED)
        }
        assertEquals(3, log.entries().size)
    }

    @Test fun auditLogFiltersByRisk() {
        val log = DeviceAuditLog()
        DeviceControlSecurity.audit(log, "getprop foo", "shizuku", DeviceCommandOutcome.SUCCESS)
        DeviceControlSecurity.audit(log, "reboot", "root", DeviceCommandOutcome.DENIED)
        assertEquals(1, log.byRisk(DeviceCommandRisk.DESTRUCTIVE).size)
        assertEquals(1, log.byRisk(DeviceCommandRisk.READ).size)
    }

    @Test fun auditLogDoesNotStoreSecrets() {
        val log = DeviceAuditLog()
        DeviceControlSecurity.audit(log, "getprop foo", "shizuku", DeviceCommandOutcome.SUCCESS, reason = "secret=abc123")
        val entry = log.entries().first()
        assertEquals(false, entry.command.contains("secret="))
    }
}
