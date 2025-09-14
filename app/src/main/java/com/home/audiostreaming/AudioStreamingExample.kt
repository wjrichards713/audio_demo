package com.home.audiostreaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * AudioStreamingExample demonstrates how to use the AudioStreamingManager
 * to create a complete multi-channel audio streaming solution.
 * 
 * This example shows:
 * 1. How to initialize the audio streaming system
 * 2. How to add 4 WebSocket channels
 * 3. How to handle audio playback and mixing
 * 4. How to manage channel volumes and states
 */
class AudioStreamingExample(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioStreamingExample"
        
        // Example WebSocket URLs - replace with your actual URLs
        private const val CHANNEL_1_URL = "wss://your-server.com/audio/channel1"
        private const val CHANNEL_2_URL = "wss://your-server.com/audio/channel2"
        private const val CHANNEL_3_URL = "wss://your-server.com/audio/channel3"
        private const val CHANNEL_4_URL = "wss://your-server.com/audio/channel4"
    }
    
    private val audioStreamingManager = AudioStreamingManager(context)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    /**
     * Initializes and starts the audio streaming system with 4 channels.
     */
    fun startAudioStreaming() {
        coroutineScope.launch {
            try {
                // Initialize the audio streaming system
                audioStreamingManager.initialize()
                
                // Set up the listener to handle events
                setupListener()
                
                // Add 4 WebSocket channels
                addChannels()
                
                // Start audio playback
                audioStreamingManager.startPlayback()
                
                Log.d(TAG, "Audio streaming started with 4 channels")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio streaming", e)
            }
        }
    }
    
    /**
     * Stops the audio streaming system.
     */
    fun stopAudioStreaming() {
        coroutineScope.launch {
            try {
                audioStreamingManager.stopPlayback()
                Log.d(TAG, "Audio streaming stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop audio streaming", e)
            }
        }
    }
    
    /**
     * Sets up the listener to handle audio streaming events.
     */
    private fun setupListener() {
        audioStreamingManager.setListener(object : AudioStreamingManager.StreamingManagerListener {
            override fun onPlaybackStarted() {
                Log.d(TAG, "Audio playback started")
                // Update UI or perform other actions
            }
            
            override fun onPlaybackStopped() {
                Log.d(TAG, "Audio playback stopped")
                // Update UI or perform other actions
            }
            
            override fun onChannelConnected(channelId: String) {
                Log.d(TAG, "Channel $channelId connected")
                // Update UI to show channel is connected
            }
            
            override fun onChannelDisconnected(channelId: String) {
                Log.d(TAG, "Channel $channelId disconnected")
                // Update UI to show channel is disconnected
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "Audio streaming error: $error")
                // Handle error - show user notification, etc.
            }
            
            override fun onChannelStateChanged(channelId: String, isActive: Boolean) {
                Log.d(TAG, "Channel $channelId state changed: ${if (isActive) "active" else "inactive"}")
                // Update UI to show channel activity status
            }
        })
    }
    
    /**
     * Adds 4 WebSocket channels for audio streaming.
     */
    private fun addChannels() {
        // Channel 1 - PCM 16-bit audio
        audioStreamingManager.addChannel(
            channelId = "channel1",
            url = CHANNEL_1_URL,
            encoding = WebSocketAudioReceiver.AudioEncoding.PCM_16BIT,
            sampleRate = 48000,
            channels = 1
        )
        
        // Channel 2 - PCM 16-bit audio
        audioStreamingManager.addChannel(
            channelId = "channel2",
            url = CHANNEL_2_URL,
            encoding = WebSocketAudioReceiver.AudioEncoding.PCM_16BIT,
            sampleRate = 48000,
            channels = 1
        )
        
        // Channel 3 - Opus audio (if your server supports it)
        audioStreamingManager.addChannel(
            channelId = "channel3",
            url = CHANNEL_3_URL,
            encoding = WebSocketAudioReceiver.AudioEncoding.OPUS,
            sampleRate = 48000,
            channels = 1
        )
        
        // Channel 4 - PCM 16-bit audio
        audioStreamingManager.addChannel(
            channelId = "channel4",
            url = CHANNEL_4_URL,
            encoding = WebSocketAudioReceiver.AudioEncoding.PCM_16BIT,
            sampleRate = 48000,
            channels = 1
        )
    }
    
    /**
     * Example of how to control channel volumes.
     */
    fun adjustChannelVolumes() {
        // Set different volumes for each channel
        audioStreamingManager.setChannelVolume("channel1", 0.8f)  // 80% volume
        audioStreamingManager.setChannelVolume("channel2", 0.6f)  // 60% volume
        audioStreamingManager.setChannelVolume("channel3", 1.0f)  // 100% volume
        audioStreamingManager.setChannelVolume("channel4", 0.4f)  // 40% volume
        
        Log.d(TAG, "Channel volumes adjusted")
    }
    
    /**
     * Example of how to send control messages to channels.
     */
    fun sendControlMessages() {
        // Send keep-alive messages to all channels
        val keepAliveMessage = """{"type":"keep_alive","timestamp":${System.currentTimeMillis()}}"""
        
        audioStreamingManager.getConnectedChannels().forEach { channelId ->
            audioStreamingManager.sendMessage(channelId, keepAliveMessage)
        }
        
        Log.d(TAG, "Control messages sent to all channels")
    }
    
    /**
     * Example of how to monitor channel statistics.
     */
    fun monitorChannelStatistics() {
        coroutineScope.launch {
            while (isActive) {
                val stats = audioStreamingManager.getChannelStatistics()
                
                stats.forEach { (channelId, channelStats) ->
                    Log.d(TAG, "Channel $channelId stats: " +
                            "totalFrames=${channelStats.totalFrames}, " +
                            "processedFrames=${channelStats.processedFrames}, " +
                            "droppedFrames=${channelStats.droppedFrames}, " +
                            "dropRate=${channelStats.dropRate}")
                }
                
                delay(5000) // Check every 5 seconds
            }
        }
    }
    
    /**
     * Example of how to handle channel management.
     */
    fun manageChannels() {
        // Remove a channel
        audioStreamingManager.removeChannel("channel4")
        
        // Add a new channel
        audioStreamingManager.addChannel(
            channelId = "channel5",
            url = "wss://your-server.com/audio/channel5",
            encoding = WebSocketAudioReceiver.AudioEncoding.PCM_16BIT
        )
        
        Log.d(TAG, "Channel management completed")
    }
    
    /**
     * Cleanup method to release resources.
     */
    fun cleanup() {
        audioStreamingManager.cleanup()
        coroutineScope.cancel()
        Log.d(TAG, "Audio streaming example cleaned up")
    }
    
    /**
     * Gets the current status of the audio streaming system.
     */
    fun getStatus(): String {
        val isPlaying = audioStreamingManager.isPlaying()
        val connectedChannels = audioStreamingManager.getConnectedChannels()
        
        return "Audio Streaming Status:\n" +
                "Playing: $isPlaying\n" +
                "Connected Channels: ${connectedChannels.size}\n" +
                "Channel IDs: ${connectedChannels.joinToString(", ")}"
    }
}

/**
 * Example of how to integrate this with your existing MainActivity.
 * Add this to your MainActivity class:
 */
/*
class MainActivity : AppCompatActivity() {
    private lateinit var audioStreamingExample: AudioStreamingExample
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... existing code ...
        
        // Initialize audio streaming
        audioStreamingExample = AudioStreamingExample(this)
        
        // Start audio streaming when WebSocket is connected
        // Call this in your WebSocket connection success callback
        audioStreamingExample.startAudioStreaming()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        audioStreamingExample.cleanup()
    }
    
    // Add methods to control audio streaming
    private fun adjustVolumes() {
        audioStreamingExample.adjustChannelVolumes()
    }
    
    private fun sendKeepAlive() {
        audioStreamingExample.sendControlMessages()
    }
    
    private fun showStatus() {
        val status = audioStreamingExample.getStatus()
        // Display status in your UI
    }
}
*/
