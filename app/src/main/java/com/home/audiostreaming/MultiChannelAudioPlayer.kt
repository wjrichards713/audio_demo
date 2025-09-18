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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MultiChannelAudioPlayer(
    private val socket: DatagramSocket,
    private val address: InetAddress,
    private val port: Int
) {
    
    interface MultiChannelAudioListener {
        fun onChannelStarted(channelId: String)
        fun onChannelStopped(channelId: String)
        fun onLog(message: String)
        fun onError(error: String)
    }
    
    // Audio constants - 40ms frame alignment to match server
    private val SAMPLE_RATE = AudioStreamConstant.SAMPLE_RATE
    private val CHANNELS = 1
    private val FRAME_DURATION_MS = 40L
    private val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_DURATION_MS / 1000).toInt() // 1920 samples @ 48kHz
    private val FRAMES_PER_WRITE = 1 // Write 1 frame (40ms) to reduce underruns
    private val WRITE_SIZE_SAMPLES = SAMPLES_PER_FRAME * FRAMES_PER_WRITE // 1920 samples
    private val WRITE_SIZE_STEREO_SAMPLES = WRITE_SIZE_SAMPLES * 2 // 3840 samples (interleaved stereo)
    private val JITTER_BUFFER_SIZE = 5 // Smaller buffer for lower latency
    
    // Pre-allocated buffers for allocation-free mixing
    private val mixBuffer = FloatArray(WRITE_SIZE_SAMPLES)
    private val stereoBuffer = ShortArray(WRITE_SIZE_STEREO_SAMPLES)
    private val silenceFrame = ShortArray(SAMPLES_PER_FRAME)
    
    // Channel management
    private val activeChannels = ConcurrentHashMap<String, ChannelData>()
    private val jitterBuffers = ConcurrentHashMap<String, ConcurrentLinkedQueue<AudioFrame>>()
    private val opusDecoders = ConcurrentHashMap<String, OpusDecoder>()
    
    // Threading and control
    private val isPlaying = AtomicBoolean(false)
    private var packetReceiverThread: Thread? = null
    private var mixerThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    
    var listener: MultiChannelAudioListener? = null
    
    data class ChannelData(
        val channelId: String,
        var volume: Float = 1.0f,
        var isActive: Boolean = false,
        var lastPacketTime: Long = 0L,
        var packetsReceived: Long = 0L,
        var packetsDropped: Long = 0L
    )
    
    data class AudioFrame(
        val pcmData: ShortArray,
        val timestamp: Long,
        val sequenceNumber: Long,
        val channelId: String
    )
    
    fun start() {
        if (isPlaying.get()) return
        
        isPlaying.set(true)
        createSharedAudioTrack()
        startPacketReceiver()
        startMixerThread()
        listener?.onLog("Multi-channel audio player started with real-time mixer")
    }
    
    fun stop() {
        isPlaying.set(false)
        packetReceiverThread?.interrupt()
        mixerThread?.interrupt()
        
        audioTrack?.let { track ->
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e("MultiChannelAudio", "Error stopping audio track: ${e.message}")
            }
        }
        audioTrack = null
        
        jitterBuffers.clear()
        opusDecoders.clear()
        activeChannels.clear()
        
        listener?.onLog("Multi-channel audio player stopped")
    }
    
    fun setChannelVolume(channelId: String, volume: Float) {
        activeChannels[channelId]?.volume = volume.coerceIn(0f, 1f)
    }
    
    fun addChannel(channelId: String, volume: Float = 1.0f) {
        if (activeChannels.containsKey(channelId)) {
            Log.w("MultiChannelAudio", "⚠️  Channel $channelId already exists")
            return
        }
        
        Log.i("MultiChannelAudio", "➕ Adding channel -> ID: $channelId, Volume: ${(volume * 100).toInt()}%")
        
        val channelData = ChannelData(channelId, volume, true)
        activeChannels[channelId] = channelData
        jitterBuffers[channelId] = ConcurrentLinkedQueue()
        
        val decoder = OpusDecoder()
        val initSuccess = decoder.init(SAMPLE_RATE, CHANNELS, SAMPLES_PER_FRAME)
        opusDecoders[channelId] = decoder
        
        Log.d("MultiChannelAudio", "🔧 Opus decoder init -> Channel: $channelId, Success: $initSuccess, FrameSize: $SAMPLES_PER_FRAME")
        
        Log.i("MultiChannelAudio", "✅ Channel $channelId added and activated successfully")
        listener?.onChannelStarted(channelId)
        listener?.onLog("Channel $channelId added and activated")
    }
    
    fun removeChannel(channelId: String) {
        activeChannels[channelId]?.isActive = false
        
        jitterBuffers.remove(channelId)
        opusDecoders.remove(channelId)
        activeChannels.remove(channelId)
        
        listener?.onChannelStopped(channelId)
        listener?.onLog("Channel $channelId removed")
    }
    
    fun submitFrame(channelId: String, pcm: ShortArray) {
        val channelData = activeChannels[channelId] ?: return
        val jitterBuffer = jitterBuffers[channelId] ?: return
        
        if (!channelData.isActive) return
        
        // Ensure frame is exactly the right size
        val frameData = if (pcm.size == SAMPLES_PER_FRAME) {
            pcm
        } else if (pcm.size > SAMPLES_PER_FRAME) {
            pcm.copyOf(SAMPLES_PER_FRAME)
        } else {
            // Pad with silence if too short
            val padded = ShortArray(SAMPLES_PER_FRAME)
            pcm.copyInto(padded)
            padded
        }
        
        val frame = AudioFrame(
            pcmData = frameData,
            timestamp = SystemClock.elapsedRealtimeNanos(),
            sequenceNumber = channelData.packetsReceived,
            channelId = channelId
        )
        
        jitterBuffer.offer(frame)
        
        // Manage buffer size for low latency
        while (jitterBuffer.size > JITTER_BUFFER_SIZE) {
            jitterBuffer.poll()
            channelData.packetsDropped++
            Log.w("MultiChannelAudio", "🗑️  Dropped frame for channel $channelId (buffer full)")
        }
        
        channelData.packetsReceived++
        channelData.lastPacketTime = System.currentTimeMillis()
        
        Log.v("MultiChannelAudio", "✅ Frame submitted -> Channel: $channelId, Buffer: ${jitterBuffer.size}")
    }
    
    private fun createSharedAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 4
        
        audioTrack = AudioTrack.Builder()
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
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        audioTrack?.play()
        Log.i("MultiChannelAudio", "🎵 Shared AudioTrack created and started")
    }
    
    private fun startPacketReceiver() {
        packetReceiverThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            val buffer = ByteArray(8192)
            val keyAES = SecretKeySpec(Base64.decode(AudioStreamConstant.AES_256_ALGO_KEY, Base64.DEFAULT), "AES")
            
            try {
                while (isPlaying.get() && !Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    processIncomingPacket(packet, keyAES)
                }
            } catch (e: Exception) {
                if (isPlaying.get()) {
                    listener?.onError("Packet receiver error: ${e.message}")
                    Log.e("MultiChannelAudio", "Packet receiver error", e)
                }
            }
        }.apply { start() }
    }
    
    private fun processIncomingPacket(packet: DatagramPacket, keyAES: SecretKeySpec) {
        try {
            val rawJson = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val jsonObj = JSONObject(rawJson)
            
            Log.d("MultiChannelAudio", "📦 RX UDP Packet -> Size: ${packet.length} bytes, From: ${packet.address}:${packet.port}")
            Log.d("MultiChannelAudio", "📋 Packet JSON: $rawJson")
            
            if (jsonObj.has("type") && jsonObj.optString("type") == "audio") {
                val channelId = jsonObj.optString("channel_id")
                val base64Data = jsonObj.optString("data")
                
                Log.i("MultiChannelAudio", "🎵 AUDIO PACKET -> Channel: $channelId, DataSize: ${base64Data.length} chars")
                
                val channelData = activeChannels[channelId]
                if (channelData?.isActive != true) {
                    Log.w("MultiChannelAudio", "⚠️  Channel $channelId not active, dropping packet. Active channels: ${activeChannels.keys}")
                    return
                }
                
                if (base64Data.isNotEmpty()) {
                    val encryptedData = Base64.decode(base64Data, Base64.DEFAULT)
                    val decryptedData = decryptAES(encryptedData, keyAES)
                    val pcmData = decodeOpus(channelId, decryptedData)
                    
                    Log.d("MultiChannelAudio", "🔓 Decoded -> Encrypted: ${encryptedData.size} bytes, Decrypted: ${decryptedData.size} bytes, PCM: ${pcmData.size} samples")
                    
                    if (pcmData.isNotEmpty()) {
                        submitFrame(channelId, pcmData)
                    } else {
                        Log.w("MultiChannelAudio", "⚠️  Empty PCM data for channel $channelId")
                    }
                } else {
                    Log.w("MultiChannelAudio", "⚠️  Empty audio data for channel $channelId")
                }
            } else {
                Log.d("MultiChannelAudio", "📄 Non-audio packet: ${jsonObj.optString("type", "unknown")}")
            }
        } catch (e: Exception) {
            Log.e("MultiChannelAudio", "❌ Packet processing error: ${e.message}", e)
        }
    }
    
    private fun startMixerThread() {
        mixerThread = Thread {
            // Critical: Set highest audio priority for real-time performance
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            
            try {
                while (isPlaying.get() && !Thread.currentThread().isInterrupted) {
                    val startTime = System.nanoTime()
                    
                    // Mix multiple frames to reduce write frequency and underruns
                    mixAndWriteFrames()
                    
                    // Calculate precise sleep time to maintain 40ms frame timing
                    val elapsedNs = System.nanoTime() - startTime
                    val sleepTimeMs = FRAME_DURATION_MS - (elapsedNs / 1_000_000)
                    
                    if (sleepTimeMs > 0) {
                        Thread.sleep(sleepTimeMs)
                    } else {
                        // We're running behind, log a warning but continue
                        Log.w("MultiChannelAudio", "⚠️  Mixer thread running behind by ${-sleepTimeMs}ms")
                    }
                }
            } catch (e: InterruptedException) {
                // Normal shutdown
            } catch (e: Exception) {
                if (isPlaying.get()) {
                    listener?.onError("Mixer thread error: ${e.message}")
                    Log.e("MultiChannelAudio", "Mixer thread error", e)
                }
            }
        }.apply { start() }
    }
    
    private fun mixAndWriteFrames() {
        val audioTrack = audioTrack ?: return
        
        // Clear mix buffer
        mixBuffer.fill(0f)
        
        var activeChannelCount = 0
        val channelsWithData = mutableListOf<String>()
        
        // Collect one frame from each active channel
        for (channelId in activeChannels.keys) {
            val channelData = activeChannels[channelId] ?: continue
            if (!channelData.isActive) continue
            
            val jitterBuffer = jitterBuffers[channelId] ?: continue
            val frame = jitterBuffer.poll()
            
            if (frame != null && frame.pcmData.size == SAMPLES_PER_FRAME) {
                // Mix this channel's frame into the mix buffer
                mixChannelFrame(frame.pcmData, channelData.volume, mixBuffer)
                channelsWithData.add(channelId)
                activeChannelCount++
            } else {
                // Insert silence for this channel
                mixChannelFrame(silenceFrame, 0f, mixBuffer)
            }
        }
        
        if (activeChannelCount > 0) {
            Log.v("MultiChannelAudio", "🔊 Mixed $activeChannelCount channels: ${channelsWithData.joinToString()}")
        }
        
        // Convert float mix to 16-bit stereo and write to AudioTrack
        convertToStereoAndWrite(mixBuffer, audioTrack)
    }
    
    private fun mixChannelFrame(frameData: ShortArray, volume: Float, mixBuffer: FloatArray) {
        // Mix exactly SAMPLES_PER_FRAME samples into the mix buffer
        for (i in 0 until SAMPLES_PER_FRAME) {
            mixBuffer[i] += frameData[i] * volume
        }
    }
    
    private fun convertToStereoAndWrite(mixBuffer: FloatArray, audioTrack: AudioTrack) {
        // Convert float mix to 16-bit stereo with proper clamping
        for (i in 0 until WRITE_SIZE_SAMPLES) {
            val sample = mixBuffer[i].coerceIn(-32768f, 32767f).toInt()
            val stereoSample = sample.toShort()
            
            // Interleaved stereo: left, right, left, right...
            stereoBuffer[i * 2] = stereoSample     // Left channel
            stereoBuffer[i * 2 + 1] = stereoSample // Right channel
        }
        
        // Write to AudioTrack with error handling
        try {
            val byteBuffer = ByteBuffer.allocateDirect(stereoBuffer.size * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asShortBuffer().put(stereoBuffer)
            byteBuffer.position(0)
            
            val bytesWritten = audioTrack.write(
                byteBuffer,
                stereoBuffer.size * 2,
                AudioTrack.WRITE_BLOCKING
            )
            
            if (bytesWritten > 0) {
                Log.v("MultiChannelAudio", "✅ Mixed audio written: $bytesWritten bytes")
            } else {
                Log.w("MultiChannelAudio", "⚠️  AudioTrack write failed: $bytesWritten bytes")
            }
        } catch (e: Exception) {
            Log.e("MultiChannelAudio", "❌ Error writing mixed audio: ${e.message}")
        }
    }
    
    private fun decodeOpus(channelId: String, encodedData: ByteArray): ShortArray {
        val decoder = opusDecoders[channelId] ?: return ShortArray(0)
        
        val decodedData = ShortArray(SAMPLES_PER_FRAME)
        val size = decoder.decode(encodedData, decodedData)
        
        Log.d("MultiChannelAudio", "🔊 Opus decode -> Channel: $channelId, Input: ${encodedData.size} bytes, Output: $size samples")
        
        if (size <= 0) {
            Log.w("MultiChannelAudio", "⚠️  Opus decode failed for channel $channelId, size: $size")
            return ShortArray(0)
        }
        
        return decodedData.copyOf(size)
    }
    
    private fun decryptAES(encryptedData: ByteArray, key: SecretKey): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = encryptedData.copyOfRange(0, 12)
            val actualEncryptedData = encryptedData.copyOfRange(12, encryptedData.size)
            
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(actualEncryptedData)
        } catch (e: Exception) {
            Log.e("MultiChannelAudio", "Decryption error: ${e.message}")
            ByteArray(0)
        }
    }
    
    fun getChannelStats(channelId: String): Map<String, Any>? {
        val channelData = activeChannels[channelId] ?: return null
        return mapOf(
            "packetsReceived" to channelData.packetsReceived,
            "packetsDropped" to channelData.packetsDropped,
            "isActive" to channelData.isActive,
            "volume" to channelData.volume,
            "jitterBufferSize" to (jitterBuffers[channelId]?.size ?: 0),
            "lastPacketTime" to channelData.lastPacketTime
        )
    }
    
    fun getActiveChannels(): Set<String> = activeChannels.keys.toSet()
    
    fun release() {
        stop()
        socket.close()
    }
}