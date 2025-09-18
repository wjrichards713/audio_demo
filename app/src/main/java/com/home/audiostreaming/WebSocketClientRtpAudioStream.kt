package com.home.audiostreaming

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.Timer
import java.util.TimerTask

class WebSocketClientRtpAudioStream(
    private val context: Context,
    private var serverUrl: String) {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val sharedSocket = DatagramSocket().apply { broadcast = true }

    private var multiChannelPlayer: MultiChannelAudioPlayer? = null
    private var streamRecorder: StreamRecorder? = null
    private var keepAliveJob: Job? = null

    var rooms = mutableListOf<Room>()
    var mySocketID: String? = null
    var isConnected = false
    var ipAddress = "audio.redenes.org"
    var userName = ""
    var agencyName = ""
    var affiliationId = ""
    private var port: Int = 0

    private var onMessageListener: MessageListener? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        connectWebSocket()
    }

    fun addRoom(roomID: String): Room{
        var room = Room()
        room.type = ""
        room.roomID = roomID
        room.roomName = roomID
        room.agencyName = agencyName
        room.transmit = false
        room.top1 = ""
        room.top2 = ""
        room.top3 = ""
        room.top4 = ""
        room.top5 = ""
        room.isJoined = false
        room.isSelected = true
        room.duration = 0
        room.affiliationId = affiliationId
        rooms.add(room)
        return room
    }
    fun removeRoom(room: Room){
        rooms.remove(room)
    }
    // ------------------- Public APIs -------------------

    fun connectWebSocket() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, WebSocketListenerImpl())
    }

    fun disconnect() {
        stopKeepAlive()
        multiChannelPlayer?.stop()
        multiChannelPlayer = null
        streamRecorder?.stopRecording()
        streamRecorder = null
        sharedSocket.close()
        webSocket?.close(1000, "Disconnecting")
    }

    fun send(message: String) {
        Log.d("WebSocket", "send: $message")
        webSocket?.send(message)
    }

    fun setOnMessageListener(listener: MessageListener?) {
        onMessageListener = listener
    }

    fun sendConnectRequest(room: Room) {
        val request = JSONObject().apply {
            put("connect", JSONObject().apply {
                put("channel_id", room.roomID)
                put("affiliation_id", room.affiliationId)
                put("user_name", userName)
                put("agency_name", agencyName)
                put("time", System.currentTimeMillis() / 1000)
            })
        }
        room.isJoined = true
        send(request.toString())
        connectStreamSocket(room)
        
        // Add channel to multi-channel player
        multiChannelPlayer?.addChannel(room.roomID ?: "", room.volume)
    }

    fun sendTransmitStarted(room: Room) {
        val recordId = "${UUID.randomUUID()}.wav"
        val recorder = Room.Record(recordId, userName, System.currentTimeMillis(), null).apply {
            isRecorderDone = false
        }
        room.recorders.add(recorder)

        val request = JSONObject().apply {
            put("transmit_started", JSONObject().apply {
                put("affiliation_id", room.affiliationId)
                put("channel_id", room.roomID)
                put("file_id", recordId)
                put("time", System.currentTimeMillis() / 1000)
                put("user_name", userName)
                put("agency_name", agencyName)
            })
        }
        send(request.toString())

        if (!isRecording()) startRecording(recordId.removeSuffix(".wav"), room.roomID ?: "")
    }

    fun sendTransmitEnded(room: Room) {
        if (isRecording()) stopRecording()

        coroutineScope.launch {
            val request = JSONObject().apply {
                put("transmit_ended", JSONObject().apply {
                    put("affiliation_id", room.affiliationId)
                    put("channel_id", room.roomID)
                    put("time", System.currentTimeMillis() / 1000)
                    put("start_time", 0)
                    put("user_name", userName)
                })
            }
            send(request.toString())
        }
    }

    fun sendFileUploaded(name: String, room: Room, text: String) {
        val request = JSONObject().apply {
            put("file_uploaded", JSONObject().apply {
                put("channel_id", room.roomID)
                put("file_id", name)
                put("transcription", text)
                put("time", System.currentTimeMillis() / 1000)
            })
        }
        send(request.toString())
    }

    fun sendDisconnect(room: Room) {
        val request = JSONObject().apply {
            put("disconnect", JSONObject().apply {
                put("affiliation_id", room.affiliationId)
                put("channel_id", room.roomID)
                put("time", System.currentTimeMillis() / 1000)
            })
        }
        send(request.toString())
        
        // Remove channel from multi-channel player
        multiChannelPlayer?.removeChannel(room.roomID ?: "")
        
        disconnectStreamSocket(room)
    }
    fun disconnectStreamSocket(room: Room){
        val find = rooms.find { it.isJoined }
        if (find == null && keepAliveJob != null){// it means none of room is connected then stop keep alive
            stopKeepAlive()
        }
    }

    // ------------------- Stream Handling -------------------

    fun connectStreamSocket(room: Room) {
        if (keepAliveJob == null) startKeepAlive()
        initMultiChannelPlayer()
        initRecorder(room)
    }

    private fun initMultiChannelPlayer() {
        if (multiChannelPlayer == null) {
            coroutineScope.launch {
                multiChannelPlayer = MultiChannelAudioPlayer(sharedSocket, InetAddress.getByName(ipAddress), port).apply {
                    listener = object : MultiChannelAudioPlayer.MultiChannelAudioListener {
                        override fun onChannelStarted(channelId: String) {
                            Log.d("MultiChannel", "Channel started: $channelId")
                        }

                        override fun onChannelStopped(channelId: String) {
                            Log.d("MultiChannel", "Channel stopped: $channelId")
                        }

                        override fun onLog(message: String) {
                            Log.d("MultiChannel", message)
                        }

                        override fun onError(error: String) {
                            Log.e("MultiChannel", error)
                        }
                    }
                }
                multiChannelPlayer?.start()
            }
        }
    }

    private fun initRecorder(room: Room) {
        if (streamRecorder == null) {
            coroutineScope.launch {
                streamRecorder = StreamRecorder(sharedSocket, InetAddress.getByName(ipAddress), port, context)
            }
        }
    }

    fun startRecording(fileName: String, channelId: String) {
        streamRecorder?.startRecording(fileName, channelId)
    }

    fun stopRecording() {
        streamRecorder?.stopRecording()
    }

    fun isRecording(): Boolean = streamRecorder?.keepRecording ?: false
    
    fun setChannelVolume(channelId: String, volume: Float) {
        multiChannelPlayer?.setChannelVolume(channelId, volume)
    }
    
    fun getChannelStats(channelId: String): Map<String, Any>? {
        return multiChannelPlayer?.getChannelStats(channelId)
    }

    // ------------------- Keep Alive -------------------

    private fun startKeepAlive() {
        keepAliveJob = coroutineScope.launch {
            while (isActive) {
                try {
                    if (!sharedSocket.isClosed && !isRecording()) {
                        val buffer = """{"type":"KEEP_ALIVE"}""".toByteArray()
                        val packet = DatagramPacket(buffer, buffer.size, InetAddress.getByName(ipAddress), port)
                        sharedSocket.send(packet)
                        Log.d("WebSocket", "Sent keep-alive to $ipAddress:$port")
                    }
                } catch (e: Exception) {
                    Log.e("WebSocket", "Keep-alive error: ${e.message}", e)
                }
                delay(10_000)
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    // ------------------- WebSocket Listener -------------------

    inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            onMessageListener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("WebSocket", "receive: $text")
            onMessageListener?.onMessage(text)
            handleMessage(JSONObject(text))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            mySocketID = null
            Log.e("WebSocket", "onFailure: ${t.message}")
            onMessageListener?.onClose()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            onMessageListener?.onClose()
        }
    }

    private fun handleMessage(data: JSONObject) {
        when {
            data.has("udp_port") -> {
                port = data.optInt("udp_port")
                ipAddress = data.optString("udp_host")
            }
            data.has("transmit_started") -> {
                responseTransmitStarted(data.optJSONObject("transmit_started"))
            }
            data.has("transmit_ended") -> {
                responseTransmitEnded(data.optJSONObject("transmit_ended"))
            }
            data.has("file_uploaded") -> {
                responseFileUploaded(data.optJSONObject("file_uploaded"))
            }
            data.has("users_connected") -> {
                responseConnected(data)
            }
        }


    }

    // ------------------- Responses -------------------

    private fun responseTransmitStarted(data: JSONObject) { /* TODO */ }

    private fun responseTransmitEnded(data: JSONObject) { /* TODO */ }

    private fun responseFileUploaded(data: JSONObject) { /* TODO */ }

    private fun responseConnected(data: JSONObject) { /* TODO */ }

    // ------------------- Interfaces -------------------

    interface MessageListener {
        fun onMessage(message: String?)
        fun onOpen()
        fun onClose()
    }
}

