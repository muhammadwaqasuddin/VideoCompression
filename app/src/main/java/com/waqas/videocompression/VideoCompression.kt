package com.waqas.videocompression

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer


class VideoCompressor(private val context: Context) {
    suspend fun compressVideo(
        inputUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {
        val inputPath = getPathFromUri(inputUri) ?: throw IllegalArgumentException("Invalid URI")
        val outputDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!outputDir.exists()) outputDir.mkdirs()

        val outputFile = File(outputDir, "compressed_${System.currentTimeMillis()}.mp4")
        val outputPath = outputFile.absolutePath

        try {
            val inputFile = File(inputPath)
            if (!inputFile.exists() || !inputFile.canRead()) {
                throw IllegalArgumentException("Input video file is not accessible")
            }

            val retriever = MediaMetadataRetriever()
            val videoInfo = try {
                retriever.setDataSource(inputPath)
                VideoInfo(
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0,
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0,
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0,
                    frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?.toFloatOrNull() ?: 30f,
                    bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull() ?: 0,
                    rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                )
            } finally {
                retriever.release()
            }

            if (videoInfo.width == 0 || videoInfo.height == 0 || videoInfo.duration == 0L) {
                throw IllegalArgumentException("Invalid video file or corrupted metadata")
            }
            compressWithMediaExtractor(inputPath, outputPath, videoInfo, onProgress)
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw RuntimeException("Compression failed - output file not created")
            }

            Uri.fromFile(outputFile)
        } catch (e: Exception) {
            try {
                if (outputFile.exists()) {
                    outputFile.delete()
                }
            } catch (cleanupException: Exception) {
                // Ignore cleanup errors
            }

            when (e) {
                is IllegalArgumentException -> throw e
                is IllegalStateException -> throw RuntimeException(
                    "Video compression failed: ${e.message}",
                    e
                )

                else -> throw RuntimeException(
                    "Unexpected error during compression: ${e.message}",
                    e
                )
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun compressWithMediaExtractor(
        inputPath: String,
        outputPath: String,
        videoInfo: VideoInfo,
        onProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var decoderSurface: Surface? = null

        try {
            extractor.setDataSource(inputPath)
            audioExtractor.setDataSource(inputPath)

            // Find video and audio tracks
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoInputFormat: MediaFormat? = null
            var audioInputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                when {
                    mime?.startsWith("video/") == true && videoTrackIndex == -1 -> {
                        videoTrackIndex = i
                        videoInputFormat = format
                    }

                    mime?.startsWith("audio/") == true && audioTrackIndex == -1 -> {
                        audioTrackIndex = i
                        audioInputFormat = format
                    }
                }
            }

            if (videoTrackIndex == -1 || videoInputFormat == null) {
                throw IllegalArgumentException("No video track found in input file")
            }

            extractor.selectTrack(videoTrackIndex)
            if (audioTrackIndex != -1) {
                audioExtractor.selectTrack(audioTrackIndex)
            }
            val targetWidth = if (videoInfo.width % 2 == 0) videoInfo.width else videoInfo.width - 1
            val targetHeight = if (videoInfo.height % 2 == 0) videoInfo.height else videoInfo.height - 1

            val targetBitrate = calculateTargetBitrate(videoInfo, targetWidth, targetHeight)

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // Add encoder profile and level for better compatibility
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)
            }
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Create encoder's input surface
            decoderSurface = encoder.createInputSurface()

            // Create and configure decoder with the encoder's input surface
            val mime =
                videoInputFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(videoInputFormat, decoderSurface, null, 0)

            // Start both codecs
            decoder.start()
            encoder.start()

            // Create muxer
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            muxer.setOrientationHint(videoInfo.rotation)

            var muxerStarted = false
            var videoOutputTrackIndex = -1
            var audioOutputTrackIndex = -1

            if (audioInputFormat != null) {
                audioOutputTrackIndex = muxer.addTrack(audioInputFormat)
            }

            // Process video and audio
            val decoderBufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var audioDone = audioTrackIndex == -1 // Mark audio as done if no audio track
            var frameCount = 0
            val totalFrames = ((videoInfo.duration / 1000f) * videoInfo.frameRate).toInt()

            val audioBuffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer

            while (!encoderDone || !audioDone) {
                // Feed input to decoder
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val presentationTime = extractor.sampleTime
                                decoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    presentationTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    } else {
                        Log.w("VideoCompressor", "Decoder input buffer was null for index $inputBufferIndex")
                    }
                }

                // Get output from decoder and render to encoder's surface
                if (!decoderDone) {
                    val outputBufferIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        // Render frame to surface (encoder input)
                        decoder.releaseOutputBuffer(outputBufferIndex, true)
                        frameCount++
                        if (totalFrames > 0) {
                            onProgress(frameCount.toFloat() / totalFrames)
                        }

                        if (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Get encoded output from encoder
                when (val encoderOutputIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet
                    }

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoOutputTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }

                    else -> {
                        if (encoderOutputIndex >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(encoderOutputIndex)
                            if (outputBuffer != null && muxerStarted) {
                                // Skip codec config buffers
                                if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    muxer.writeSampleData(
                                        videoOutputTrackIndex,
                                        outputBuffer,
                                        encoderBufferInfo
                                    )
                                }
                            }
                            encoder.releaseOutputBuffer(encoderOutputIndex, false)

                            if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                            }
                        }
                    }
                }

                // Process audio track (copy without re-encoding)
                if (!audioDone && audioTrackIndex != -1 && muxerStarted) {
                    audioBuffer.clear()
                    val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)

                    if (sampleSize < 0) {
                        audioDone = true
                    } else {
                        audioBufferInfo.offset = 0
                        audioBufferInfo.size = sampleSize
                        audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        audioBufferInfo.flags = audioExtractor.sampleFlags

                        muxer.writeSampleData(audioOutputTrackIndex, audioBuffer, audioBufferInfo)
                        audioExtractor.advance()
                    }
                }
            }

        } finally {
            // Clean up resources in proper order
            try {
                decoderSurface?.release()

                decoder?.let {
                    try {
                        it.stop()
                    } catch (e: Exception) {
                        Log.w("VideoCompressor", "Decoder stop failed: ${e.message}")
                    }
                    it.release()
                }

                encoder?.let {
                    try {
                        it.stop()
                    } catch (e: Exception) {
                        Log.w("VideoCompressor", "Encoder stop failed: ${e.message}")
                    }
                    it.release()
                }

                muxer?.let {
                    try {
                        if (it != null) it.stop()
                    } catch (e: IllegalStateException) {
                        Log.w("VideoCompressor", "MediaMuxer stop failed: ${e.message}")
                    }
                    it.release()
                }

                extractor.release()
                audioExtractor.release()
            } catch (e: Exception) {
                Log.e("VideoCompressor", "Error releasing resources", e)
            }
        }
    }

    /**
     * Converts a content URI to a local file path by copying the file to a temporary
     * location in the app's cache directory. This is necessary because MediaExtractor
     * requires a local file path to work.
     *
     * @param uri The content URI to convert.
     * @return The local file path of the temporary file, or null if an error occurred.
     */
    private fun getPathFromUri(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val tempFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
            tempFile.outputStream().use { output ->
                inputStream?.copyTo(output)
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e("VideoCompressor", "Error creating temp file from URI", e)
            null
        }
    }

    /* <<<<<<<<<<<<<<  ✨ Windsurf Command ⭐ >>>>>>>>>>>>>>>> */
    /**
     * Retrieves the size of the video file specified by the given URI.
     *
     * @param uri The URI of the video file whose size is to be determined.
     * @return The size of the video file in bytes, or 0 if an error occurs.
     * Logs an error if unable to retrieve the size.
     */

    /* <<<<<<<<<<  1f4ecd0c-2cc2-44e4-ab92-c7751a0be2d5  >>>>>>>>>>> */
    fun getVideoSize(uri: Uri): Long {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length
            } ?: 0
        } catch (e: Exception) {
            Log.e("VideoCompressor", "Error getting video size", e)
            0
        }
    }
    private fun calculateTargetBitrate(videoInfo: VideoInfo, targetWidth: Int, targetHeight: Int): Int {
        val originalPixels = videoInfo.width * videoInfo.height
        val targetPixels = targetWidth * targetHeight

        // Base bitrate calculation
        val baseBitrate = if (videoInfo.bitrate > 0 && videoInfo.bitrate < 100_000_000) {
            videoInfo.bitrate
        } else {
            (targetPixels * videoInfo.frameRate * 0.1f).toInt()
        }

        // Scale bitrate based on resolution change and apply compression ratio
        val scaleFactor = targetPixels.toFloat() / originalPixels.toFloat()
        val compressionRatio = 0.2f
        return (baseBitrate * scaleFactor * compressionRatio).toInt().coerceIn(500_000, 10_000_000)
    }
}

data class VideoInfo(
    val width: Int,
    val height: Int,
    val duration: Long,
    val frameRate: Float,
    val bitrate: Int,
    val rotation: Int
)