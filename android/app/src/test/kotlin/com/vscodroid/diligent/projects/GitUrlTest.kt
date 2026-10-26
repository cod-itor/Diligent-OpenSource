package com.vscodroid.diligent.projects

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitUrlTest {

    @Test
    fun `common remotes are accepted`() {
        listOf(
            "https://github.com/user/shop.git",
            "https://github.com/user/shop",
            "  https://gitlab.com/group/sub/project  ",
            "http://192.168.1.5:3000/me/app.git",
            "ssh://git@github.com/user/shop.git",
            "git://example.org/repo",
            "git@github.com:user/shop.git",
            "git@bitbucket.org:team/app",
            "/sdcard/backup/shop.git",
        ).forEach { assertTrue(GitUrl.isValid(it), it) }
    }

    @Test
    fun `things that are not remotes are refused`() {
        listOf(
            "",
            "   ",
            "shop",
            "github.com",
            "https://github.com",
            "https://github.com/",
            "https://github.com/user/my shop",
            "clone this please",
            "--upload-pack=touch /tmp/x",
            "-c core.sshCommand=x repo.git",
            "ftp://example.org/repo",
        ).forEach { assertFalse(GitUrl.isValid(it), it) }
    }

    @Test
    fun `the folder name is the last path part without git`() {
        assertEquals("shop", GitUrl.folderName("https://github.com/user/shop.git"))
        assertEquals("shop", GitUrl.folderName("https://github.com/user/shop"))
        assertEquals("shop", GitUrl.folderName("https://github.com/user/shop/"))
        assertEquals("shop", GitUrl.folderName("https://github.com/user/shop.git/"))
        assertEquals("shop", GitUrl.folderName("git@github.com:user/shop.git"))
        assertEquals("shop", GitUrl.folderName("git@host:shop.git"))
        assertEquals("shop", GitUrl.folderName("ssh://git@github.com/user/shop.git"))
        assertEquals("project", GitUrl.folderName("https://gitlab.com/group/sub/project"))
        assertEquals("app", GitUrl.folderName("http://192.168.1.5:3000/me/app.git"))
        assertEquals("shop", GitUrl.folderName("https://example.org/user/shop.git?ref=main#readme"))
        assertEquals("shop", GitUrl.folderName("/sdcard/backup/shop.git"))
        assertEquals("my.site", GitUrl.folderName("https://github.com/user/my.site.git"))
    }

    @Test
    fun `no usable name gives null`() {
        assertNull(GitUrl.folderName("https://github.com"))
        assertNull(GitUrl.folderName("https://github.com/"))
        assertNull(GitUrl.folderName(""))
        assertNull(GitUrl.folderName(".git"))
        assertNull(GitUrl.folderName("https://host/user/.."))
        assertNull(GitUrl.folderName("https://host/user/-x"))
    }
}
