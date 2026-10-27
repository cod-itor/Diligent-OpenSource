package com.vscodroid.diligent.guide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class GuidedStartTest {

    @Test
    fun `each goal makes a recipe the kit ships`() {
        val shipped = File("src/main/assets/extensions").listFiles { f -> f.name.startsWith("diligent.diligent-kit-") }
            ?.flatMap { File(it, "recipes").list()?.toList().orEmpty() }
            ?.map { it.removeSuffix(".json") }
            .orEmpty()
        assertTrue(shipped.isNotEmpty(), "no kit recipes found from ${File(".").absolutePath}")
        for (goal in GuideGoal.entries) assertTrue(goal.recipe in shipped, "$goal: ${goal.recipe}")
    }

    @Test
    fun `only new to coding turns beginner mode on, with a website`() {
        assertEquals(listOf(GuideGoal.NewToCoding), GuideGoal.entries.filter { it.beginner })
        assertEquals(GuideGoal.Website.recipe, GuideGoal.NewToCoding.recipe)
        assertFalse(GuideGoal.React.beginner)
    }

    @Test
    fun `back walks the steps and closes on the first`() {
        assertEquals(GuideStep.Name, GuideStep.Confirm.previous)
        assertEquals(GuideStep.Goal, GuideStep.Name.previous)
        assertNull(GuideStep.Goal.previous)
    }

    @Test
    fun `the suggested name avoids a project that exists`() {
        assertEquals("my-site", suggestedName(GuideGoal.Website) { false })
        assertEquals("my-site-2", suggestedName(GuideGoal.Website) { it == "my-site" })
    }
}
