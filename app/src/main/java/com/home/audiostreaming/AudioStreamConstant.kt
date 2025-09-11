package com.home.audiostreaming

class AudioStreamConstant() {
    companion object {
        const val SAMPLE_RATE = 48000
        const val HEADER_SIZE = 9 // Size of the header
        const val AUDIO_TYPE: Byte = 0x01 // Type identifier for audio buffer
        const val frameDurationMs = 40
        const val channel = 1
        const val AES_256_ALGO_KEY = "46dR4QR5KH7JhPyyjh/ZS4ki/3QBVwwOTkkQTdZQkC0="

    }
}
