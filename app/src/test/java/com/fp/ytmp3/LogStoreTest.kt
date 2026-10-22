package com.fp.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LogStoreTest {

    @Test
    fun rotationKeepsFiveNewestFiles() {
        val dir = Files.createTempDirectory("ytmp3-log").toFile()
        try {
            File(dir, "job.log").writeText("new")
            File(dir, "job.1.log").writeText("one")
            File(dir, "job.2.log").writeText("two")
            File(dir, "job.3.log").writeText("three")
            File(dir, "job.4.log").writeText("four")

            LogStore.rotate(dir)

            assertEquals("new", File(dir, "job.1.log").readText())
            assertEquals("one", File(dir, "job.2.log").readText())
            assertEquals("two", File(dir, "job.3.log").readText())
            assertEquals("three", File(dir, "job.4.log").readText())
            assertFalse(File(dir, "job.5.log").exists())
            assertFalse(File(dir, "job.log").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
