package com.example.core.media.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.Calendar

object MediaMuxerHelper {

    private const val TAG = "MediaMuxerHelper"

    /**
     * Multiplexes a video-only MP4/AVC file and an audio-only M4A/AAC file into a single
     * standardized MP4 container containing both synchronized video and audio tracks.
     * Uses Android platform native MediaExtractor and MediaMuxer without external binaries.
     */
    fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            Log.e(TAG, "Video file does not exist or is empty")
            return false
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Audio file does not exist or is empty")
            return false
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            var videoTrackIndex = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }
            if (videoTrackIndex < 0) {
                Log.e(TAG, "No video track found in ${videoFile.name}")
                return false
            }

            var audioTrackIndex = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found in ${audioFile.name}")
                return false
            }

            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)

            audioExtractor.selectTrack(audioTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // 1. Copy video samples
            while (true) {
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags

                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // 2. Copy audio samples
            while (true) {
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags

                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error multiplexing video and audio: ${e.message}", e)
            if (outputFile.exists()) outputFile.delete()
            false
        } finally {
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Generates a standard ID3v2.3 tag containing Title, Artist, Album, Year,
     * and embedded APIC Album Art (cover image) from the video thumbnail.
     */
    fun createId3v2Tag(
        title: String,
        artist: String,
        album: String = "YAS Browser Downloads",
        thumbnailUrl: String? = null
    ): ByteArray {
        val framesOut = ByteArrayOutputStream()

        // TIT2 (Title)
        writeTextFrame(framesOut, "TIT2", title)

        // TPE1 (Lead Artist / Creator)
        writeTextFrame(framesOut, "TPE1", artist)

        // TALB (Album)
        writeTextFrame(framesOut, "TALB", album)

        // TYER (Year)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR).toString()
        writeTextFrame(framesOut, "TYER", currentYear)

        // APIC (Attached Picture / Cover Art)
        if (!thumbnailUrl.isNullOrBlank()) {
            try {
                val imageBytes = downloadThumbnailBytes(thumbnailUrl)
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    writeApicFrame(framesOut, imageBytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not embed cover art in ID3 tag: ${e.message}")
            }
        }

        val frameBytes = framesOut.toByteArray()
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3 // ID3v2.3
        header[4] = 0 // Revision
        header[5] = 0 // Flags

        // Synchsafe integer (7 bits per byte)
        var size = frameBytes.size
        header[9] = (size and 0x7F).toByte()
        size = size shr 7
        header[8] = (size and 0x7F).toByte()
        size = size shr 7
        header[7] = (size and 0x7F).toByte()
        size = size shr 7
        header[6] = (size and 0x7F).toByte()

        val tagOut = ByteArrayOutputStream()
        tagOut.write(header)
        tagOut.write(frameBytes)
        return tagOut.toByteArray()
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val frameData = ByteArray(1 + textBytes.size)
        frameData[0] = 3 // UTF-8 encoding descriptor in ID3v2.3/v2.4
        System.arraycopy(textBytes, 0, frameData, 1, textBytes.size)

        // Frame header: 4 bytes ID, 4 bytes size, 2 bytes flags
        out.write(frameId.toByteArray(Charsets.ISO_8859_1))
        val len = frameData.size
        out.write((len shr 24 and 0xFF))
        out.write((len shr 16 and 0xFF))
        out.write((len shr 8 and 0xFF))
        out.write((len and 0xFF))
        out.write(0) // flags
        out.write(0)
        out.write(frameData)
    }

    private fun writeApicFrame(out: ByteArrayOutputStream, imageBytes: ByteArray) {
        val mimeType = "image/jpeg"
        val mimeBytes = mimeType.toByteArray(Charsets.ISO_8859_1)
        val picType = 3.toByte() // Cover (front)
        val descBytes = byteArrayOf(0) // Empty description + null terminator

        val frameDataOut = ByteArrayOutputStream()
        frameDataOut.write(0) // ISO-8859-1 encoding for MIME
        frameDataOut.write(mimeBytes)
        frameDataOut.write(0) // Null terminator for MIME
        frameDataOut.write(picType.toInt())
        frameDataOut.write(descBytes)
        frameDataOut.write(imageBytes)

        val frameData = frameDataOut.toByteArray()
        out.write("APIC".toByteArray(Charsets.ISO_8859_1))
        val len = frameData.size
        out.write((len shr 24 and 0xFF))
        out.write((len shr 16 and 0xFF))
        out.write((len shr 8 and 0xFF))
        out.write((len and 0xFF))
        out.write(0)
        out.write(0)
        out.write(frameData)
    }

    private fun downloadThumbnailBytes(urlStr: String): ByteArray? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.getInputStream().use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks if the device has a native hardware/software encoder for the specified MIME type.
     */
    fun hasEncoderForMime(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in list.codecInfos) {
            if (!codecInfo.isEncoder) continue
            for (type in codecInfo.supportedTypes) {
                if (type.equals(mime, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }
}
