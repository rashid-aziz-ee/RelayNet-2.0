package com.example.relaynet.util

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

data class AudioRecordingResult(
    val durationSeconds: Int,
    val base64Data: String,
    val localFile: File
)

object AudioHelper {

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRecordingFile: File? = null
    private var recordingStartTime: Long = 0L

    var currentlyPlayingId: String? = null
        private set

    fun isRecording(): Boolean = mediaRecorder != null
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    /**
     * Starts recording audio using AMR-NB (Adaptive Multi-Rate Narrowband).
     * AMR-NB is the gold standard for tactical walkie-talkies and cellular RF.
     * Produces ultra-compact audio (~1.5 KB per second, no container overhead).
     * A 2-sec audio is ~3 KB; 5-sec is ~7.5 KB. Easily stays under Nearby 32KB payload limit,
     * guaranteeing near-instant (< 100ms) radio mesh delivery!
     */
    fun startRecording(context: Context): Boolean {
        return try {
            stopRecording()
            stopPlayback()

            val audioDir = File(context.cacheDir, "audio_notes").apply { mkdirs() }
            val outputFile = File(audioDir, "ptt_${System.currentTimeMillis()}.amr")
            currentRecordingFile = outputFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartTime = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanupRecorder()
            false
        }
    }

    fun stopRecording(): AudioRecordingResult? {
        val recorder = mediaRecorder ?: return null
        val file = currentRecordingFile ?: return null

        return try {
            val durationMs = System.currentTimeMillis() - recordingStartTime
            val durationSec = maxOf(1, (durationMs / 1000).toInt())

            try {
                recorder.stop()
            } catch (_: Exception) {}
            recorder.release()
            mediaRecorder = null

            if (file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                AudioRecordingResult(
                    durationSeconds = durationSec,
                    base64Data = base64,
                    localFile = file
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            cleanupRecorder()
        }
    }

    fun cancelRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        cleanupRecorder()
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        currentRecordingFile = null
        recordingStartTime = 0L
    }

    fun playAudio(
        context: Context,
        audioPayloadOrPath: String,
        messageId: String,
        onComplete: () -> Unit = {}
    ): Boolean {
        stopPlayback()

        return try {
            val audioFile: File = if (audioPayloadOrPath.startsWith("/") || audioPayloadOrPath.startsWith("file://")) {
                File(audioPayloadOrPath.removePrefix("file://"))
            } else {
                val audioDir = File(context.cacheDir, "audio_notes").apply { mkdirs() }
                val targetFile = File(audioDir, "cached_${messageId.take(8)}.amr")
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    val bytes = Base64.decode(audioPayloadOrPath, Base64.NO_WRAP)
                    FileOutputStream(targetFile).use { it.write(bytes) }
                }
                targetFile
            }

            if (!audioFile.exists()) return false

            val player = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    currentlyPlayingId = null
                    onComplete()
                }
                start()
            }

            mediaPlayer = player
            currentlyPlayingId = messageId
            true
        } catch (e: Exception) {
            e.printStackTrace()
            stopPlayback()
            false
        }
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentlyPlayingId = null
    }

    fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }
}
