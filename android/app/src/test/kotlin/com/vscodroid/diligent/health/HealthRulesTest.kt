package com.vscodroid.diligent.health

import com.vscodroid.diligent.health.HealthRules.ADB_COMMANDS
import com.vscodroid.diligent.health.HealthRules.ADB_COMMAND_MONITOR
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthRulesTest {

    private fun facts(sdk: Int = 34) = HealthFacts(
        sdk = sdk,
        ignoringBatteryOptimizations = true,
        phantomMonitor = "false",
        notificationsEnabled = true,
        freeBytes = HealthRules.MIN_FREE_BYTES,
        processes = 3,
    )

    @Test
    fun `battery passes only when the app is exempt, and offers the settings otherwise`() {
        assertEquals(HealthCheck(HealthTopic.Battery, HealthStatus.Pass), HealthRules.battery(true))
        assertEquals(HealthCheck(HealthTopic.Battery, HealthStatus.Fail, HealthFix.BatterySettings), HealthRules.battery(false))
        assertEquals(HealthStatus.CannotCheck, HealthRules.battery(null).status)
        assertEquals(HealthFix.BatterySettings, HealthRules.battery(null).fix)
    }

    @Test
    fun `the monitor setting is read as off only when it says so`() {
        assertEquals(true, HealthRules.monitorOff("false"))
        assertEquals(true, HealthRules.monitorOff(" FALSE "))
        assertEquals(true, HealthRules.monitorOff("0"))
        assertEquals(false, HealthRules.monitorOff("true"))
        assertEquals(false, HealthRules.monitorOff("1"))
        assertNull(HealthRules.monitorOff(null))
        assertNull(HealthRules.monitorOff(""))
        assertNull(HealthRules.monitorOff("maybe"))
    }

    @Test
    fun `Android 14 and later read the setting, and show the steps when it is on or unreadable`() {
        for (sdk in listOf(34, 35, 36)) {
            assertEquals(HealthCheck(HealthTopic.ChildProcesses, HealthStatus.Pass), HealthRules.childProcesses(sdk, "false"))
            val on = HealthRules.childProcesses(sdk, "true")!!
            assertEquals(HealthStatus.Fail, on.status)
            assertTrue(on.steps)
            assertEquals(HealthFix.DeveloperOptions, on.fix)
            assertTrue(on.commands.isEmpty(), "no adb on 14+")
            val unreadable = HealthRules.childProcesses(sdk, null)!!
            assertEquals(HealthStatus.CannotCheck, unreadable.status)
            assertTrue(unreadable.steps)
        }
    }

    @Test
    fun `Android 12L and 13 show the three adb commands unless the monitor is off`() {
        for (sdk in listOf(32, 33)) {
            assertEquals(HealthCheck(HealthTopic.ChildProcessesAdb, HealthStatus.Pass), HealthRules.childProcesses(sdk, "false"))
            val unknown = HealthRules.childProcesses(sdk, null)!!
            assertEquals(HealthStatus.CannotCheck, unknown.status)
            assertEquals(ADB_COMMANDS + ADB_COMMAND_MONITOR, unknown.commands)
            assertFalse(unknown.steps)
            assertEquals(HealthStatus.Fail, HealthRules.childProcesses(sdk, "true")!!.status)
        }
    }

    @Test
    fun `Android 12 cannot be read, and gets only the two device_config commands`() {
        for (monitor in listOf(null, "false", "true")) {
            val check = HealthRules.childProcesses(31, monitor)!!
            assertEquals(HealthTopic.ChildProcessesAdb, check.topic)
            assertTrue(check.status != HealthStatus.Pass, "12 has no readable switch: $monitor")
            assertEquals(ADB_COMMANDS, check.commands)
        }
    }

    @Test
    fun `the commands are the guide's, word for word`() {
        assertEquals(
            listOf(
                "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent\"",
                "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\"",
            ),
            ADB_COMMANDS,
        )
        assertEquals("adb shell settings put global settings_enable_monitor_phantom_procs false", ADB_COMMAND_MONITOR)
    }

    @Test
    fun `Android 11 has no child process limit to check`() {
        assertNull(HealthRules.childProcesses(30, null))
        assertTrue(HealthRules.evaluate(facts(30)).none { it.topic == HealthTopic.ChildProcesses || it.topic == HealthTopic.ChildProcessesAdb })
    }

    @Test
    fun `notifications are checked from Android 13`() {
        assertNull(HealthRules.notifications(32, false))
        assertEquals(HealthStatus.Pass, HealthRules.notifications(33, true)!!.status)
        assertEquals(HealthCheck(HealthTopic.Notifications, HealthStatus.Fail, HealthFix.NotificationSettings), HealthRules.notifications(33, false))
        assertEquals(HealthStatus.CannotCheck, HealthRules.notifications(36, null)!!.status)
    }

    @Test
    fun `storage needs a gigabyte`() {
        assertEquals(HealthStatus.Pass, HealthRules.storage(HealthRules.MIN_FREE_BYTES).status)
        assertEquals(HealthCheck(HealthTopic.Storage, HealthStatus.Fail, HealthFix.StorageSettings), HealthRules.storage(HealthRules.MIN_FREE_BYTES - 1))
        assertEquals(HealthStatus.Fail, HealthRules.storage(0).status)
        assertEquals(HealthStatus.CannotCheck, HealthRules.storage(null).status)
        assertEquals(HealthStatus.CannotCheck, HealthRules.storage(-1).status)
    }

    @Test
    fun `the process count warns at 24`() {
        assertEquals(HealthStatus.Pass, HealthRules.processes(0).status)
        assertEquals(HealthStatus.Pass, HealthRules.processes(23).status)
        assertEquals(HealthCheck(HealthTopic.Processes, HealthStatus.Fail, HealthFix.RunScreen), HealthRules.processes(24))
        assertEquals(HealthStatus.Fail, HealthRules.processes(40).status)
        assertEquals(HealthCheck(HealthTopic.Processes, HealthStatus.CannotCheck), HealthRules.processes(null))
    }

    @Test
    fun `a passing check never offers a fix`() {
        val all = HealthRules.evaluate(facts(34)) + HealthRules.evaluate(facts(33)) + HealthRules.evaluate(facts(30))
        for (check in all.filter { it.status == HealthStatus.Pass }) {
            assertNull(check.fix, "$check")
            assertTrue(check.commands.isEmpty(), "$check")
            assertFalse(check.steps, "$check")
        }
    }

    @Test
    fun `the screen lists the checks in a fixed order`() {
        assertEquals(
            listOf(HealthTopic.Battery, HealthTopic.ChildProcesses, HealthTopic.Notifications, HealthTopic.Storage, HealthTopic.Processes),
            HealthRules.evaluate(facts(34)).map { it.topic },
        )
        assertEquals(
            listOf(HealthTopic.Battery, HealthTopic.ChildProcessesAdb, HealthTopic.Storage, HealthTopic.Processes),
            HealthRules.evaluate(facts(32)).map { it.topic },
        )
    }
}
