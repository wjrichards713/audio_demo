package com.home.audiostreaming

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocketAudioReceiver handles receiving audio data from multiple WebSocket channels
 * and forwards the PCM data to the MultiChannelAudioPlayer for mixing and playback.
 */
class WebSocketAudioReceiver {
    
    companion object {
        private const val TAG = "WebSocketAudioReceiver"
        private const val MAX_CHANNELS = 4
        private const val RECONNECT_DELAY_MS = 5000L
        private const val PING_INTERVAL_MS = 30000L
    }
    
    // WebSocket management
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    
    private val webSockets = ConcurrentHashMap<String, WebSocket>()
    private val isConnected = ConcurrentHashMap<String, AtomicBoolean>()
    
    // Coroutine management
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    
    // Audio data channel for forwarding to player
    private val audioDataChannel = Channel<AudioData>(Channel.UNLIMITED)
    
    // Channel configuration
    private val channelConfigs = ConcurrentHashMap<String, ChannelConfig>()
    
    // Listener interface
    interface AudioReceiverListener {
        fun onChannelConnected(channelId: String)
        fun onChannelDisconnected(channelId: String)
        fun onAudioDataReceived(channelId: String, pcmData: ShortArray, timestamp: Long)
        fun onError(channelId: String, error: String)
    }
    
    private var listener: AudioReceiverListener? = null
    
    /**
     * Connects to a WebSocket channel for audio streaming.
     * 
     * @param channelId Channel identifier
     * @param url WebSocket URL
     * @param config Channel configuration
     */
    fun connectChannel(channelId: String, url: String, config: ChannelConfig) {
        if (webSockets.containsKey(channelId)) {
            Log.w(TAG, "Channel $channelId already connected")
            return
        }
        
        if (webSockets.size >= MAX_CHANNELS) {
            Log.e(TAG, "Maximum number of channels ($MAX_CHANNELS) reached")
            listener?.onError(channelId, "Maximum number of channels reached")
            return
        }
        
        channelConfigs[channelId] = config
        isConnected[channelId] = AtomicBoolean(false)
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        val webSocket = client.newWebSocket(request, WebSocketListenerImpl(channelId))
        webSockets[channelId] = webSocket
        
        Log.d(TAG, "Connecting to channel $channelId at $url")
    }
    
    /**
     * Disconnects from a WebSocket channel.
     * 
     * @param channelId Channel identifier
     */
    fun disconnectChannel(channelId: String) {
        val webSocket = webSockets.remove(channelId)
        webSocket?.close(1000, "Disconnecting")
        
        isConnected.remove(channelId)
        channelConfigs.remove(channelId)
        
        // Cancel reconnect job if exists
        reconnectJobs[channelId]?.cancel()
        reconnectJobs.remove(channelId)
        
        listener?.onChannelDisconnected(channelId)
        Log.d(TAG, "Disconnected from channel $channelId")
    }
    
    /**
     * Disconnects from all channels.
     */
    fun disconnectAllChannels() {
        val channelIds = webSockets.keys.toList()
        channelIds.forEach { disconnectChannel(it) }
        Log.d(TAG, "Disconnected from all channels")
    }
    
    /**
     * Sends a message to a specific channel.
     * 
     * @param channelId Channel identifier
     * @param message Message to send
     */
    fun sendMessage(channelId: String, message: String) {
        val webSocket = webSockets[channelId]
        if (webSocket != null && isConnected[channelId]?.get() == true) {
            webSocket.send(message)
            Log.d(TAG, "Sent message to channel $channelId: $message")
        } else {
            Log.w(TAG, "Cannot send message: channel $channelId not connected")
        }
    }
    
    /**
     * Gets the connection status of a channel.
     * 
     * @param channelId Channel identifier
     * @return true if connected, false otherwise
     */
    fun isChannelConnected(channelId: String): Boolean {
        return isConnected[channelId]?.get() ?: false
    }
    
    /**
     * Gets all connected channel IDs.
     * 
     * @return Set of connected channel IDs
     */
    fun getConnectedChannels(): Set<String> {
        return webSockets.keys.filter { isChannelConnected(it) }.toSet()
    }
    
    /**
     * Sets the audio receiver listener.
     * 
     * @param listener AudioReceiverListener instance
     */
    fun setListener(listener: AudioReceiverListener?) {
        this.listener = listener
    }
    
    /**
     * Gets the audio data channel for consuming audio data.
     * 
     * @return Channel<AudioData> for receiving audio data
     */
    fun getAudioDataChannel(): Channel<AudioData> {
        return audioDataChannel
    }
    
    /**
     * WebSocket listener implementation for handling connection events and messages.
     */
    private inner class WebSocketListenerImpl(private val channelId: String) : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Channel $channelId connected")
            isConnected[channelId]?.set(true)
            listener?.onChannelConnected(channelId)
            
            // Cancel any pending reconnect job
            reconnectJobs[channelId]?.cancel()
            reconnectJobs.remove(channelId)
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(channelId, text)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message from channel $channelId", e)
                listener?.onError(channelId, "Message handling error: ${e.message}")
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Channel $channelId connection failed: ${t.message}")
            isConnected[channelId]?.set(false)
            listener?.onError(channelId, "Connection failed: ${t.message}")
            
            // Schedule reconnection
            scheduleReconnection(channelId)
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Channel $channelId closed: $code - $reason")
            isConnected[channelId]?.set(false)
            listener?.onChannelDisconnected(channelId)
        }
    }
    
    /**
     * Handles incoming messages from WebSocket channels.
     * 
     * @param channelId Channel identifier
     * @param message Message content
     */
    private fun handleMessage(channelId: String, message: String) {
        try {
            val json = JSONObject(message)
            
            // Check if this is an audio message
            if (json.has("type") && json.optString("type") == "audio") {
                handleAudioMessage(channelId, json)
            } else {
                // Handle other message types (keep-alive, control, etc.)
                handleControlMessage(channelId, json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message from channel $channelId", e)
        }
    }
    
    /**
     * Handles audio messages containing PCM data.
     * 
     * @param channelId Channel identifier
     * @param json JSON message containing audio data
     */
    private fun handleAudioMessage(channelId: String, json: JSONObject) {
        try {
            val config = channelConfigs[channelId] ?: return
            
            // Extract and decode audio data based on configuration
            val audioData = when (config.encoding) {
                AudioEncoding.PCM_16BIT -> {
                    val base64Data = json.optString("data")
                    if (base64Data.isNotEmpty()) {
                        decodeBase64PCM(base64Data)
                    } else {
                        null
                    }
                }
                AudioEncoding.OPUS -> {
                    val base64Data = json.optString("data")
                    if (base64Data.isNotEmpty()) {
                        decodeOpusAudio(base64Data, channelId)
                    } else {
                        null
                    }
                }
            }
            
            if (audioData != null) {
                val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                
                // Forward to audio player
                listener?.onAudioDataReceived(channelId, audioData, timestamp)
                
                // Also send to audio data channel for external consumption
                coroutineScope.launch {
                    audioDataChannel.send(AudioData(channelId, audioData, timestamp))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling audio message from channel $channelId", e)
            listener?.onError(channelId, "Audio processing error: ${e.message}")
        }
    }
    
    /**
     * Handles control messages (keep-alive, status, etc.).
     * 
     * @param channelId Channel identifier
     * @param json JSON message
     */
    private fun handleControlMessage(channelId: String, json: JSONObject) {
        // Handle keep-alive, status updates, etc.
        Log.v(TAG, "Received control message from channel $channelId: ${json.toString()}")
    }
    
    /**
     * Decodes base64-encoded PCM data.
     * 
     * @param base64Data Base64-encoded PCM data
     * @return Decoded PCM data as ShortArray
     */
    private fun decodeBase64PCM(base64Data: String): ShortArray? {
        return try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val shorts = ShortArray(bytes.size / 2)
            for (i in shorts.indices) {
                val byteIndex = i * 2
                shorts[i] = ((bytes[byteIndex].toInt() and 0xFF) or 
                           ((bytes[byteIndex + 1].toInt() and 0xFF) shl 8)).toShort()
            }
            shorts
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 PCM data", e)
            null
        }
    }
    
    /**
     * Decodes Opus audio data.
     * 
     * @param base64Data Base64-encoded Opus data
     * @param channelId Channel identifier
     * @return Decoded PCM data as ShortArray
     */
    private fun decodeOpusAudio(base64Data: String, channelId: String): ShortArray? {
        // This would integrate with your existing Opus decoder
        // For now, return null as this requires the Opus library
        Log.w(TAG, "Opus decoding not implemented for channel $channelId")
        return null
    }
    
    /**
     * Schedules reconnection for a failed channel.
     * 
     * @param channelId Channel identifier
     */
    private fun scheduleReconnection(channelId: String) {
        val config = channelConfigs[channelId] ?: return
        
        val reconnectJob = coroutineScope.launch {
            delay(RECONNECT_DELAY_MS)
            
            if (isActive) {
                Log.d(TAG, "Attempting to reconnect channel $channelId")
                connectChannel(channelId, config.url, config)
            }
        }
        
        reconnectJobs[channelId] = reconnectJob
    }
    
    /**
     * Cleanup method to release resources.
     */
    fun cleanup() {
        disconnectAllChannels()
        coroutineScope.cancel()
        audioDataChannel.close()
    }
    
    /**
     * Data class representing audio data from a channel.
     */
    data class AudioData(
        val channelId: String,
        val pcmData: ShortArray,
        val timestamp: Long
    )
    
    /**
     * Data class representing channel configuration.
     */
    data class ChannelConfig(
        val url: String,
        val encoding: AudioEncoding,
        val sampleRate: Int = 48000,
        val channels: Int = 1
    )
    
    /**
     * Enum representing audio encoding types.
     */
    enum class AudioEncoding {
        PCM_16BIT,
        OPUS
    }
}
