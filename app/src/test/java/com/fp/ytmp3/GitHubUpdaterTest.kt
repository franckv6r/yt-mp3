package com.fp.ytmp3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdaterTest {
    @Test
    fun comparesSemanticTags() {
        assertTrue(GitHubUpdater.isNewer("v0.5.1", "0.4.9"))
        assertTrue(GitHubUpdater.isNewer("0.4.10", "0.4.9"))
        assertFalse(GitHubUpdater.isNewer("v0.4.0", "0.4"))
        assertFalse(GitHubUpdater.isNewer("0.4.9", "0.4.9"))
    }
}
