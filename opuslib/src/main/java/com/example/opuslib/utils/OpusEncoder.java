package com.example.opuslib.utils;

import android.util.Log;

public class OpusEncoder {

    public native boolean nativeInitEncoder(int samplingRate, int numberOfChannels, int frameSize);

    public native int nativeEncodeBytes(short[] in, byte[] out);

    public native boolean nativeReleaseEncoder();

    static {
        try {
            System.loadLibrary("senz");
            Log.d("LibraryLoad", "Library loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e("LibraryLoad", "Failed to load library: " + e.getMessage());
        }
    }

    public void init(int sampleRate, int channels, int frameSize) {
        this.nativeInitEncoder(sampleRate, channels, frameSize);
    }

    public int encode(short[] buffer, byte[] out) {
        int encoded = this.nativeEncodeBytes(buffer, out);

        return encoded;
    }

    public void close() {
        this.nativeReleaseEncoder();
    }

}
