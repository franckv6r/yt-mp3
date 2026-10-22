package com.fp.ytmp3

import android.content.Context
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.nio.ByteBuffer

/**
 * Charge l'extracteur depuis un .dex, entierement en memoire.
 *
 * Android 14 interdit de charger un dex depuis un fichier inscriptible
 * ("Writable dex file ... is not allowed"). InMemoryDexClassLoader contourne
 * le probleme et permet quand meme la mise a jour a chaud.
 */
class PluginLoader(private val ctx: Context) {

    private val apiClass = "com.fp.ytmp3.plugin.ExtractorApi"
    private val stored = File(ctx.filesDir, "extractor.dex")

    private var dexBytes: ByteArray = loadBytes()
    private var loader: ClassLoader = makeLoader(dexBytes)

    private fun loadBytes(): ByteArray {
        if (stored.exists() && stored.length() > 1000) {
            return stored.readBytes()
        }
        return ctx.assets.open("extractor.dex").use { it.readBytes() }
    }

    private fun makeLoader(bytes: ByteArray): ClassLoader =
        InMemoryDexClassLoader(ByteBuffer.wrap(bytes), ctx.classLoader)

    private fun api() = loader.loadClass(apiClass)

    private fun unwrap(e: Throwable): Throwable =
        if (e is InvocationTargetException && e.cause != null) e.cause!! else e

    fun info(videoUrl: String): String {
        try {
            val m = api().getMethod("info", String::class.java)
            return m.invoke(null, videoUrl) as String
        } catch (e: Throwable) {
            throw unwrap(e)
        }
    }

    fun list(url: String): String {
        try {
            val m = api().getMethod("list", String::class.java)
            return m.invoke(null, url) as String
        } catch (e: Throwable) {
            throw unwrap(e)
        }
    }

    fun size(): Int = dexBytes.size

    /** Telecharge un nouveau .dex, verifie qu'il se charge, puis le remplace. */
    fun updateFrom(url: String): String {
        val bytes = URL(url).openStream().use { it.readBytes() }
        if (bytes.size < 1000) {
            throw IllegalStateException("fichier trop petit, ce n'est pas un .dex")
        }
        val test = makeLoader(bytes)
        test.loadClass(apiClass) // leve une exception si le dex est invalide

        dexBytes = bytes
        loader = test
        stored.writeBytes(bytes)
        return "extracteur mis a jour (${bytes.size} octets)"
    }
}
