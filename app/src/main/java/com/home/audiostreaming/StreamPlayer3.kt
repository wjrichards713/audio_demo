package com.home.audiostreaming
import android.media.*
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.example.opuslib.utils.OpusDecoder
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class StreamPlayer3(
    private val socket: DatagramSocket,
    private val address: InetAddress,
    private val port: Int,
    var speakerType: String

)
{

    interface StreamPlayerListener {
        fun onStreamStart()
        fun onStreamStop()
        fun onLog(message: String)
        fun onError(error: String)
    }
    fun setVolume(channelID: String, volume: Float) {
        audioTrackMap[channelID]?.setVolume(volume)
    }
    private val SAMPLE_RATE = AudioStreamConstant.SAMPLE_RATE
    private val BUFFER_INTERVAL_MS = 40L // Buffer interval for sync
    private val HEADER_SIZE = AudioStreamConstant.HEADER_SIZE // Assume 12-byte RTP header

    private val audioTrackMap = ConcurrentHashMap<String, AudioTrack>()
    private val jitterBuffers = ConcurrentHashMap<String, ConcurrentLinkedQueue<PcmFrame>>()
    private val lastPlaybackTime = ConcurrentHashMap<String, Long>()
    private val processingThreads = ConcurrentHashMap<String, Thread>()
    private val opusDecoders = ConcurrentHashMap<String, OpusDecoder>()

    @Volatile
    private var keepPlaying = false
    private var playerThread: Thread? = null
    var listener: StreamPlayerListener? = null

    fun startPlaying() {
        if (playerThread == null || playerThread?.state == Thread.State.TERMINATED) {
            playerThread = Thread {
                listener?.onStreamStart()
                val keyAES = SecretKeySpec(Base64.decode(AudioStreamConstant.AES_256_ALGO_KEY, Base64.DEFAULT), "AES")

                keepPlaying = true
                val buffer = ByteArray(8192)

                startUnifiedAudioProcessing()
                try {
                    while (keepPlaying) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        processIncomingPacket(packet, keyAES)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    stopAllAudioTracks()
                    listener?.onStreamStop()
                }
            }.also { it.start() }
        }
    }
    fun stopPlaying() {
        Log.e("web_socket", "stopping player")

        keepPlaying = false
        playerThread?.interrupt() // Interrupt the thread to stop blocking operations
        playerThread = null
    }

    private fun processIncomingPacket(packet: DatagramPacket, keyAES: SecretKeySpec) {
        try {
            val rawJson = String(packet.data, 0, packet.length, Charsets.UTF_8)
            Log.d("StreamPlayer", "RX audio packet JSON: ${rawJson}")
            val jsonObj = JSONObject(rawJson)
            val channelID = jsonObj.optString("channel_id")
            if (jsonObj.has("type") && jsonObj.optString("type") == "audio") {
                val base64Decoded = Base64.decode(jsonObj.optString("data"), Base64.DEFAULT)
                val decryptedData = decryptAES(base64Decoded, keyAES)
                val pcmData = decodeOpus(channelID, decryptedData)

                if (pcmData.isNotEmpty()) {
                    val frame = PcmFrame(pcmData.size, 1, SAMPLE_RATE, pcmData, System.currentTimeMillis())
                    jitterBuffers.getOrPut(channelID) { ConcurrentLinkedQueue() }.add(frame)
                    //startChannelThread(channelID)
                }
            }
        } catch (e: Exception) {
            Log.e("StreamPlayer", "Packet processing error: ${e.message}")
        }
    }

    private fun startUnifiedAudioProcessing() {
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            while (keepPlaying) {
                val currentTime = SystemClock.elapsedRealtime()
                for (channelID in jitterBuffers.keys) {
                    playBufferedAudioMultiStream(channelID, currentTime)
                }
                Thread.sleep(10) // Small delay to keep CPU usage in check
            }
        }.apply { start() }
    }
    private fun playBufferedAudioMultiStream(channelID: String, syncTime: Long) {
        val queue = jitterBuffers[channelID] ?: return
        if (queue.isEmpty()) return

        val audioTrack = getOrCreateAudioTrack(channelID)
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play()
        }

        val frame = queue.poll() ?: return
        val stereoData = convertMonoToStereo(frame.pcmData)

        val timestamp = AudioTimestamp()
        val playbackTimestampNs = if (audioTrack.getTimestamp(timestamp)) {
            timestamp.nanoTime + BUFFER_INTERVAL_MS * 1_000_000
        } else {
            syncTime * 1_000_000
        }

        val byteBuffer = ByteBuffer.allocateDirect(stereoData.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asShortBuffer().put(stereoData)
        byteBuffer.position(0)

        audioTrack.write(byteBuffer, stereoData.size * 2, AudioTrack.WRITE_BLOCKING, playbackTimestampNs)
    }

    private val globalReferenceClock = SystemClock.elapsedRealtimeNanos()


    private fun playBufferedAudioMultiStream(channelID: String) {
        val queue = jitterBuffers[channelID] ?: return
        if (queue.isEmpty()) return

        val audioTrack = getOrCreateAudioTrack(channelID)
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play()
        }

        val frame = queue.poll() ?: return
        val sampleRate = SAMPLE_RATE
        val durationMs = (frame.pcmData.size.toDouble() / sampleRate) * 1000

        // 🕒 Use a Global Clock to Sync All Streams
        val currentTimeMs = (SystemClock.elapsedRealtimeNanos() - globalReferenceClock) / 1_000_000
        val lastTimeMs = lastPlaybackTime[channelID] ?: currentTimeMs
        val adjustedTimeMs = maxOf(lastTimeMs + durationMs.toLong(), currentTimeMs)

        val delayMs = adjustedTimeMs - currentTimeMs
        Log.e("AudioSync", "Channel: $channelID | Delay: $delayMs ms")

        val stereoData = convertMonoToStereo(frame.pcmData)

        val byteBuffer = ByteBuffer.allocateDirect(stereoData.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asShortBuffer().put(stereoData)
        byteBuffer.position(0)

        // 🔄 Synchronize with AudioTrack Timestamp
        val timestamp = AudioTimestamp()
        val playbackTimestampNs = if (audioTrack.getTimestamp(timestamp)) {
            timestamp.nanoTime + BUFFER_INTERVAL_MS * 1_000_000
        } else {
            Log.e("AudioTrack", "Timestamp fetch failed, using estimated timing")
            adjustedTimeMs * 1_000_000
        }

        synchronized(audioTrack) {
            val bytesWritten = audioTrack.write(byteBuffer, stereoData.size * 2, AudioTrack.WRITE_BLOCKING, playbackTimestampNs)
            if (bytesWritten > 0) {
                lastPlaybackTime[channelID] = adjustedTimeMs + durationMs.toLong()
            } else {
                Log.e("AudioTrack", "Failed to write audio for channel: $channelID")
            }
        }


    }

    private fun getOrCreateAudioTrack(channelID: String): AudioTrack {
        return audioTrackMap.getOrPut(channelID) {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 4

            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().apply {
                    play()
                }
        }
    }

    private fun decodeOpus(channelID: String, encodedData: ByteArray): ShortArray {
        val decoder = opusDecoders.getOrPut(channelID) {
            OpusDecoder().apply { init(SAMPLE_RATE, 1, 4800) }
        }
        val decodedData = ShortArray(4800)
        val size = decoder.decode(encodedData, decodedData)
        return if (size > 0) decodedData.copyOf(size) else ShortArray(0)
    }

    private fun decryptAES(encryptedData: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = encryptedData.copyOfRange(0, 12)
        val actualEncryptedData = encryptedData.copyOfRange(12, encryptedData.size)

        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(actualEncryptedData)
    }

    private fun convertMonoToStereo(monoData: ShortArray): ShortArray {
        val stereoData = ShortArray(monoData.size * 2)
        for (i in monoData.indices) {
            stereoData[i * 2] = monoData[i]
            stereoData[i * 2 + 1] = monoData[i]
        }
        return stereoData
    }

    fun stopAllAudioTracks() {
        audioTrackMap.values.forEach {
            it.stop()
            it.release()
        }
        audioTrackMap.clear()
    }

    data class PcmFrame(
        val numFrames: Int,
        val numChannels: Int,
        val sampleRate: Int,
        val pcmData: ShortArray,
        val timestamp: Long
    )
}
