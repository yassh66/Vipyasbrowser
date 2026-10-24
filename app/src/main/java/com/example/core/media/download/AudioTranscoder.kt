package com.example.core.media.download

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class TranscodeResult(
    val success: Boolean,
    val outputFile: File? = null,
    val errorMessage: String? = null
)

class AudioTranscoder {
    companion object {
        /**
         * Checks if the device has a hardware or software MP3 encoder.
         */
        fun isMp3EncoderAvailable(): Boolean {
            return try {
                val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
                codecList.codecInfos.any { info ->
                    info.isEncoder && info.supportedTypes.any { type ->
                        type.equals("audio/mpeg", ignoreCase = true) ||
                        type.equals("audio/mp3", ignoreCase = true)
                    }
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Validates whether a file is a genuine MP3 file.
         * Checks for ID3 tags ("ID3") or MPEG audio sync words (0xFF 0xFB, 0xFF 0xFA, 0xFF 0xF3, 0xFF 0xF2).
         */
        fun isValidMp3File(file: File): Boolean {
            if (!file.exists() || file.length() < 128) return false
            return try {
                FileInputStream(file).use { input ->
                    val header = ByteArray(10)
                    val read = input.read(header)
                    if (read < 4) return false

                    // Check for ID3v2 tag ("ID3")
                    if (header[0] == 'I'.code.toByte() &&
                        header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte()
                    ) {
                        return true
                    }

                    // Check for MPEG-1/2 Audio Layer III sync word (0xFF followed by 0xE0-0xFF)
                    val b0 = header[0].toInt() and 0xFF
                    val b1 = header[1].toInt() and 0xFF
                    if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                        val layer = (b1 ushr 1) and 0x03
                        if (layer == 1) return true // Layer III = MP3
                    }

                    // Search initial buffer for MPEG sync word
                    val scanBuffer = ByteArray(4096)
                    val scanRead = input.read(scanBuffer)
                    for (i in 0 until scanRead - 1) {
                        val s0 = scanBuffer[i].toInt() and 0xFF
                        val s1 = scanBuffer[i + 1].toInt() and 0xFF
                        if (s0 == 0xFF && (s1 and 0xE0) == 0xE0) {
                            val layer = (s1 ushr 1) and 0x03
                            if (layer == 1) return true
                        }
                    }

                    false
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Transcodes an input audio file (AAC/M4A/Opus/WAV) to genuine MP3 if an MP3 encoder is available.
         * If unavailable, reports the exact reason clearly without faking success.
         */
        fun transcodeToMp3(inputFile: File, outputFile: File, targetBitrateKbps: Int = 320): TranscodeResult {
            if (!inputFile.exists() || inputFile.length() == 0L) {
                return TranscodeResult(false, null, "Input audio file is empty or missing")
            }

            // If input is ALREADY a genuine MP3, validate and copy
            if (isValidMp3File(inputFile)) {
                inputFile.copyTo(outputFile, overwrite = true)
                return TranscodeResult(true, outputFile)
            }

            // Check encoder availability
            if (!isMp3EncoderAvailable()) {
                return TranscodeResult(
                    success = false,
                    outputFile = null,
                    errorMessage = "MP3 hardware/system encoder is unavailable on this device. Audio preserved in original high-quality format."
                )
            }

            // Perform transcoding using MediaExtractor + MediaCodec
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            var encoder: MediaCodec? = null

            return try {
                extractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
                var trackIndex = -1
                var inputFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        inputFormat = format
                        break
                    }
                }

                if (trackIndex == -1 || inputFormat == null) {
                    return TranscodeResult(false, null, "No audio track found in media file")
                }

                extractor.selectTrack(trackIndex)
                val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
                val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                decoder = MediaCodec.createDecoderByType(inputMime).apply {
                    configure(inputFormat, null, null, 0)
                    start()
                }

                val mp3Format = MediaFormat.createAudioFormat("audio/mpeg", sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrateKbps * 1000)
                }

                encoder = MediaCodec.createEncoderByType("audio/mpeg").apply {
                    configure(mp3Format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }

                val fos = FileOutputStream(outputFile)
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                while (!sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inputBufIndex = decoder.dequeueInputBuffer(10000L)
                        if (inputBufIndex >= 0) {
                            val inputBuf = decoder.getInputBuffer(inputBufIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val decOutIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000L)
                    if (decOutIndex >= 0) {
                        val pcmBuf = decoder.getOutputBuffer(decOutIndex)!!
                        val encInIndex = encoder.dequeueInputBuffer(10000L)
                        if (encInIndex >= 0) {
                            val encInBuf = encoder.getInputBuffer(encInIndex)!!
                            encInBuf.put(pcmBuf)
                            encoder.queueInputBuffer(encInIndex, 0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        decoder.releaseOutputBuffer(decOutIndex, false)
                    }

                    val encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000L)
                    if (encOutIndex >= 0) {
                        val mp3Buf = encoder.getOutputBuffer(encOutIndex)!!
                        val chunk = ByteArray(bufferInfo.size)
                        mp3Buf.get(chunk)
                        fos.write(chunk)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                        encoder.releaseOutputBuffer(encOutIndex, false)
                    }
                }

                fos.flush()
                fos.close()

                if (isValidMp3File(outputFile)) {
                    TranscodeResult(true, outputFile)
                } else {
                    TranscodeResult(false, null, "Transcoded output did not contain valid MP3 frames")
                }
            } catch (e: Exception) {
                outputFile.delete()
                TranscodeResult(false, null, "MP3 transcoding error: ${e.message}")
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                try { encoder?.stop() } catch (_: Exception) {}
                try { encoder?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
            }
        }
    }
}
