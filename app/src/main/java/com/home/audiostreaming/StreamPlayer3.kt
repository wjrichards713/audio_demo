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
        private const val OPUS_MAX_FRAME_SIZE = 4800   // 100ms at 48kHz mono (for Opus decoder)
        private const val MIXER_FRAME_SIZE = 1920      // 40ms at 48kHz — fixed output per mix cycle
        private const val JITTER_BUFFER_MIN_FRAMES = 5 // Wait for this many before starting playback
        private const val FADE_SAMPLES = 64            // Crossfade length (~1.3ms at 48kHz)
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
    private val channelSpeakerTypes = ConcurrentHashMap<String, String>()
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

    fun setSpeakerType(channelID: String, type: String) {
        channelSpeakerTypes[channelID] = type
        Log.d(TAG, "setSpeakerType channel=$channelID type=$type")
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
        channelSpeakerTypes.clear()

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
        // Fixed stereo output: MIXER_FRAME_SIZE mono samples -> MIXER_FRAME_SIZE*2 stereo shorts
        val stereoSamples = MIXER_FRAME_SIZE * 2
        val mixBuffer = IntArray(stereoSamples)       // int accumulator to avoid clipping during sum
        val outputShorts = ShortArray(stereoSamples)   // final 16-bit output

        // Per-channel accumulation buffer: decoded PCM is accumulated here until
        // we have a full MIXER_FRAME_SIZE worth. This eliminates partial-frame
        // discontinuities when frame sizes don't divide evenly into MIXER_FRAME_SIZE
        // (e.g. 4800-sample frames / 1920 mixer = 2.5 — the old remainder approach
        // would produce a 960-sample partial frame every 3rd cycle = crackling).
        val accBuffers = HashMap<String, ShortArray>()
        val accCounts = HashMap<String, Int>()

        // Per-channel crossfade state: prevents clicks when a channel briefly
        // has no data. Tracks whether channel produced a full frame last cycle
        // and the last sample value for smooth fade-out/fade-in.
        val channelHadData = HashMap<String, Boolean>()
        val channelLastSample = HashMap<String, Int>()
        var underflowCount = 0L

        while (keepPlaying) {
            mixBuffer.fill(0)
            var activeChannels = 0

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

                // Phase 1: Accumulate decoded PCM from queue into per-channel buffer
                val accBuf = accBuffers.getOrPut(channelID) { ShortArray(OPUS_MAX_FRAME_SIZE * 2) }
                var accCount = accCounts[channelID] ?: 0

                while (accCount < MIXER_FRAME_SIZE) {
                    val frame = queue.poll() ?: break
                    frame.pcmData.copyInto(accBuf, accCount)
                    accCount += frame.pcmData.size
                }

                // Phase 2: Mix only if we have a full MIXER_FRAME_SIZE worth of data.
                // This guarantees every channel always contributes exactly 1920 or 0
                // samples per cycle — no partial frames, no mid-frame discontinuities.
                val volume = channelVolumes[channelID] ?: 1.0f
                val spkType = channelSpeakerTypes[channelID] ?: "center"
                val hadData = channelHadData[channelID] ?: false

                if (accCount >= MIXER_FRAME_SIZE) {
                    val needFadeIn = !hadData
                    var lastSample = 0

                    for (i in 0 until MIXER_FRAME_SIZE) {
                        var sample = (accBuf[i] * volume).toInt()
                        if (needFadeIn && i < FADE_SAMPLES) {
                            sample = sample * i / FADE_SAMPLES
                        }
                        lastSample = sample
                        when (spkType) {
                            "left"  -> mixBuffer[i * 2] += sample
                            "right" -> mixBuffer[i * 2 + 1] += sample
                            else -> {
                                mixBuffer[i * 2] += sample
                                mixBuffer[i * 2 + 1] += sample
                            }
                        }
                    }

                    // Shift remaining data to front of buffer
                    val remaining = accCount - MIXER_FRAME_SIZE
                    if (remaining > 0) {
                        System.arraycopy(accBuf, MIXER_FRAME_SIZE, accBuf, 0, remaining)
                    }
                    accCount = remaining

                    channelHadData[channelID] = true
                    channelLastSample[channelID] = lastSample
                    activeChannels++
                } else if (hadData) {
                    // Channel just went from active to not-enough-data.
                    // Generate fade-out to prevent click from abrupt silence.
                    val lastSample = channelLastSample[channelID] ?: 0
                    if (lastSample != 0) {
                        for (i in 0 until FADE_SAMPLES) {
                            val fade = lastSample * (FADE_SAMPLES - 1 - i) / FADE_SAMPLES
                            when (spkType) {
                                "left"  -> mixBuffer[i * 2] += fade
                                "right" -> mixBuffer[i * 2 + 1] += fade
                                else -> {
                                    mixBuffer[i * 2] += fade
                                    mixBuffer[i * 2 + 1] += fade
                                }
                            }
                        }
                        activeChannels++  // count as active during fade-out
                    }
                    underflowCount++
                    Log.w(TAG, "UNDERFLOW ch=$channelID #$underflowCount lastSample=$lastSample accCount=$accCount")
                    channelHadData[channelID] = false
                    channelLastSample[channelID] = 0
                }

                accCounts[channelID] = accCount
            }

            if (activeChannels == 0) {
                // No audio data from any channel — brief sleep to avoid busy loop
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }

            // Peak limiter: find max absolute value, normalize if it would clip
            var peak = 0
            for (i in 0 until stereoSamples) {
                val v = mixBuffer[i]
                val abs = if (v >= 0) v else -v
                if (abs > peak) peak = abs
            }

            if (peak > Short.MAX_VALUE) {
                // Scale all samples proportionally to prevent clipping
                val scale = Short.MAX_VALUE.toFloat() / peak
                for (i in 0 until stereoSamples) {
                    outputShorts[i] = (mixBuffer[i] * scale).toInt().toShort()
                }
            } else {
                for (i in 0 until stereoSamples) {
                    outputShorts[i] = mixBuffer[i].toShort()
                }
            }

            // Write FIXED-size frame to AudioTrack — WRITE_BLOCKING paces at 40ms
            val track = audioTrack
            if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val written = track.write(outputShorts, 0, stereoSamples)

                mixCycleCount++
                if (mixCycleCount % 50 == 1L) {
                    val queueSizes = jitterBuffers.entries.joinToString { "${it.key}=${it.value.size}" }
                    val clipped = if (peak > Short.MAX_VALUE) "CLIPPED peak=$peak" else "peak=$peak"
                    val underruns = try { track.underrunCount } catch (_: Exception) { -1 }
                    Log.d(TAG, "MIX #$mixCycleCount active=$activeChannels written=$written $clipped underruns=$underruns underflows=$underflowCount queues=[$queueSizes]")
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
        // Buffer for 8 mixer frames: 1920 samples * 2 channels * 2 bytes/sample * 8 frames
        val bufferSize = maxOf(minBufSize * 4, MIXER_FRAME_SIZE * 2 * 2 * 8)

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
