package com.fp.ytmp3

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Decode le flux telecharge (webm/opus ou m4a/aac) via MediaCodec, puis
 * encode en MP3 avec jump3r. Le decodeur et LAME tournent en pipeline :
 * LAME encode un bloc pendant que MediaCodec decode le suivant, sans
 * conserver toute la piste PCM en memoire.
 */
object AudioConverter {

    private const val BLOCK_FRAMES = 1152
    private const val QUEUE_BLOCKS = 8
    private val END = ShortArray(0)

    fun toMp3(
        src: File,
        dst: File,
        bitrate: Int = 192,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(src.absolutePath)
        var track = -1
        var mime: String? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) {
                track = i
                mime = m
                break
            }
        }
        check(track >= 0) { "aucune piste audio dans le fichier" }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
        var encoder: Encoder? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (shouldStop()) throw CancellationException("arrêt")
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIdx = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            floatPcm = f.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val pcm = if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            ShortArray(fb.remaining()).also { sh ->
                                for (i in sh.indices) {
                                    sh[i] = (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                }
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            ShortArray(sb.remaining()).also { sb.get(it) }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (pcm.isNotEmpty()) {
                            if (encoder == null) {
                                val totalFrames = if (durationUs > 0) {
                                    (durationUs * sampleRate / 1_000_000).toInt()
                                } else 0
                                encoder = Encoder(
                                    dst, bitrate, sampleRate, channels, totalFrames,
                                    onProgress, shouldStop
                                ).also { it.start() }
                            }
                            encoder!!.feed(pcm)
                        }
                        if (durationUs > 0) {
                            onProgress((info.presentationTimeUs * 50 / durationUs).toInt().coerceIn(0, 50))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            val enc = encoder ?: throw IllegalStateException("aucune donnee audio decodee")
            enc.finish()
        } catch (e: Throwable) {
            encoder?.abort()
            throw e
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
    }

    /** Consommateur LAME sur un thread dedie, alimente par une file bornee. */
    private class Encoder(
        private val out: File,
        private val bitrate: Int,
        private val sampleRate: Int,
        private val channels: Int,
        private val totalFrames: Int,
        private val onProgress: (Int) -> Unit,
        private val shouldStop: () -> Boolean
    ) {
        private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_BLOCKS)
        private val done = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var stopped = false
        private val thread = Thread({ runEncoder() }, "yt-mp3-lame").apply { isDaemon = true }

        fun start() = thread.start()

        fun feed(pcm: ShortArray) {
            if (pcm.isEmpty()) return
            while (true) {
                failure.get()?.let { throw it }
                if (queue.offer(pcm, 100, TimeUnit.MILLISECONDS)) return
            }
        }

        fun finish() {
            while (!queue.offer(END, 100, TimeUnit.MILLISECONDS)) {
                failure.get()?.let { throw it }
                if (!thread.isAlive) break
            }
            if (!done.await(120, TimeUnit.SECONDS)) {
                abort()
                throw IllegalStateException("encodeur MP3 bloque")
            }
            failure.get()?.let { throw it }
        }

        fun abort() {
            stopped = true
            thread.interrupt()
            queue.clear()
        }

        private fun runEncoder() {
            var lame: Lame? = null
            var flags: LameGlobalFlags? = null
            var fos: FileOutputStream? = null
            try {
                val l = Lame()
                val ga = GainAnalysis()
                val bs = BitStream()
                val presets = Presets()
                val qupvt = QuantizePVT()
                val qu = Quantize()
                val vbrTag = VBRTag()
                val version = de.sciss.jump3r.mp3.Version()
                val id3 = ID3Tag()
                val reservoir = Reservoir()
                val takehiro = Takehiro()
                val mpg = MPGLib()
                val intf = Interface()
                val common = Common()
                l.setModules(ga, bs, presets, qupvt, qu, vbrTag, version, id3, mpg)
                bs.setModules(ga, mpg, version, vbrTag)
                id3.setModules(bs, version)
                presets.setModules(l)
                qu.setModules(bs, reservoir, qupvt, takehiro)
                qupvt.setModules(takehiro, reservoir, l.enc.psy)
                reservoir.setModules(bs)
                takehiro.setModules(qupvt)
                vbrTag.setModules(l, bs, version)
                mpg.setModules(intf, common)
                intf.setModules(vbrTag, common)
                val g = l.lame_init()
                id3.id3tag_init(g)
                g.in_samplerate = sampleRate
                g.num_channels = channels
                g.brate = bitrate
                g.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
                g.quality = 9
                g.VBR = VbrMode.vbr_off
                check(l.lame_init_params(g) >= 0) { "lame_init_params a echoue" }
                lame = l
                flags = g
                fos = FileOutputStream(out)

                val pending = ShortArray(BLOCK_FRAMES * channels)
                var pendingSamples = 0
                var encodedFrames = 0L
                val left = IntArray(BLOCK_FRAMES)
                val right = IntArray(BLOCK_FRAMES)
                val buf = ByteArray((1.25 * BLOCK_FRAMES + 7200).toInt())

                fun encodeFrames(frames: Int) {
                    for (j in 0 until frames) {
                        val base = j * channels
                        if (channels == 1) {
                            left[j] = pending[base].toInt() shl 16
                            right[j] = left[j]
                        } else {
                            left[j] = pending[base].toInt() shl 16
                            right[j] = pending[base + 1].toInt() shl 16
                        }
                    }
                    val w = l.lame_encode_buffer_int(g, left, right, frames, buf, 0, buf.size)
                    if (w > 0) fos.write(buf, 0, w)
                    encodedFrames += frames
                    if (totalFrames > 0) {
                        onProgress((50 + encodedFrames * 50 / totalFrames).toInt().coerceIn(50, 99))
                    }
                }

                while (true) {
                    if (shouldStop() || stopped) throw CancellationException("arrêt")
                    val block = queue.take()
                    if (block === END) break
                    var offset = 0
                    while (offset < block.size) {
                        val room = pending.size - pendingSamples
                        val n = minOf(room, block.size - offset)
                        System.arraycopy(block, offset, pending, pendingSamples, n)
                        pendingSamples += n
                        offset += n
                        if (pendingSamples == pending.size) {
                            encodeFrames(BLOCK_FRAMES)
                            pendingSamples = 0
                        }
                    }
                }
                if (pendingSamples > 0) {
                    val frames = pendingSamples / channels
                    if (frames > 0) encodeFrames(frames)
                }
                val w = l.lame_encode_flush(g, buf, 0, buf.size)
                if (w > 0) fos.write(buf, 0, w)
                onProgress(100)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                try { fos?.close() } catch (_: Exception) {}
                try { lame?.lame_close(flags) } catch (_: Exception) {}
                done.countDown()
            }
        }
    }
}
