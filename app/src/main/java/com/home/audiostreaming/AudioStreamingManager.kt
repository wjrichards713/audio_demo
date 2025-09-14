package com.home.audiostreaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * AudioStreamingManager is the main coordinator class that integrates all audio components
 * to provide a complete multi-channel audio streaming solution.
 * 
 * This class demonstrates the complete architecture:
 * - WebSocketAudioReceiver handles 4 WebSocket channels
 * - AudioBufferManager manages channel-specific buffers
 * - MultiChannelAudioPlayer mixes and plays audio using a single AudioTrack
 * - All components use Kotlin coroutines for efficient processing
 */
class AudioStreamingManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioStreamingManager"
        private const val MAX_CHANNELS = 4
    }
    
    // Core components
    private val audioPlayer = MultiChannelAudioPlayer()
    private val webSocketReceiver = WebSocketAudioReceiver()
    
    // Coroutine management
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioDataConsumerJob: Job? = null
    
    // Channel management
    private val channelUrls = mutableMapOf<String, String>()
    private val channelConfigs = mutableMapOf<String, WebSocketAudioReceiver.ChannelConfig>()
    
    // State management
    private var isInitialized = false
    private var isPlaying = false
    
    // Listener interface
    interface StreamingManagerListener {
        fun onPlaybackStarted()
        fun onPlaybackStopped()
        fun onChannelConnected(channelId: String)
        fun onChannelDisconnected(channelId: String)
        fun onError(error: String)
        fun onChannelStateChanged(channelId: String, isActive: Boolean)
    }
    
    private var listener: StreamingManagerListener? = null
    
    /**
     * Initializes the audio streaming system.
     */
    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }
        
        setupAudioPlayer()
        setupWebSocketReceiver()
        startAudioDataConsumer()
        
        isInitialized = true
        Log.d(TAG, "Audio streaming system initialized")
    }
    
    /**
     * Starts audio playback and begins receiving from WebSocket channels.
     */
    fun startPlayback() {
        if (!isInitialized) {
            Log.e(TAG, "Not initialized")
            return
        }
        
        if (isPlaying) {
            Log.w(TAG, "Already playing")
            return
        }
        
        audioPlayer.startPlayback()
        isPlaying = true
        Log.d(TAG, "Audio playback started")
    }
    
    /**
     * Stops audio playback and disconnects from all channels.
     */
    fun stopPlayback() {
        if (!isPlaying) {
            Log.w(TAG, "Not playing")
            return
        }
        
        audioPlayer.stopPlayback()
        webSocketReceiver.disconnectAllChannels()
        isPlaying = false
        Log.d(TAG, "Audio playback stopped")
    }
    
    /**
     * Adds a WebSocket channel for audio streaming.
     * 
     * @param channelId Channel identifier
     * @param url WebSocket URL
     * @param encoding Audio encoding type
     * @param sampleRate Sample rate (default: 48000)
     * @param channels Number of channels (default: 1)
     */
    fun addChannel(
        channelId: String,
        url: String,
        encoding: WebSocketAudioReceiver.AudioEncoding,
        sampleRate: Int = 48000,
        channels: Int = 1
    ) {
        if (channelUrls.size >= MAX_CHANNELS) {
            Log.e(TAG, "Maximum number of channels ($MAX_CHANNELS) reached")
            listener?.onError("Maximum number of channels reached")
            return
        }
        
        val config = WebSocketAudioReceiver.ChannelConfig(url, encoding, sampleRate, channels)
        channelConfigs[channelId] = config
        channelUrls[channelId] = url
        
        webSocketReceiver.connectChannel(channelId, url, config)
        Log.d(TAG, "Added channel $channelId")
    }
    
    /**
     * Removes a WebSocket channel.
     * 
     * @param channelId Channel identifier
     */
    fun removeChannel(channelId: String) {
        webSocketReceiver.disconnectChannel(channelId)
        audioPlayer.removeChannel(channelId)
        channelUrls.remove(channelId)
        channelConfigs.remove(channelId)
        Log.d(TAG, "Removed channel $channelId")
    }
    
    /**
     * Sets the volume for a specific channel.
     * 
     * @param channelId Channel identifier
     * @param volume Volume level (0.0f to 1.0f)
     */
    fun setChannelVolume(channelId: String, volume: Float) {
        audioPlayer.setChannelVolume(channelId, volume)
    }
    
    /**
     * Gets the current volume for a specific channel.
     * 
     * @param channelId Channel identifier
     * @return Current volume level
     */
    fun getChannelVolume(channelId: String): Float {
        return audioPlayer.getChannelVolume(channelId)
    }
    
    /**
     * Gets all connected channel IDs.
     * 
     * @return Set of connected channel IDs
     */
    fun getConnectedChannels(): Set<String> {
        return webSocketReceiver.getConnectedChannels()
    }
    
    /**
     * Gets statistics for all channels.
     * 
     * @return Map of channel statistics
     */
    fun getChannelStatistics(): Map<String, AudioBufferManager.ChannelStats> {
        return audioPlayer.getChannelStatistics()
    }
    
    /**
     * Sends a message to a specific channel.
     * 
     * @param channelId Channel identifier
     * @param message Message to send
     */
    fun sendMessage(channelId: String, message: String) {
        webSocketReceiver.sendMessage(channelId, message)
    }
    
    /**
     * Sets the streaming manager listener.
     * 
     * @param listener StreamingManagerListener instance
     */
    fun setListener(listener: StreamingManagerListener?) {
        this.listener = listener
    }
    
    /**
     * Sets up the audio player with proper listeners.
     */
    private fun setupAudioPlayer() {
        audioPlayer.setListener(object : MultiChannelAudioPlayer.AudioPlayerListener {
            override fun onPlaybackStarted() {
                listener?.onPlaybackStarted()
            }
            
            override fun onPlaybackStopped() {
                listener?.onPlaybackStopped()
            }
            
            override fun onError(error: String) {
                listener?.onError(error)
            }
            
            override fun onChannelStateChanged(channelId: String, isActive: Boolean) {
                listener?.onChannelStateChanged(channelId, isActive)
            }
        })
    }
    
    /**
     * Sets up the WebSocket receiver with proper listeners.
     */
    private fun setupWebSocketReceiver() {
        webSocketReceiver.setListener(object : WebSocketAudioReceiver.AudioReceiverListener {
            override fun onChannelConnected(channelId: String) {
                listener?.onChannelConnected(channelId)
            }
            
            override fun onChannelDisconnected(channelId: String) {
                listener?.onChannelDisconnected(channelId)
            }
            
            override fun onAudioDataReceived(channelId: String, pcmData: ShortArray, timestamp: Long) {
                // Forward audio data to the player for mixing
                audioPlayer.addAudioData(channelId, pcmData, timestamp)
            }
            
            override fun onError(channelId: String, error: String) {
                listener?.onError("Channel $channelId: $error")
            }
        })
    }
    
    /**
     * Starts the audio data consumer coroutine that processes audio data
     * from the WebSocket receiver and forwards it to the audio player.
     */
    private fun startAudioDataConsumer() {
        audioDataConsumerJob = coroutineScope.launch {
            val channel = webSocketReceiver.getAudioDataChannel()
            while (isActive) {
                try {
                    val audioData = channel.receive()
                    // This is already handled by the WebSocket receiver listener
                    // but we can add additional processing here if needed
                    Log.v(TAG, "Received audio data from channel ${audioData.channelId}, size: ${audioData.pcmData.size}")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                        Log.d(TAG, "Audio data channel closed")
                        break
                    } else {
                        Log.e(TAG, "Error processing audio data", e)
                    }
                }
            }
        }
    }
    
    /**
     * Cleanup method to release all resources.
     */
    fun cleanup() {
        stopPlayback()
        audioDataConsumerJob?.cancel()
        audioPlayer.cleanup()
        webSocketReceiver.cleanup()
        coroutineScope.cancel()
        isInitialized = false
        Log.d(TAG, "Audio streaming system cleaned up")
    }
    
    /**
     * Gets the current playback state.
     * 
     * @return true if playing, false otherwise
     */
    fun isPlaying(): Boolean {
        return isPlaying
    }
    
    /**
     * Gets the initialization state.
     * 
     * @return true if initialized, false otherwise
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }
}
