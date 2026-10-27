package com.vscodroid.diligent.health

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthReportTest {

    @Test
    fun `each check becomes an entry with id, words and state`() {
        val facts = HealthFacts(
            sdk = 34,
            ignoringBatteryOptimizations = true,
            phantomMonitor = "true",
            notificationsEnabled = null,
            freeBytes = 100L,
            processes = 3,
        )
        val checks = HealthRules.evaluate(facts)
        val json = HealthReport.json(HealthReport.lines(checks, { "T:" + it.topic }, { "D:" + it.status }))
        val array = JSONObject(json).getJSONArray("checks")
        val got = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            listOf(o.getString("id"), o.getString("state"), o.getString("title"), o.getString("detail"))
        }
        assertEquals(
            listOf(
                listOf("battery", "pass", "T:Battery", "D:Pass"),
                listOf("child-processes", "fail", "T:ChildProcesses", "D:Fail"),
                listOf("notifications", "unknown", "T:Notifications", "D:CannotCheck"),
                listOf("storage", "fail", "T:Storage", "D:Fail"),
                listOf("processes", "pass", "T:Processes", "D:Pass"),
            ),
            got,
        )
    }

    @Test
    fun `words are escaped as JSON`() {
        val json = HealthReport.json(listOf(HealthLine("storage", "Say \"hi\"", "pass", "a\nb")))
        val o = JSONObject(json).getJSONArray("checks").getJSONObject(0)
        assertEquals("Say \"hi\"", o.getString("title"))
        assertEquals("a\nb", o.getString("detail"))
    }

    @Test
    fun `no checks is an empty list`() {
        assertEquals("""{"checks":[]}""", HealthReport.json(emptyList()))
    }

    @Test
    fun `every topic has its own id`() {
        assertEquals(HealthTopic.entries.size, HealthTopic.entries.map(HealthReport::id).toSet().size)
    }
}
