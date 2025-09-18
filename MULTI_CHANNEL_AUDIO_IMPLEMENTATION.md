# Multi-Channel Audio Implementation

## Overview
This implementation provides smooth, synchronized multi-channel audio playback for your Android audio streaming application. The system addresses the choppy audio issues by implementing a comprehensive 7-step approach for handling multiple audio channels simultaneously.

## Architecture

### 1. Packet Reception
- **UDP packets** arrive containing JSON with channel ID and encrypted audio data
- **WebSocketManager** extracts the channel ID and Base64-encoded audio data
- **Audio data** is decrypted using AES/GCM encryption

### 2. Channel Routing
- Each audio packet is routed to its specific channel based on the channel ID
- The system maintains separate audio processing for each active channel
- Channels are managed through the `MultiChannelAudioPlayer` class

### 3. Opus Decoding
- Decrypted audio data is passed to the `MultiChannelAudioPlayer`
- Each channel has its own dedicated Opus decoder instance
- Opus-encoded audio is converted to raw PCM samples (48kHz, 16-bit, mono)
- Decoded audio is packaged into audio frames with timestamps

### 4. Jitter Buffer Management
- Each channel maintains its own queue (jitter buffer) of audio frames
- Frames are added to the queue as they arrive from decoding
- Buffer size is limited to prevent memory issues
- This helps smooth out network timing variations and packet delays

### 5. Unified Audio Processing
- A single background thread runs continuously at high priority
- Every 10 milliseconds, this thread processes all active channels
- It checks each channel's jitter buffer for available audio frames
- Uses `THREAD_PRIORITY_URGENT_AUDIO` for optimal performance

### 6. Per-Channel Playback
- Each channel has its own dedicated AudioTrack instance
- When audio frames are available, they're converted from mono to stereo
- Audio is written to the AudioTrack with precise timing synchronization
- All channels play simultaneously and stay synchronized

### 7. Timing Synchronization
- Uses Android's `AudioTimestamp` for precise playback timing
- All channels share the same timing reference to stay in sync
- Prevents audio drift between different channels
- Implements global reference clock for consistent timing

## Key Features

### MultiChannelAudioPlayer Class
- **Channel Management**: Add/remove channels dynamically
- **Volume Control**: Individual volume control per channel
- **Statistics**: Track packets received, dropped, and buffer status
- **Error Handling**: Comprehensive error handling and logging
- **Thread Safety**: Uses concurrent data structures for thread safety

### WebSocketClientRtpAudioStream Integration
- **Automatic Channel Management**: Channels are automatically added/removed when connecting/disconnecting
- **Volume Control API**: Methods to control individual channel volumes
- **Statistics API**: Access to channel performance statistics

### UI Enhancements
- **Volume Controls**: +/- buttons for each channel
- **Volume Display**: Shows current volume percentage
- **Real-time Updates**: UI updates when channels are connected/disconnected

## Performance Optimizations

### Threading
- **Packet Receiver**: High-priority thread for receiving UDP packets
- **Audio Processor**: High-priority thread for audio processing (10ms intervals)
- **UI Thread**: Separate thread for UI updates

### Memory Management
- **Jitter Buffer Limits**: Prevents memory overflow
- **Resource Cleanup**: Proper cleanup of AudioTracks and decoders
- **Efficient Data Structures**: Uses concurrent collections for thread safety

### Audio Quality
- **48kHz Sample Rate**: High-quality audio processing
- **16-bit PCM**: Standard audio format
- **Stereo Output**: Mono input converted to stereo for better audio experience
- **Buffer Sizing**: 4x minimum buffer size for smooth playback

## Usage

### Adding a Channel
```kotlin
multiChannelPlayer?.addChannel(channelId, volume)
```

### Removing a Channel
```kotlin
multiChannelPlayer?.removeChannel(channelId)
```

### Setting Volume
```kotlin
multiChannelPlayer?.setChannelVolume(channelId, volume)
```

### Getting Statistics
```kotlin
val stats = multiChannelPlayer?.getChannelStats(channelId)
```

## Expected Results

With this implementation, you should experience:

1. **Smooth Audio Playback**: No more choppy audio from multi-channels
2. **Synchronized Playback**: All channels stay in sync with each other
3. **Low Latency**: 10ms processing intervals for responsive audio
4. **Stable Performance**: Proper thread management and resource cleanup
5. **Individual Control**: Per-channel volume control and management
6. **Error Resilience**: Robust error handling and recovery

## Technical Specifications

- **Sample Rate**: 48,000 Hz
- **Bit Depth**: 16-bit
- **Channels**: Mono input, Stereo output
- **Processing Interval**: 10ms
- **Jitter Buffer Size**: 10 frames per channel
- **Audio Format**: PCM 16-bit
- **Encryption**: AES/GCM
- **Codec**: Opus

## Thread Priorities

- **Packet Receiver**: `THREAD_PRIORITY_URGENT_AUDIO`
- **Audio Processor**: `THREAD_PRIORITY_URGENT_AUDIO`
- **UI Updates**: Main thread

This implementation should provide the smooth, synchronized multi-channel audio experience you're looking for. The system is designed to handle multiple channels simultaneously while maintaining audio quality and synchronization.
