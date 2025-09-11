package com.example.opuslib.utils;

import android.util.Log;

public class OpusDecoder {

    public native boolean nativeInitDecoder(int samplingRate, int numberOfChannels, int frameSize);

    public native int nativeDecodeBytes(byte[] in, short[] out);

    public native boolean nativeReleaseDecoder();

    static {
        try {
            System.loadLibrary("senz");
            Log.d("LibraryLoad", "Library loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e("LibraryLoad", "Failed to load library: " + e.getMessage());
        }
    }

    public void init(int sampleRate, int channels, int frameSize) {
        this.nativeInitDecoder(sampleRate, channels, frameSize);
    }

    public int decode(byte[] encodedBuffer, short[] buffer) {
        int decoded = this.nativeDecodeBytes(encodedBuffer, buffer);

        return decoded;
    }

    public void close() {
        this.nativeReleaseDecoder();
    }

}
