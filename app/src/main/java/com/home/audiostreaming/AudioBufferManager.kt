package com.home.audiostreaming

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * AudioBufferManager manages individual audio buffers for each WebSocket channel.
 * Each channel has its own queue to handle network jitter and timing variations.
 */
class AudioBufferManager {
    
    companion object {
        private const val TAG = "AudioBufferManager"
        private const val MAX_BUFFER_SIZE = 50 // Maximum number of frames per channel
        private const val MAX_FRAME_SIZE = 4800 // Maximum frame size in samples
    }
    
    // Channel-specific audio buffers
    private val channelBuffers = mutableMapOf<String, ConcurrentLinkedQueue<AudioFrame>>()
    
    // Channel statistics
    private val channelStats = mutableMapOf<String, ChannelStats>()
    
    // Buffer size monitoring
    private val totalBufferedFrames = AtomicInteger(0)
    
    /**
     * Adds audio data to a specific channel's buffer.
     * 
     * @param channelId Channel identifier
     * @param pcmData Raw PCM audio data
     * @param timestamp Timestamp when the data was received
     */
    fun addAudioData(channelId: String, pcmData: ShortArray, timestamp: Long) {
        val buffer = channelBuffers.getOrPut(channelId) { ConcurrentLinkedQueue() }
        val stats = channelStats.getOrPut(channelId) { ChannelStats() }
        
        // Check if buffer is getting too large
        if (buffer.size >= MAX_BUFFER_SIZE) {
            Log.w(TAG, "Channel $channelId buffer full, dropping oldest frame")
            buffer.poll() // Remove oldest frame
            stats.droppedFrames++
        }
        
        val frame = AudioFrame(pcmData, timestamp, System.currentTimeMillis())
        buffer.offer(frame)
        totalBufferedFrames.incrementAndGet()
        stats.totalFrames++
        
        Log.v(TAG, "Added frame to channel $channelId, buffer size: ${buffer.size}")
    }
    
    /**
     * Retrieves the next audio frame from a channel's buffer.
     * 
     * @param channelId Channel identifier
     * @return AudioFrame or null if buffer is empty
     */
    fun getNextFrame(channelId: String): AudioFrame? {
        val buffer = channelBuffers[channelId] ?: return null
        val frame = buffer.poll()
        
        if (frame != null) {
            totalBufferedFrames.decrementAndGet()
            val stats = channelStats[channelId]
            stats?.processedFrames = (stats?.processedFrames ?: 0) + 1
        }
        
        return frame
    }
    
    /**
     * Checks if a channel has available audio data.
     * 
     * @param channelId Channel identifier
     * @return true if data is available, false otherwise
     */
    fun hasData(channelId: String): Boolean {
        return channelBuffers[channelId]?.isNotEmpty() ?: false
    }
    
    /**
     * Gets the current buffer size for a specific channel.
     * 
     * @param channelId Channel identifier
     * @return Number of frames in the buffer
     */
    fun getBufferSize(channelId: String): Int {
        return channelBuffers[channelId]?.size ?: 0
    }
    
    /**
     * Gets the total number of buffered frames across all channels.
     * 
     * @return Total buffered frames
     */
    fun getTotalBufferedFrames(): Int {
        return totalBufferedFrames.get()
    }
    
    /**
     * Clears all buffers for a specific channel.
     * 
     * @param channelId Channel identifier
     */
    fun clearChannel(channelId: String) {
        val buffer = channelBuffers[channelId]
        if (buffer != null) {
            val clearedFrames = buffer.size
            buffer.clear()
            totalBufferedFrames.addAndGet(-clearedFrames)
            Log.d(TAG, "Cleared $clearedFrames frames from channel $channelId")
        }
    }
    
    /**
     * Clears all buffers for all channels.
     */
    fun clearAllChannels() {
        channelBuffers.values.forEach { it.clear() }
        totalBufferedFrames.set(0)
        Log.d(TAG, "Cleared all channel buffers")
    }
    
    /**
     * Gets statistics for a specific channel.
     * 
     * @param channelId Channel identifier
     * @return ChannelStats or null if channel doesn't exist
     */
    fun getChannelStats(channelId: String): ChannelStats? {
        return channelStats[channelId]
    }
    
    /**
     * Gets all channel statistics.
     * 
     * @return Map of channel ID to ChannelStats
     */
    fun getAllChannelStats(): Map<String, ChannelStats> {
        return channelStats.toMap()
    }
    
    /**
     * Removes a channel and its associated data.
     * 
     * @param channelId Channel identifier
     */
    fun removeChannel(channelId: String) {
        val buffer = channelBuffers.remove(channelId)
        if (buffer != null) {
            val clearedFrames = buffer.size
            totalBufferedFrames.addAndGet(-clearedFrames)
            Log.d(TAG, "Removed channel $channelId with $clearedFrames frames")
        }
        channelStats.remove(channelId)
    }
    
    /**
     * Gets all active channel IDs.
     * 
     * @return Set of active channel IDs
     */
    fun getActiveChannels(): Set<String> {
        return channelBuffers.keys.toSet()
    }
    
    /**
     * Data class representing an audio frame with metadata.
     */
    data class AudioFrame(
        val pcmData: ShortArray,
        val timestamp: Long, // Original timestamp from the source
        val receivedAt: Long // When this frame was received by the buffer manager
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as AudioFrame
            
            if (!pcmData.contentEquals(other.pcmData)) return false
            if (timestamp != other.timestamp) return false
            if (receivedAt != other.receivedAt) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = pcmData.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + receivedAt.hashCode()
            return result
        }
    }
    
    /**
     * Statistics for a specific channel.
     */
    data class ChannelStats(
        var totalFrames: Long = 0,
        var processedFrames: Long = 0,
        var droppedFrames: Long = 0,
        var lastFrameTime: Long = 0
    ) {
        val bufferUtilization: Float
            get() = if (totalFrames > 0) processedFrames.toFloat() / totalFrames else 0.0f
        
        val dropRate: Float
            get() = if (totalFrames > 0) droppedFrames.toFloat() / totalFrames else 0.0f
    }
}
