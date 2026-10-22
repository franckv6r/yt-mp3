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
import java.util.concurrent.CancellationException

/**
 * Decode le flux telecharge (webm/opus ou m4a/aac) via MediaCodec,
 * puis encode en MP3 avec jump3r (portage Java de LAME, aucun binaire natif).
 */
object AudioConverter {

    private data class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    fun toMp3(
        src: File,
        dst: File,
        bitrate: Int = 192,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ) {
        val pcm = decode(src, onProgress, shouldStop)
        encode(pcm, dst, bitrate, onProgress, shouldStop)
    }

    private fun decode(file: File, onProgress: (Int) -> Unit, shouldStop: () -> Boolean): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

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

        val chunks = ArrayList<ShortArray>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

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
                    if (floatPcm) {
                        val fb = buf.asFloatBuffer()
                        val sh = ShortArray(fb.remaining())
                        for (i in sh.indices) {
                            sh[i] = (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        chunks.add(sh)
                    } else {
                        val sb = buf.asShortBuffer()
                        val sh = ShortArray(sb.remaining())
                        sb.get(sh)
                        chunks.add(sh)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (durationUs > 0) {
                        onProgress((info.presentationTimeUs * 40 / durationUs).toInt().coerceIn(0, 40))
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        val total = chunks.sumOf { it.size }
        val pcm = ShortArray(total)
        var p = 0
        for (c in chunks) {
            System.arraycopy(c, 0, pcm, p, c.size)
            p += c.size
        }
        return Pcm(pcm, sampleRate, channels)
    }

    private fun encode(
        pcm: Pcm,
        out: File,
        bitrate: Int,
        onProgress: (Int) -> Unit,
        shouldStop: () -> Boolean
    ) {
        val lame = Lame()
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

        lame.setModules(ga, bs, presets, qupvt, qu, vbrTag, version, id3, mpg)
        bs.setModules(ga, mpg, version, vbrTag)
        id3.setModules(bs, version)
        presets.setModules(lame)
        qu.setModules(bs, reservoir, qupvt, takehiro)
        qupvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bs)
        takehiro.setModules(qupvt)
        vbrTag.setModules(lame, bs, version)
        mpg.setModules(intf, common)
        intf.setModules(vbrTag, common)

        val g: LameGlobalFlags = lame.lame_init()
        id3.id3tag_init(g)
        g.in_samplerate = pcm.sampleRate
        g.num_channels = pcm.channels
        g.brate = bitrate
        g.mode = if (pcm.channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        g.quality = 9
        g.VBR = VbrMode.vbr_off
        check(lame.lame_init_params(g) >= 0) { "lame_init_params a echoue" }

        FileOutputStream(out).use { fos ->
            val block = 1152
            val left = IntArray(block)
            val right = IntArray(block)
            val buf = ByteArray((1.25 * block + 7200).toInt())
            val frames = pcm.samples.size / pcm.channels

            var i = 0
            while (i < frames) {
                if (shouldStop()) throw CancellationException("arrêt")
                val n = minOf(block, frames - i)
                for (j in 0 until n) {
                    val base = (i + j) * pcm.channels
                    if (pcm.channels == 1) {
                        left[j] = pcm.samples[base].toInt() shl 16
                        right[j] = left[j]
                    } else {
                        left[j] = pcm.samples[base].toInt() shl 16
                        right[j] = pcm.samples[base + 1].toInt() shl 16
                    }
                }
                val w = lame.lame_encode_buffer_int(g, left, right, n, buf, 0, buf.size)
                if (w > 0) fos.write(buf, 0, w)
                i += n
                if (frames > 0) onProgress(40 + (60 * i / frames))
            }
            val w = lame.lame_encode_flush(g, buf, 0, buf.size)
            if (w > 0) fos.write(buf, 0, w)
        }
        lame.lame_close(g)
        onProgress(100)
    }
}
