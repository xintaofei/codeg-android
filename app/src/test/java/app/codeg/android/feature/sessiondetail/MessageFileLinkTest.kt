package app.codeg.android.feature.sessiondetail

import org.junit.Assert.*
import org.junit.Test

class MessageFileLinkTest {
    @Test fun `absolute artifact links keep the server root and relative download path`() {
        assertEquals(WorkspaceFileLink("/work/codeg-android", "app/build/app-debug.apk"), workspaceFileLink(
            "/work/codeg-android/app/build/app-debug.apk", listOf("/work", "/work/codeg-android"),
        ))
        assertEquals(WorkspaceFileLink("/work", "build/a b+c.apk"), workspaceFileLink(
            "<file:///work/build/a%20b+c.apk>", listOf("/work"),
        ))
        assertEquals(WorkspaceFileLink("/work", "app.kt"), workspaceFileLink("/work/app.kt:12:3", listOf("/work")))
    }

    @Test fun `relative paths stay inside current workspace and windows roots are supported`() {
        assertEquals(WorkspaceFileLink("/work", "build/app.apk"), workspaceFileLink("./build/app.apk", listOf("/work")))
        assertEquals(WorkspaceFileLink("C:\\work", "build/app.apk"), workspaceFileLink("C:\\work\\build\\app.apk", listOf("C:\\work")))
        assertEquals(WorkspaceFileLink("C:\\work", "app.apk"), workspaceFileLink("file:///C:/work/app.apk", listOf("C:\\work")))
    }

    @Test fun `absolute downloads outside registered projects use the explicit file parent`() {
        assertEquals(WorkspaceFileLink("/Users/sofire/Downloads", "codeg-android-debug.apk"), workspaceFileLink(
            "/Users/sofire/Downloads/codeg-android-debug.apk", listOf("/Users/sofire/Work/AI/ai-xcode"),
        ))
        assertEquals(WorkspaceFileLink("/Users/sofire/Downloads", "a b+c.apk"), workspaceFileLink(
            "file:///Users/sofire/Downloads/a%20b+c.apk", emptyList(), currentRoot = null,
        ))
        assertEquals(WorkspaceFileLink("/work-other", "app.apk"), workspaceFileLink("/work-other/app.apk", listOf("/work")))
        assertEquals(WorkspaceFileLink("/", "app.apk"), workspaceFileLink("/app.apk", emptyList()))
        assertEquals(WorkspaceFileLink("C:/Downloads", "app.apk"), workspaceFileLink("C:\\Downloads\\app.apk", emptyList()))
        assertEquals(WorkspaceFileLink("C:/", "app.apk"), workspaceFileLink("file:///C:/app.apk", emptyList()))
    }

    @Test fun `untrusted schemes missing context and traversal are rejected`() {
        val roots = listOf("/work")
        listOf("../secret.apk", "%2e%2e/secret.apk", "/work/../secret.apk", "/Downloads/", "/", "C:/",
            "javascript:alert(1)", "content://downloads/12", "file://other/work/app.apk", "build/%00.apk", "https://example.test/app.apk")
            .forEach { assertNull(it, workspaceFileLink(it, roots)) }
        assertNull(workspaceFileLink("app.apk", emptyList()))
        assertNull(workspaceFileLink("app.apk", roots, currentRoot = null))
    }

    @Test fun `web APK detection ignores queries and keeps ordinary websites separate`() {
        assertTrue(isApkLink("https://example.test/app.APK?version=2#download"))
        assertFalse(isApkLink("https://example.test/help"))
        assertNull(messageWebUrl("javascript:alert(1)"))
        assertNull(messageWebUrl("https://user:password@example.test/app.apk"))
    }

    @Test fun `download names cannot write paths or control characters`() {
        assertEquals("app.apk", safeDownloadFilename("../../app.apk"))
        assertEquals("app.apk", safeDownloadFilename("C:\\build\\app.apk"))
        assertEquals("badname.apk", safeDownloadFilename("bad\r\n:name.apk"))
        assertEquals("download", safeDownloadFilename("..."))
        val unicode = safeDownloadFilename("构建😀".repeat(80) + ".apk")
        assertTrue(unicode.endsWith(".apk"))
        assertTrue(unicode.toByteArray(Charsets.UTF_8).size <= 180)
    }
}
