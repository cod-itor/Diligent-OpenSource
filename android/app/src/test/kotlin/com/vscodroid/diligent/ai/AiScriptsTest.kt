package com.vscodroid.diligent.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AiScriptsTest {

    private val scripts = AiScripts(homeDir = "/home/u", shell = "/bin/bash", execPreload = null)
    private val commit = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `sessions are named diligent-ai, agent, project`() {
        assertEquals("diligent-ai-claude-my-app", AiSession.name(AiAgent.Claude, "My App"))
        assertEquals("diligent-ai-agy-project", AiSession.name(AiAgent.Antigravity, "..::"))
        assertEquals("diligent-ai-claude-it-s-rm-rf", AiSession.name(AiAgent.Claude, "it's \$(rm -rf)"))
        assertTrue(AiSession.name(AiAgent.Claude, "x".repeat(100)).length <= "diligent-ai-claude-".length + 32)
    }

    @Test
    fun `the start script quotes the folder and records the snapshot`() {
        val dir = "/p/it's \$(id) `x`"
        val s = scripts.start("diligent-ai-claude-x", dir, AiAgent.Claude, commit, 42L)
        assertTrue(s.contains("[ -d '/p/it'\\''s \$(id) `x`' ]"), s)
        assertTrue(s.contains("tmux has-session -t '=diligent-ai-claude-x'"), s)
        assertTrue(s.contains("'/home/u/.diligent/ai/diligent-ai-claude-x.json'"), s)
        assertTrue(s.contains("\"commit\":\"$commit\",\"started\":42"), s)
        assertTrue(s.contains("exec tmux new-session -d -s 'diligent-ai-claude-x' -c '/p/it'\\''s"), s)
    }

    @Test
    fun `the start script refuses a commit that is not one`() {
        assertThrows<IllegalArgumentException> { scripts.start("s", "/p", AiAgent.Claude, "HEAD", 0L) }
    }

    @Test
    fun `the agent's script runs the agent in the project and waits at the end`() {
        val inner = scripts.inner("/p/a b", AiAgent.Antigravity)
        assertEquals("cd '/p/a b' || exit 2", inner.lines().first())
        assertTrue(inner.lines().contains("agy"))
        assertEquals("read -r _", inner.lines().last())
    }

    @Test
    fun `attach and stop name the exact session`() {
        assertEquals("tmux attach -t '=diligent-ai-agy-x'", scripts.attachCommand("diligent-ai-agy-x"))
        assertTrue(scripts.stop("diligent-ai-agy-x").startsWith("tmux kill-session -t '=diligent-ai-agy-x'"))
    }

    @Test
    fun `status reads installed, missing and broken`() {
        assertEquals(AiToolStatus.Installed("2.1.3"), AiScripts.parseStatus(listOf("code 0", "2.1.3 (Claude Code)")))
        assertEquals(AiToolStatus.Installed("2.0.0"), AiScripts.parseStatus(listOf("code 0", "agy version 2.0.0")))
        assertEquals(AiToolStatus.NotInstalled, AiScripts.parseStatus(listOf("code 127")))
        assertEquals(
            AiToolStatus.NotInstalled,
            AiScripts.parseStatus(listOf("code 127", "claude: install the Claude Code extension first")),
        )
        assertEquals(AiToolStatus.Broken(159, "Bad system call"), AiScripts.parseStatus(listOf("code 159", "Bad system call")))
        assertEquals(AiToolStatus.Broken(0, "hello"), AiScripts.parseStatus(listOf("code 0", "hello")))
        assertEquals(AiToolStatus.Unknown, AiScripts.parseStatus(listOf("garbage")))
    }

    @Test
    fun `the session list keeps only AI sessions`() {
        assertEquals(
            setOf("diligent-ai-claude-x"),
            AiScripts.parseList(listOf("session diligent-ai-claude-x", "session diligent-x-dev", "session other")),
        )
        assertNull(AiScripts.parseList(listOf("notmux")))
    }

    @Test
    fun `records are read back for the same folder only`() {
        val text = "{\"project\":\"/p/a\\\"b\",\"commit\":\"$commit\",\"started\":42}"
        assertEquals(AiSessionRecord("/p/a\"b", commit, 42L), AiScripts.parseRecord(text, "/p/a\"b"))
        assertNull(AiScripts.parseRecord(text, "/p/other"))
        assertNull(AiScripts.parseRecord("{\"project\":\"/p\",\"commit\":\"HEAD\"}", "/p"))
    }
}
