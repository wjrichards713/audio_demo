package com.home.audiostreaming

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Base64
import android.util.Log
import com.example.opuslib.utils.OpusEncoder
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class StreamRecorder(
    private val socket: DatagramSocket,
    private val address: InetAddress,
    private val port: Int,
    private val context: Context
) : Runnable {


    interface StreamRecorderListener {
        fun onRecordingStart()
        fun onRecordingStop()
        fun onLog(message: String)
        fun onError(error: String)
    }

    private val opusEncoder = OpusEncoder()
    private var recordingThread: Thread? = null
    var listener: StreamRecorderListener? = null
    var keepRecording = false

    //      FPSCounter fpsCounter = new FPSCounter("SamplingLoop::run()");
    var wavWriter: WavWriter = WavWriter(AudioStreamConstant.SAMPLE_RATE)

    var recordedFileName: String? = null
    var channelID = ""
    fun startRecording(recordFilename: String?,channelId: String) {
        recordedFileName = recordFilename
        channelID = channelId
        if (recordingThread == null) {
            keepRecording = true


            recordingThread = Thread(this).apply { start() }
        }
    }

    fun stopRecording() {
        if (recordedFileName != null)
            wavWriter.stop()
        keepRecording = false
        recordingThread?.join() // Wait for the thread to finish
        recordingThread = null
    }
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null


    val SAMPLE_RATE: Int = AudioStreamConstant.SAMPLE_RATE
    val pTime: Int = 40 // packetization times in ms
    val FRAME_RATE: Int = 1000 / pTime
    val SAMPLES_PER_SECOND: Int = SAMPLE_RATE / FRAME_RATE
    val FRAME_SIZE: Int = SAMPLES_PER_SECOND * 2
    val BUF_SIZE: Int = FRAME_SIZE / 2
    override fun run() {

        val samplesPerFrame = (AudioStreamConstant.SAMPLE_RATE * AudioStreamConstant.frameDurationMs).toInt() / 1000 // 320 samples
        val bufferSize = samplesPerFrame * AudioStreamConstant.channel* 2 / 2
        Log.e("AUDIO_CONFIGURATION", "frame size ${(FRAME_SIZE / 2)}, Buffer size = $BUF_SIZE")

        // Init OpusEncoder
        opusEncoder.init(AudioStreamConstant.SAMPLE_RATE, AudioStreamConstant.channel, FRAME_SIZE / 2)

        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        /*val bufferSize = AudioRecord.getMinBufferSize(
            AudioStreamManager.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).takeIf { it > 0 } ?: AudioStreamManager.SAMPLE_RATE * 2*/



        listener?.onLog("Buffer size = $bufferSize")

        // initialize audio recorder
        val minBufSize = AudioRecord.getMinBufferSize(
            AudioStreamConstant.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioStreamConstant.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize
        )

        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
                if (noiseSuppressor != null) noiseSuppressor!!.enabled = true
            } catch (e: Exception) { Log.e("TAG", "[initRecorder] unable to init noise suppressor: $e") }
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                automaticGainControl = AutomaticGainControl.create(record.audioSessionId)
                if (automaticGainControl != null) automaticGainControl!!.enabled = true
            } catch (e: Exception) { Log.e("TAG", "[initRecorder] unable to init automatic gain control: $e") }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            val errorMessage = "AudioRecord can't initialize!"
            Log.e("AUDIO", errorMessage)
            listener?.onError(errorMessage)
            return
        }

        record.startRecording()
        listener?.onRecordingStart()

        val keyAES = SecretKeySpec(Base64.decode(AudioStreamConstant.AES_256_ALGO_KEY, Base64.DEFAULT), "AES")

        if (recordedFileName != null)
            wavWriter.start(recordedFileName,context)
        val recordedData = ShortArray(BUF_SIZE)
        val encodedData = ByteArray(2024)
        Log.d("AUDIO", "Started Recording read size ${BUF_SIZE}")

        while (keepRecording) {
            try {
                val numberOfBytes = record.read(recordedData, 0, recordedData.size)
                if (numberOfBytes > 0){
                    listener?.onLog("read buffer = $numberOfBytes")
                    if (recordedFileName != null)
                        wavWriter.pushAudioShort(recordedData, numberOfBytes) // Maybe move this to another thread?

                    val timestamp = System.currentTimeMillis()
                    Log.i("RTPAudio", "raw data = ${recordedData.contentToString()}")

                    val size = opusEncoder.encode(recordedData, encodedData)
                    //val encoded = pcmToOpus(audioBuffer)
                    Log.i("RTPAudio", "opus encode data = ${encodedData.contentToString()}")

                    if (size > 0 ){
                        val encryptCodec = encryptAES(Arrays.copyOfRange(encodedData, 0, size),keyAES)
                        Log.i("RTPAudio", "encrpt data = ${encryptCodec.contentToString()}")

                        encryptCodec?.let {
                            val base64Encoded = Base64.encodeToString(encryptCodec, Base64.DEFAULT)
                            Log.i("RTPAudio", "base64 data = ${base64Encoded}")

                            val jsonObj = JSONObject()
                            jsonObj.put("data",base64Encoded)
                            jsonObj.put("type","audio")
                            jsonObj.put("channel_id",channelID)
                            val finalData = jsonObj.toString().toByteArray(Charsets.UTF_8)
                            Log.i("RTPAudio", "${jsonObj}")

                            Thread {
                                try {
                                    val packet = DatagramPacket(finalData, finalData.size, address, port)
                                    socket.send(packet)
                                    listener?.onLog("Audio data sent length: ${packet.length} ")
                                    Log.i("RTPAudio", "Packet sent: ${packet.length}")
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                    listener?.onError("IOException: ${e.localizedMessage}")
                                }
                            }.start()
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                keepRecording = false // Stop recording on error
                listener?.onError("Exception: ${e.localizedMessage}")
            }
        }

        record.stop()
        record.release()
        opusEncoder.nativeReleaseEncoder()
        Log.d("AUDIO", "Recording stopped")
        listener?.onRecordingStop()
    }
    fun encryptAES(plainText: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // Initialization vector
        val encryptedBytes = cipher.doFinal(plainText)

        // Combine IV and encrypted data into one byte array for transmission
        return iv + encryptedBytes
    }


}
