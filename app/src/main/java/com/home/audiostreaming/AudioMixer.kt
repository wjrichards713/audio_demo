package com.home.audiostreaming

import android.util.Log

/**
 * AudioMixer handles the mixing of multiple PCM audio streams into a single output.
 * This class provides efficient mixing with proper scaling to prevent clipping.
 */
class AudioMixer {
    
    companion object {
        private const val TAG = "AudioMixer"
        private const val MAX_CHANNELS = 4
        private const val MAX_SAMPLE_VALUE = Short.MAX_VALUE.toFloat()
        private const val MIN_SAMPLE_VALUE = Short.MIN_VALUE.toFloat()
    }
    
    /**
     * Mixes multiple PCM audio streams into a single output buffer.
     * 
     * @param channels Array of audio channels, each containing PCM data as ShortArray
     * @param outputLength Length of the output buffer to generate
     * @param channelVolumes Optional volume levels for each channel (0.0f to 1.0f)
     * @return Mixed audio data as ShortArray
     */
    fun mixChannels(
        channels: Array<ShortArray?>,
        outputLength: Int,
        channelVolumes: FloatArray = FloatArray(MAX_CHANNELS) { 1.0f }
    ): ShortArray {
        val output = ShortArray(outputLength)
        
        // Initialize output buffer with silence
        for (i in output.indices) {
            output[i] = 0
        }
        
        // Mix each channel into the output
        for (channelIndex in channels.indices) {
            val channelData = channels[channelIndex] ?: continue
            val volume = if (channelIndex < channelVolumes.size) channelVolumes[channelIndex] else 1.0f
            val clampedVolume = volume.coerceIn(0.0f, 1.0f)
            
            // Mix this channel's data into the output
            for (i in 0 until minOf(outputLength, channelData.size)) {
                val sample = (channelData[i].toFloat() * clampedVolume).toInt()
                output[i] = (output[i].toInt() + sample).toShort()
            }
        }
        
        // Apply scaling to prevent clipping
        return scaleOutput(output)
    }
    
    /**
     * Mixes multiple PCM audio streams with proper scaling to prevent clipping.
     * This method is more sophisticated and handles dynamic scaling.
     * 
     * @param channels Array of audio channels, each containing PCM data as ShortArray
     * @param outputLength Length of the output buffer to generate
     * @param channelVolumes Optional volume levels for each channel (0.0f to 1.0f)
     * @return Mixed audio data as ShortArray
     */
    fun mixChannelsAdvanced(
        channels: Array<ShortArray?>,
        outputLength: Int,
        channelVolumes: FloatArray = FloatArray(MAX_CHANNELS) { 1.0f }
    ): ShortArray {
        val output = FloatArray(outputLength)
        
        // Initialize output buffer with silence
        for (i in output.indices) {
            output[i] = 0.0f
        }
        
        // Mix each channel into the output using floating point for better precision
        for (channelIndex in channels.indices) {
            val channelData = channels[channelIndex] ?: continue
            val volume = if (channelIndex < channelVolumes.size) channelVolumes[channelIndex] else 1.0f
            val clampedVolume = volume.coerceIn(0.0f, 1.0f)
            
            // Mix this channel's data into the output
            for (i in 0 until minOf(outputLength, channelData.size)) {
                output[i] += channelData[i].toFloat() * clampedVolume
            }
        }
        
        // Convert back to ShortArray with proper scaling
        return convertToShortArray(output)
    }
    
    /**
     * Scales the output to prevent clipping while maintaining audio quality.
     * Uses a simple peak limiting approach.
     */
    private fun scaleOutput(input: ShortArray): ShortArray {
        var maxSample = 0
        var minSample = 0
        
        // Find the peak values
        for (sample in input) {
            val sampleValue = sample.toInt()
            maxSample = maxOf(maxSample, sampleValue)
            minSample = minOf(minSample, sampleValue)
        }
        
        val maxAbs = maxOf(kotlin.math.abs(maxSample), kotlin.math.abs(minSample))
        
        // If no clipping would occur, return as is
        if (maxAbs <= Short.MAX_VALUE) {
            return input
        }
        
        // Scale down to prevent clipping
        val scaleFactor = Short.MAX_VALUE.toFloat() / maxAbs
        val output = ShortArray(input.size)
        
        for (i in input.indices) {
            output[i] = (input[i].toFloat() * scaleFactor).toInt().toShort()
        }
        
        Log.d(TAG, "Applied scaling factor: $scaleFactor")
        return output
    }
    
    /**
     * Converts float array to ShortArray with proper scaling and clipping prevention.
     */
    private fun convertToShortArray(input: FloatArray): ShortArray {
        val output = ShortArray(input.size)
        
        // Find the maximum absolute value for scaling
        var maxAbs = 0.0f
        for (sample in input) {
            maxAbs = maxOf(maxAbs, kotlin.math.abs(sample))
        }
        
        // Calculate scale factor to prevent clipping
        val scaleFactor = if (maxAbs > MAX_SAMPLE_VALUE) {
            MAX_SAMPLE_VALUE / maxAbs
        } else {
            1.0f
        }
        
        // Convert with scaling
        for (i in input.indices) {
            val scaledSample = input[i] * scaleFactor
            output[i] = scaledSample.coerceIn(MIN_SAMPLE_VALUE, MAX_SAMPLE_VALUE).toInt().toShort()
        }
        
        if (scaleFactor < 1.0f) {
            Log.d(TAG, "Applied scaling factor: $scaleFactor")
        }
        
        return output
    }
    
    /**
     * Creates a silence buffer of the specified length.
     * 
     * @param length Length of the silence buffer
     * @return ShortArray filled with silence (zeros)
     */
    fun createSilence(length: Int): ShortArray {
        return ShortArray(length) { 0 }
    }
    
    /**
     * Applies volume scaling to a PCM buffer.
     * 
     * @param input Input PCM data
     * @param volume Volume level (0.0f to 1.0f)
     * @return Volume-adjusted PCM data
     */
    fun applyVolume(input: ShortArray, volume: Float): ShortArray {
        val clampedVolume = volume.coerceIn(0.0f, 1.0f)
        val output = ShortArray(input.size)
        
        for (i in input.indices) {
            output[i] = (input[i].toFloat() * clampedVolume).toInt().toShort()
        }
        
        return output
    }
}
