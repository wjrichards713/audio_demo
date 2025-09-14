package com.home.audiostreaming

import android.media.*
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MultiChannelAudioPlayer handles mixing and playback of multiple audio channels
 * using a single AudioTrack in streaming mode. This class uses Kotlin coroutines
 * for efficient audio processing and mixing.
 */
class MultiChannelAudioPlayer {
    
    companion object {
        private const val TAG = "MultiChannelAudioPlayer"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4
        private const val MIXING_INTERVAL_MS = 20L // Mix every 20ms for smooth playback
        private const val FRAME_SIZE_SAMPLES = (SAMPLE_RATE * MIXING_INTERVAL_MS / 1000).toInt()
        private const val MAX_CHANNELS = 4
    }
    
    // Audio components
    private var audioTrack: AudioTrack? = null
    private val audioMixer = AudioMixer()
    private val bufferManager = AudioBufferManager()
    
    // Coroutine management
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mixerJob: Job? = null
    private var isPlaying = AtomicBoolean(false)
    
    // Channel management
    private val channelVolumes = ConcurrentHashMap<String, Float>()
    private val channelStates = ConcurrentHashMap<String, ChannelState>()
    
    // State management
    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    
    // Audio data channel for mixing
    private val audioDataChannel = Channel<AudioData>(Channel.UNLIMITED)
    
    // Listener interface
    interface AudioPlayerListener {
        fun onPlaybackStarted()
        fun onPlaybackStopped()
        fun onError(error: String)
        fun onChannelStateChanged(channelId: String, isActive: Boolean)
    }
    
    private var listener: AudioPlayerListener? = null
    
    /**
     * Initializes the audio system and starts playback.
     */
    fun startPlayback() {
        if (isPlaying.get()) {
            Log.w(TAG, "Playback already started")
            return
        }
        
        try {
            initializeAudioTrack()
            startMixingCoroutine()
            isPlaying.set(true)
            _playbackState.value = PlaybackState.PLAYING
            listener?.onPlaybackStarted()
            Log.d(TAG, "Audio playback started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            listener?.onError("Failed to start playback: ${e.message}")
        }
    }
    
    /**
     * Stops audio playback and releases resources.
     */
    fun stopPlayback() {
        if (!isPlaying.get()) {
            Log.w(TAG, "Playback not started")
            return
        }
        
        isPlaying.set(false)
        _playbackState.value = PlaybackState.STOPPED
        
        // Cancel mixing coroutine
        mixerJob?.cancel()
        mixerJob = null
        
        // Stop and release AudioTrack
        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.release()
        }
        audioTrack = null
        
        // Clear all buffers
        bufferManager.clearAllChannels()
        
        listener?.onPlaybackStopped()
        Log.d(TAG, "Audio playback stopped")
    }
    
    /**
     * Adds audio data from a WebSocket channel to the mixing system.
     * 
     * @param channelId Channel identifier
     * @param pcmData Raw PCM audio data
     * @param timestamp Timestamp when the data was received
     */
    fun addAudioData(channelId: String, pcmData: ShortArray, timestamp: Long) {
        if (!isPlaying.get()) {
            Log.w(TAG, "Cannot add audio data: playback not started")
            return
        }
        
        bufferManager.addAudioData(channelId, pcmData, timestamp)
        
        // Update channel state
        val wasActive = channelStates[channelId]?.isActive ?: false
        val isActive = bufferManager.hasData(channelId)
        
        if (wasActive != isActive) {
            channelStates[channelId] = ChannelState(isActive, System.currentTimeMillis())
            listener?.onChannelStateChanged(channelId, isActive)
        }
    }
    
    /**
     * Sets the volume for a specific channel.
     * 
     * @param channelId Channel identifier
     * @param volume Volume level (0.0f to 1.0f)
     */
    fun setChannelVolume(channelId: String, volume: Float) {
        val clampedVolume = volume.coerceIn(0.0f, 1.0f)
        channelVolumes[channelId] = clampedVolume
        Log.d(TAG, "Set volume for channel $channelId to $clampedVolume")
    }
    
    /**
     * Gets the current volume for a specific channel.
     * 
     * @param channelId Channel identifier
     * @return Current volume level
     */
    fun getChannelVolume(channelId: String): Float {
        return channelVolumes[channelId] ?: 1.0f
    }
    
    /**
     * Removes a channel from the mixing system.
     * 
     * @param channelId Channel identifier
     */
    fun removeChannel(channelId: String) {
        bufferManager.removeChannel(channelId)
        channelVolumes.remove(channelId)
        channelStates.remove(channelId)
        Log.d(TAG, "Removed channel $channelId")
    }
    
    /**
     * Gets statistics for all channels.
     * 
     * @return Map of channel statistics
     */
    fun getChannelStatistics(): Map<String, AudioBufferManager.ChannelStats> {
        return bufferManager.getAllChannelStats()
    }
    
    /**
     * Sets the audio player listener.
     * 
     * @param listener AudioPlayerListener instance
     */
    fun setListener(listener: AudioPlayerListener?) {
        this.listener = listener
    }
    
    /**
     * Initializes the AudioTrack with proper configuration.
     */
    private fun initializeAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        
        val bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER
        
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
                    .setEncoding(AUDIO_FORMAT)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        audioTrack?.play()
        Log.d(TAG, "AudioTrack initialized with buffer size: $bufferSize")
    }
    
    /**
     * Starts the mixing coroutine that continuously mixes audio from all channels.
     */
    private fun startMixingCoroutine() {
        mixerJob = coroutineScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            while (isActive && isPlaying.get()) {
                try {
                    mixAndPlayAudio()
                    delay(MIXING_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in mixing coroutine", e)
                    listener?.onError("Mixing error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Mixes audio from all active channels and plays the result.
     */
    private suspend fun mixAndPlayAudio() {
        val activeChannels = bufferManager.getActiveChannels()
        if (activeChannels.isEmpty()) {
            // No active channels, play silence
            playSilence()
            return
        }
        
        // Prepare channel data for mixing
        val channelData = Array<ShortArray?>(MAX_CHANNELS) { null }
        val volumes = FloatArray(MAX_CHANNELS) { 1.0f }
        
        var channelIndex = 0
        for (channelId in activeChannels) {
            if (channelIndex >= MAX_CHANNELS) break
            
            val frame = bufferManager.getNextFrame(channelId)
            if (frame != null) {
                channelData[channelIndex] = frame.pcmData
                volumes[channelIndex] = getChannelVolume(channelId)
            }
            channelIndex++
        }
        
        // Mix the audio channels
        val mixedAudio = audioMixer.mixChannelsAdvanced(
            channelData,
            FRAME_SIZE_SAMPLES,
            volumes
        )
        
        // Convert mono to stereo for playback
        val stereoAudio = convertMonoToStereo(mixedAudio)
        
        // Play the mixed audio
        playAudioData(stereoAudio)
    }
    
    /**
     * Plays silence when no channels are active.
     */
    private suspend fun playSilence() {
        val silence = audioMixer.createSilence(FRAME_SIZE_SAMPLES)
        val stereoSilence = convertMonoToStereo(silence)
        playAudioData(stereoSilence)
    }
    
    /**
     * Plays audio data through the AudioTrack.
     * 
     * @param audioData Audio data to play
     */
    private fun playAudioData(audioData: ShortArray) {
        val track = audioTrack ?: return
        
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        
        val byteBuffer = audioData.toByteArray()
        val bytesWritten = track.write(byteBuffer, 0, byteBuffer.size, AudioTrack.WRITE_BLOCKING)
        
        if (bytesWritten < 0) {
            Log.e(TAG, "Failed to write audio data: $bytesWritten")
        }
    }
    
    /**
     * Converts mono audio to stereo by duplicating the channel.
     * 
     * @param monoData Mono audio data
     * @return Stereo audio data
     */
    private fun convertMonoToStereo(monoData: ShortArray): ShortArray {
        val stereoData = ShortArray(monoData.size * 2)
        for (i in monoData.indices) {
            stereoData[i * 2] = monoData[i]     // Left channel
            stereoData[i * 2 + 1] = monoData[i] // Right channel
        }
        return stereoData
    }
    
    /**
     * Converts ShortArray to ByteArray for AudioTrack.
     * 
     * @param shortArray ShortArray to convert
     * @return ByteArray representation
     */
    private fun ShortArray.toByteArray(): ByteArray {
        val byteArray = ByteArray(this.size * 2)
        for (i in this.indices) {
            val sample = this[i]
            byteArray[i * 2] = (sample.toInt() and 0xFF).toByte()
            byteArray[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return byteArray
    }
    
    /**
     * Enum representing the playback state.
     */
    enum class PlaybackState {
        STOPPED,
        PLAYING,
        PAUSED
    }
    
    /**
     * Data class representing the state of a channel.
     */
    data class ChannelState(
        val isActive: Boolean,
        val lastUpdateTime: Long
    )
    
    /**
     * Data class representing audio data for mixing.
     */
    data class AudioData(
        val channelId: String,
        val pcmData: ShortArray,
        val timestamp: Long
    )
    
    /**
     * Cleanup method to release resources.
     */
    fun cleanup() {
        stopPlayback()
        coroutineScope.cancel()
    }
}
