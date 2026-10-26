package com.vscodroid.diligent.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RunWhereTest {

    @Test
    fun `phone, laptop and unknown`() {
        assertEquals(RunWhere.Phone, RunWhere.parse("phone"))
        assertEquals(RunWhere.Laptop("mac"), RunWhere.parse("laptop:mac"))
        assertEquals(RunWhere.Laptop("Dita's Mac"), RunWhere.parse(" laptop: Dita's Mac "))
        assertEquals(RunWhere.Laptop("a:b"), RunWhere.parse("laptop:a:b"))
        assertEquals(RunWhere.Unknown("laptop:"), RunWhere.parse("laptop:"))
        assertEquals(RunWhere.Unknown("laptop:"), RunWhere.parse("laptop:  "))
        assertEquals(RunWhere.Unknown("desktop:mac"), RunWhere.parse("desktop:mac"))
        assertEquals("laptop:mac", RunWhere.laptop("mac"))
    }

    @Test
    fun `run json keeps where and prepare`() {
        val text = """{ "profiles": [ { "name": "dev", "command": "npm run dev", "port": 3000, "where": "laptop:mac", "prepare": "npm ci" } ] }"""
        val config = (RunConfig.parse(text) as RunConfigParse.Ok).config
        val p = config.profiles.single()
        assertEquals("laptop:mac", p.where)
        assertEquals("npm ci", p.prepare)
        assertEquals(config, (RunConfig.parse(config.toJson()) as RunConfigParse.Ok).config)
        val noPrepare = (RunConfig.parse("""{ "profiles": [ { "name": "a", "command": "x", "prepare": 5 } ] }""") as RunConfigParse.Ok).config
        assertEquals(null, noPrepare.profiles.single().prepare)
    }

    @Test
    fun `a laptop run previews on the forwarded port`() {
        val profile = RunProfile("dev", "npm run dev", 3000, "laptop:mac")
        val state = RunUiState(config = RunConfigState.Loaded(RunConfig(listOf(profile))), active = "dev", status = RunStatus.Running, localPort = 3005)
        assertEquals(3005, state.previewPort)
        assertEquals(RunWhere.Laptop("mac"), state.where)
        assertEquals(3000, state.copy(localPort = null).previewPort)
    }
}
