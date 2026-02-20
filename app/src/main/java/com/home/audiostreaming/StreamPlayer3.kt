package com.home.audiostreaming

import android.media.*
import android.util.Base64
import android.util.Log
import com.example.opuslib.utils.OpusDecoder
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
) {

    companion object {
        private const val TAG = "SP3"
        private const val OPUS_MAX_FRAME_SIZE = 4800   // 100ms at 48kHz mono (confirmed from logs)
        private const val JITTER_BUFFER_MIN_FRAMES = 3 // Wait for this many before starting playback
        private const val CHANNEL_IDLE_TIMEOUT_MS = 500L
        private const val MAX_QUEUE_SIZE = 20
    }

    interface StreamPlayerListener {
        fun onStreamStart()
        fun onStreamStop()
        fun onLog(message: String)
        fun onError(error: String)
    }

    private val SAMPLE_RATE = AudioStreamConstant.SAMPLE_RATE

    // Per-channel state (receive side — same as working single-room code)
    private val jitterBuffers = ConcurrentHashMap<String, ConcurrentLinkedQueue<PcmFrame>>()
    private val opusDecoders = ConcurrentHashMap<String, OpusDecoder>()
    private val channelVolumes = ConcurrentHashMap<String, Float>()
    private val channelLastReceiveTime = ConcurrentHashMap<String, Long>()
    private val channelPlaying = ConcurrentHashMap<String, Boolean>()

    // Single mixed output AudioTrack (replaces per-channel audioTrackMap)
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var keepPlaying = false
    private var receiverThread: Thread? = null
    private var mixerThread: Thread? = null
    var listener: StreamPlayerListener? = null

    // Diagnostics
    private var totalPackets = 0L
    private var totalDecodeErrors = 0L
    private var mixCycleCount = 0L

    fun setVolume(channelID: String, volume: Float) {
        channelVolumes[channelID] = volume.coerceIn(0f, 1.0f)
        Log.d(TAG, "setVolume channel=$channelID volume=$volume")
    }

    fun startPlaying() {
        if (keepPlaying) return
        if (receiverThread != null && receiverThread?.state != Thread.State.TERMINATED) return

        keepPlaying = true
        totalPackets = 0
        totalDecodeErrors = 0
        mixCycleCount = 0

        // Create single output AudioTrack
        try {
            audioTrack = createAudioTrack()
            audioTrack?.play()
            Log.d(TAG, "startPlaying: rate=$SAMPLE_RATE socket.localPort=${socket.localPort} trackState=${audioTrack?.playState}")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack creation failed: ${e.message}", e)
            keepPlaying = false
            listener?.onError("AudioTrack creation failed: ${e.message}")
            return
        }

        // Receiver thread: UDP -> decrypt -> Opus decode -> jitter buffer
        // (identical pipeline to working single-room code)
        receiverThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            listener?.onStreamStart()
            Log.d(TAG, "Receiver thread started")

            val keyAES = SecretKeySpec(
                Base64.decode(AudioStreamConstant.AES_256_ALGO_KEY, Base64.DEFAULT), "AES"
            )
            val buffer = ByteArray(8192)

            try {
                while (keepPlaying) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    processIncomingPacket(packet, keyAES)
                }
            } catch (e: Exception) {
                if (keepPlaying) Log.e(TAG, "Receiver error: ${e.message}", e)
            }
            Log.d(TAG, "Receiver exiting: packets=$totalPackets errors=$totalDecodeErrors")
        }, "AudioReceiver").also { it.start() }

        // Mixer thread: drain all jitter buffers -> software mix -> single AudioTrack
        mixerThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            Log.d(TAG, "Mixer thread started")
            runMixerLoop()
            Log.d(TAG, "Mixer exiting: mixCycles=$mixCycleCount")
        }, "AudioMixer").also { it.start() }
    }

    fun stopPlaying() {
        Log.d(TAG, "stopPlaying: packets=$totalPackets errors=$totalDecodeErrors channels=${jitterBuffers.keys}")
        keepPlaying = false

        receiverThread?.interrupt()
        receiverThread = null
        mixerThread?.interrupt()
        mixerThread = null

        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioTrack = null

        opusDecoders.values.forEach { try { it.close() } catch (_: Exception) {} }
        opusDecoders.clear()
        jitterBuffers.clear()
        channelPlaying.clear()
        channelLastReceiveTime.clear()
        channelVolumes.clear()

        listener?.onStreamStop()
    }

    // =========================================================================
    // Receiver: UDP packet -> decrypt -> Opus decode -> per-channel jitter buffer
    // (identical to working single-room code)
    // =========================================================================

    private fun processIncomingPacket(packet: DatagramPacket, keyAES: SecretKeySpec) {
        try {
            val jsonObj = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
            if (jsonObj.optString("type") != "audio") return

            val channelID = jsonObj.optString("channel_id")
            if (channelID.isEmpty()) return

            totalPackets++
            val base64Decoded = Base64.decode(jsonObj.optString("data"), Base64.DEFAULT)
            val decryptedData = decryptAES(base64Decoded, keyAES)
            val pcmData = decodeOpus(channelID, decryptedData)

            if (pcmData.isNotEmpty()) {
                val frame = PcmFrame(pcmData.size, 1, SAMPLE_RATE, pcmData, System.currentTimeMillis())
                val queue = jitterBuffers.getOrPut(channelID) { ConcurrentLinkedQueue() }
                queue.add(frame)
                channelLastReceiveTime[channelID] = System.currentTimeMillis()

                // Cap buffer to prevent memory growth
                while (queue.size > MAX_QUEUE_SIZE) queue.poll()

                if (totalPackets % 50 == 1L) {
                    Log.d(TAG, "RX pkt#$totalPackets ch=$channelID encSize=${base64Decoded.size} pcmSamples=${pcmData.size} queueSize=${queue.size}")
                }
            } else {
                totalDecodeErrors++
                if (totalDecodeErrors <= 10) {
                    Log.w(TAG, "Opus decode EMPTY ch=$channelID encSize=${base64Decoded.size} decryptSize=${decryptedData.size}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Packet error: ${e.message}")
        }
    }

    // =========================================================================
    // Mixer: poll one frame from each active channel, sum into one buffer,
    //        write to single AudioTrack. WRITE_BLOCKING paces the loop.
    // =========================================================================

    private fun runMixerLoop() {
        // Stereo output: max 4800 mono samples -> 9600 stereo shorts
        val maxStereo = OPUS_MAX_FRAME_SIZE * 2
        val mixBuffer = IntArray(maxStereo)       // int accumulator to avoid clipping during sum
        val outputShorts = ShortArray(maxStereo)   // final 16-bit output

        while (keepPlaying) {
            mixBuffer.fill(0)
            var activeChannels = 0
            var frameSamples = 0  // actual mono samples this cycle (from decoded frames)
            val now = System.currentTimeMillis()

            for (channelID in jitterBuffers.keys.toList()) {
                val queue = jitterBuffers[channelID] ?: continue

                // Initial jitter gate: only applies on FIRST connect for this channel.
                // Once open, stays open — brief gaps just produce silence naturally.
                val isPlaying = channelPlaying[channelID] ?: false
                if (!isPlaying) {
                    if (queue.size >= JITTER_BUFFER_MIN_FRAMES) {
                        channelPlaying[channelID] = true
                        Log.d(TAG, "Jitter gate OPEN ch=$channelID buffered=${queue.size}")
                    } else {
                        continue
                    }
                }

                // No frame available — skip this channel (silence), don't re-gate
                val frame = queue.poll() ?: continue

                val volume = channelVolumes[channelID] ?: 1.0f
                val samplesToMix = frame.pcmData.size
                frameSamples = maxOf(frameSamples, samplesToMix)

                // Sum mono PCM into stereo int accumulator
                for (i in 0 until samplesToMix) {
                    val sample = (frame.pcmData[i] * volume).toInt()
                    mixBuffer[i * 2] += sample       // Left
                    mixBuffer[i * 2 + 1] += sample   // Right
                }
                activeChannels++
            }

            if (frameSamples == 0) {
                // No audio data from any channel — brief sleep to avoid busy loop
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }

            // Clamp int accumulator to 16-bit range
            val stereoSamples = frameSamples * 2
            for (i in 0 until stereoSamples) {
                outputShorts[i] = mixBuffer[i].coerceIn(
                    Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                ).toShort()
            }

            // Write to single AudioTrack — WRITE_BLOCKING paces the loop
            // at exact hardware playback rate (~100ms per 4800-sample frame)
            val track = audioTrack
            if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val written = track.write(outputShorts, 0, stereoSamples)

                mixCycleCount++
                if (mixCycleCount % 50 == 1L) {
                    val queueSizes = jitterBuffers.entries.joinToString { "${it.key}=${it.value.size}" }
                    Log.d(TAG, "MIX #$mixCycleCount active=$activeChannels frameSamples=$frameSamples written=$written queues=[$queueSizes]")
                }
            } else {
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
        }
    }

    // =========================================================================
    // AudioTrack creation — single track for all mixed output
    // =========================================================================

    private fun createAudioTrack(): AudioTrack {
        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        // Buffer for 4 frames: 4800 samples * 2 channels * 2 bytes/sample * 4 frames
        val bufferSize = maxOf(minBufSize * 4, OPUS_MAX_FRAME_SIZE * 2 * 2 * 4)

        Log.d(TAG, "createAudioTrack: rate=$SAMPLE_RATE stereo 16bit minBuf=$minBufSize actualBuf=$bufferSize")

        return AudioTrack.Builder()
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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // =========================================================================
    // Opus decode (identical to working single-room code)
    // =========================================================================

    private fun decodeOpus(channelID: String, encodedData: ByteArray): ShortArray {
        val isNew = !opusDecoders.containsKey(channelID)
        val decoder = opusDecoders.getOrPut(channelID) {
            OpusDecoder().apply { init(SAMPLE_RATE, 1, OPUS_MAX_FRAME_SIZE) }
        }
        if (isNew) {
            Log.d(TAG, "NEW OpusDecoder ch=$channelID rate=$SAMPLE_RATE channels=1 maxFrame=$OPUS_MAX_FRAME_SIZE")
        }
        val decodedData = ShortArray(OPUS_MAX_FRAME_SIZE)
        val size = decoder.decode(encodedData, decodedData)
        if (size <= 0 && totalDecodeErrors < 10) {
            Log.w(TAG, "Opus decode failed ch=$channelID encodedSize=${encodedData.size} returned=$size")
        }
        return if (size > 0) decodedData.copyOf(size) else ShortArray(0)
    }

    // =========================================================================
    // AES decryption (identical to working single-room code)
    // =========================================================================

    private fun decryptAES(encryptedData: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = encryptedData.copyOfRange(0, 12)
        val actualEncryptedData = encryptedData.copyOfRange(12, encryptedData.size)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(actualEncryptedData)
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    fun stopAllAudioTracks() {
        stopPlaying()
    }

    // =========================================================================
    // Data class
    // =========================================================================

    data class PcmFrame(
        val numFrames: Int,
        val numChannels: Int,
        val sampleRate: Int,
        val pcmData: ShortArray,
        val timestamp: Long
    )
}
