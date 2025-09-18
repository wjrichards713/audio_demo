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
    
    private val SAMPLE_RATE = AudioStreamConstant.SAMPLE_RATE
    private val CHANNELS = 1
    private val BITS_PER_SAMPLE = 16
    private val FRAME_DURATION_MS = 40L
    private val JITTER_BUFFER_SIZE = 10
    private val PROCESSING_INTERVAL_MS = 10L
    
    private val activeChannels = ConcurrentHashMap<String, ChannelData>()
    private val jitterBuffers = ConcurrentHashMap<String, ConcurrentLinkedQueue<AudioFrame>>()
    private val opusDecoders = ConcurrentHashMap<String, OpusDecoder>()
    private val audioTracks = ConcurrentHashMap<String, AudioTrack>()
    
    private val globalReferenceTime = SystemClock.elapsedRealtimeNanos()
    private val channelTimestamps = ConcurrentHashMap<String, Long>()
    private val lastProcessedTime = AtomicLong(0L)
    
    private val isPlaying = AtomicBoolean(false)
    private var packetReceiverThread: Thread? = null
    private var audioProcessorThread: Thread? = null
    
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
        startPacketReceiver()
        startUnifiedAudioProcessor()
        listener?.onLog("Multi-channel audio player started")
    }
    
    fun stop() {
        isPlaying.set(false)
        packetReceiverThread?.interrupt()
        audioProcessorThread?.interrupt()
        
        audioTracks.values.forEach { track ->
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e("MultiChannelAudio", "Error stopping audio track: ${e.message}")
            }
        }
        
        audioTracks.clear()
        jitterBuffers.clear()
        opusDecoders.clear()
        activeChannels.clear()
        channelTimestamps.clear()
        
        listener?.onLog("Multi-channel audio player stopped")
    }
    
    fun setChannelVolume(channelId: String, volume: Float) {
        activeChannels[channelId]?.volume = volume.coerceIn(0f, 1f)
        audioTracks[channelId]?.setVolume(volume.coerceIn(0f, 1f))
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
        
        opusDecoders[channelId] = OpusDecoder().apply {
            init(SAMPLE_RATE, CHANNELS, 4800)
        }
        
        createAudioTrackForChannel(channelId)
        
        Log.i("MultiChannelAudio", "✅ Channel $channelId added and activated successfully")
        listener?.onChannelStarted(channelId)
        listener?.onLog("Channel $channelId added and activated")
    }
    
    fun removeChannel(channelId: String) {
        activeChannels[channelId]?.isActive = false
        
        audioTracks[channelId]?.let { track ->
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e("MultiChannelAudio", "Error releasing audio track for channel $channelId: ${e.message}")
            }
        }
        
        audioTracks.remove(channelId)
        jitterBuffers.remove(channelId)
        opusDecoders.remove(channelId)
        activeChannels.remove(channelId)
        channelTimestamps.remove(channelId)
        
        listener?.onChannelStopped(channelId)
        listener?.onLog("Channel $channelId removed")
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
                    Log.w("MultiChannelAudio", "⚠️  Channel $channelId not active, dropping packet")
                    return
                }
                
                if (base64Data.isNotEmpty()) {
                    val encryptedData = Base64.decode(base64Data, Base64.DEFAULT)
                    val decryptedData = decryptAES(encryptedData, keyAES)
                    val pcmData = decodeOpus(channelId, decryptedData)
                    
                    Log.d("MultiChannelAudio", "🔓 Decoded -> Encrypted: ${encryptedData.size} bytes, Decrypted: ${decryptedData.size} bytes, PCM: ${pcmData.size} samples")
                    
                    if (pcmData.isNotEmpty()) {
                        val frame = AudioFrame(
                            pcmData = pcmData,
                            timestamp = SystemClock.elapsedRealtimeNanos(),
                            sequenceNumber = channelData.packetsReceived,
                            channelId = channelId
                        )
                        
                        val jitterBuffer = jitterBuffers[channelId] ?: return
                        
                        jitterBuffer.offer(frame)
                        
                        while (jitterBuffer.size > JITTER_BUFFER_SIZE) {
                            jitterBuffer.poll()
                            channelData.packetsDropped++
                            Log.w("MultiChannelAudio", "🗑️  Dropped frame for channel $channelId (buffer full)")
                        }
                        
                        channelData.packetsReceived++
                        channelData.lastPacketTime = System.currentTimeMillis()
                        
                        Log.v("MultiChannelAudio", "✅ Audio processed -> Channel: $channelId, Packets: ${channelData.packetsReceived}, Buffer: ${jitterBuffer.size}, Dropped: ${channelData.packetsDropped}")
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
    
    private fun startUnifiedAudioProcessor() {
        audioProcessorThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            try {
                while (isPlaying.get() && !Thread.currentThread().isInterrupted) {
                    val currentTime = SystemClock.elapsedRealtimeNanos()
                    
                    for (channelId in activeChannels.keys) {
                        val channelData = activeChannels[channelId]
                        if (channelData?.isActive == true) {
                            processChannelAudio(channelId, currentTime)
                        }
                    }
                    
                    Thread.sleep(PROCESSING_INTERVAL_MS)
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                if (isPlaying.get()) {
                    listener?.onError("Audio processor error: ${e.message}")
                    Log.e("MultiChannelAudio", "Audio processor error", e)
                }
            }
        }.apply { start() }
    }
    
    private fun processChannelAudio(channelId: String, currentTime: Long) {
        val jitterBuffer = jitterBuffers[channelId] ?: return
        val audioTrack = audioTracks[channelId] ?: return
        val channelData = activeChannels[channelId] ?: return
        
        if (jitterBuffer.isEmpty()) return
        
        val frame = jitterBuffer.poll() ?: return
        
        Log.v("MultiChannelAudio", "🔊 Playing audio -> Channel: $channelId, Frame: ${frame.sequenceNumber}, PCM: ${frame.pcmData.size} samples")
        
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play()
            Log.d("MultiChannelAudio", "▶️  Started AudioTrack for channel $channelId")
        }
        
        val synchronizedTimestamp = getSynchronizedTimestamp(audioTrack, currentTime)
        
        val stereoData = convertMonoToStereo(frame.pcmData)
        
        try {
            val byteBuffer = ByteBuffer.allocateDirect(stereoData.size * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asShortBuffer().put(stereoData)
            byteBuffer.position(0)
            
            val bytesWritten = audioTrack.write(
                byteBuffer, 
                stereoData.size * 2, 
                AudioTrack.WRITE_BLOCKING, 
                synchronizedTimestamp
            )
            
            if (bytesWritten > 0) {
                channelTimestamps[channelId] = currentTime
                Log.v("MultiChannelAudio", "✅ Audio written -> Channel: $channelId, Bytes: $bytesWritten, Buffer: ${jitterBuffer.size}")
            } else {
                Log.w("MultiChannelAudio", "⚠️  Failed to write audio for channel $channelId")
            }
        } catch (e: Exception) {
            Log.e("MultiChannelAudio", "❌ Error writing audio for channel $channelId: ${e.message}")
        }
    }
    
    private fun getSynchronizedTimestamp(audioTrack: AudioTrack, currentTime: Long): Long {
        val timestamp = AudioTimestamp()
        
        return if (audioTrack.getTimestamp(timestamp)) {
            timestamp.nanoTime + (FRAME_DURATION_MS * 1_000_000)
        } else {
            currentTime + (FRAME_DURATION_MS * 1_000_000)
        }
    }
    
    private fun createAudioTrackForChannel(channelId: String) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 4
        
        val audioTrack = AudioTrack.Builder()
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
        
        audioTracks[channelId] = audioTrack
    }
    
    private fun decodeOpus(channelId: String, encodedData: ByteArray): ShortArray {
        val decoder = opusDecoders[channelId] ?: return ShortArray(0)
        val decodedData = ShortArray(4800)
        val size = decoder.decode(encodedData, decodedData)
        return if (size > 0) decodedData.copyOf(size) else ShortArray(0)
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
    
    private fun convertMonoToStereo(monoData: ShortArray): ShortArray {
        val stereoData = ShortArray(monoData.size * 2)
        for (i in monoData.indices) {
            stereoData[i * 2] = monoData[i]
            stereoData[i * 2 + 1] = monoData[i]
        }
        return stereoData
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
}