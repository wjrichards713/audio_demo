package com.home.audiostreaming

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.home.audiostreaming.databinding.ActivityMainBinding
import com.home.audiostreaming.databinding.ItemChannelBinding
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var webSocketManager:WebSocketClientRtpAudioStream? = null
    private lateinit var channelAdapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketManager?.let {
            it.disconnect()
            webSocketManager = null
        }
    }
    private fun init(){
        binding.editTextAffiliationId.setText(UUID.randomUUID().toString())
        channelAdapter = ChannelAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = channelAdapter

        webSocketDisconnected()
        initClickCallback()
    }
    private fun initClickCallback(){
        binding.buttonConnectWebSocket.setOnClickListener {
            if (webSocketManager != null){
                webSocketDisconnected()
            }else{
                var url = binding.editTextWebSocketUrl.text.toString()
                var userName = binding.editTextUserName.text.toString()
                var agencyName = binding.editTextAgencyName.text.toString()
                var affiliationID = binding.editTextAffiliationId.text.toString()
                fun isValid(): Boolean {
                    var isValid = true
                    if (url.isEmpty()){
                        isValid = false
                        binding.editTextWebSocketUrl.error = "Required"
                    }
                    else
                        binding.editTextWebSocketUrl.error = null
                    if (userName.isEmpty()){
                        isValid = false
                        binding.editTextUserName.error = "Required"
                    }
                    else
                        binding.editTextUserName.error = null
                    if (agencyName.isEmpty()){
                        isValid = false
                        binding.editTextAgencyName.error = "Required"
                    }
                    else
                        binding.editTextAgencyName.error = null
                    if (affiliationID.isEmpty()){
                        isValid = false
                        binding.editTextAffiliationId.error = "Required"
                    }
                    else
                        binding.editTextAffiliationId.error = null
                    return isValid
                }
               if (isValid()){
                   webSocketManager = WebSocketClientRtpAudioStream(this,url)
                   webSocketManager!!.setOnMessageListener(socketListener)
                   webSocketManager!!.userName = userName
                   webSocketManager!!.agencyName = agencyName
                   webSocketManager!!.affiliationId = affiliationID
                   binding.buttonConnectWebSocket.setText("Disconnect WebSocket")
                   binding.buttonConnectWebSocket.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
                   webSocketConnected()
               }
            }

        }
        binding.buttonAddChannel.setOnClickListener {
            if (webSocketManager != null && webSocketManager!!.isConnected){
                var channelId = binding.editTextChannelId.text.toString()
                if (channelId.isNotEmpty()){
                    channelAdapter.add(webSocketManager!!.addRoom(channelId))
                    binding.editTextChannelId.setText("")
                }
            }else{
                Toast.makeText(this,"Websocket not connected",Toast.LENGTH_SHORT).show()
            }
        }

    }
    private fun webSocketConnected(){
        binding.tvWebSocketStatus.text = "WebSocket connected"
        binding.tvWebSocketStatus.setTextColor(Color.parseColor("#4CAF50"))
        //binding.llChannels.isVisible = true
    }
    private fun webSocketDisconnected(){
        binding.tvWebSocketStatus.text = "WebSocket not connected"
        binding.tvWebSocketStatus.setTextColor(Color.parseColor("#F44336"))
        //binding.llChannels.isVisible = false
        webSocketManager?.let {
            it.disconnect()
            webSocketManager = null
            binding.buttonConnectWebSocket.setText("Connect WebSocket")
            binding.buttonConnectWebSocket.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4285F4"))
            channelAdapter.clear()
            binding.editTextServerPort.setText("")
            binding.editTextServerAddress.setText("")
        }

    }
    val socketListener = object : WebSocketClientRtpAudioStream.MessageListener {
        override fun onMessage(message: String?) {
            try {
                val data = JSONObject(message)
                when {
                    data.has("udp_port") -> {
                        var port = data.optInt("udp_port")
                        var ipAddress = data.optString("udp_host")
                        runOnUiThread {
                            binding.editTextServerPort.setText(port.toString())
                            binding.editTextServerAddress.setText(ipAddress)
                        }
                    }
                    data.has("transmit_started") -> {
                        data.optJSONObject("transmit_started")?.let { responseTransmitStarted(it) }
                    }
                    data.has("transmit_ended") -> {
                        data.optJSONObject("transmit_ended")?.let { responseTransmitEnded(it) }
                    }
                    data.has("file_uploaded") -> {
                    }
                    data.has("users_connected") -> {
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        override fun onOpen() {
            runOnUiThread {
                webSocketConnected()
            }
        }

        override fun onClose() {
            runOnUiThread {
                webSocketDisconnected()
            }

        }
    }
    fun transmitStart(room: Room){
        val isAudioRecorder = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!isAudioRecorder){
            checkRecAudioPermission()
            return
        }
        room.speaking = true
        webSocketManager!!.sendTransmitStarted(room)
    }
    fun transmitStopped(room: Room){
        webSocketManager!!.sendTransmitEnded(room)
        room.speaking = false
    }
    private fun responseTransmitStarted(data: JSONObject) {
        try {
            val room = Room.isRoomID(webSocketManager!!.rooms,data.optString("channel_id"))
            room?.let {
                room.producerIsTalking = true
                runOnUiThread {
                    channelAdapter.notifyDataSetChanged()
                }
            }


        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun responseTransmitEnded(data: JSONObject) {
        try {
            val room = Room.isRoomID(webSocketManager!!.rooms,data.optString("channel_id"))
            room?.let {
                room.producerIsTalking = false
                runOnUiThread {
                    channelAdapter.notifyDataSetChanged()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun checkRecAudioPermission() {
        requestRecAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    private val requestRecAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {

        } else {

        }
    }
    inner class ChannelAdapter() : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {
        private val channels = mutableListOf<Room>()

        fun add(model:Room){
            channels.add(model)
            notifyDataSetChanged()
        }
        fun remove(model:Room){
            channels.remove(model)
            notifyDataSetChanged()
        }
        fun clear(){
            channels.clear()
            notifyDataSetChanged()
        }
        inner class ChannelViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            val binding = ItemChannelBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ChannelViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            val channel = channels[position]
            holder.binding.tvChannelName.text = "Channel ID: ${channel.roomName}"
            holder.binding.llRoot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (channel.producerIsTalking) "#C6F8C8" else "#F6F6F6"))
            holder.binding.btnStartAudio.isVisible = !channel.producerIsTalking
            if (channel.isJoined){
                holder.binding.btnConnect.text = "Disconnect"
                holder.binding.btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
            }else{
                holder.binding.btnConnect.text = "Connect"
                holder.binding.btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3F51B5"))
            }
            if (channel.speaking){
                holder.binding.btnStartAudio.text = "Stop Audio"
                holder.binding.btnRemove.isVisible = false
            }else{
                holder.binding.btnStartAudio.text = "Start Audio"
                holder.binding.btnRemove.isVisible = true
            }
            holder.binding.btnRemove.setOnClickListener {
                if (channel.isJoined){
                    webSocketManager!!.sendDisconnect(channel)
                }
                webSocketManager?.let { it.removeRoom(channel) }
                remove(channel)
            }
            holder.binding.btnStartAudio.setOnClickListener {
                if (channel.isJoined){
                    if (channel.speaking){
                        transmitStopped(channel)
                    }
                    else{
                        transmitStart(channel)
                    }
                    notifyItemChanged(position)
                }else{
                    Toast.makeText(baseContext,"Channel not connected",Toast.LENGTH_SHORT).show()
                }

            }
            holder.binding.btnConnect.setOnClickListener {
                if (!webSocketManager!!.isConnected){
                    Toast.makeText(baseContext,"Websocket not connected",Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!channel.isJoined){
                    webSocketManager!!.sendConnectRequest(channel)
                    notifyItemChanged(position)
                }
                else{
                    if (channel.speaking){
                        transmitStopped(channel)
                    }
                    channel.isJoined = false
                    channel.producerIsTalking = false
                    webSocketManager!!.sendDisconnect(channel)
                    channel.members.clear()
                    notifyItemChanged(position)
                }
            }

        }

        override fun getItemCount() = channels.size


    }





}