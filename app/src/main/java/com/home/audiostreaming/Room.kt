package com.home.audiostreaming

import java.io.Serializable

class Room {
    var roomID: String? = null
    var roomName: String? = null
    var agencyName: String? = null
    var hostedBy: String? = null
    var toneAlert: Boolean = false
    var description: String? = null
    var duration: Long = -1
    var patchedRoom = mutableListOf<String>()
    var top1: String? = null
    var top2: String? = null
    var top3: String? = null
    var top4: String? = null
    var top5: String? = null
    var type: String = ""
    var transmit: Boolean? = null
    var members = mutableListOf<Member>()
    var speaking = false
    var isSelected = false
    var producerIsTalking = false
    var affiliationId: String = ""

    var volume = 1.0f
    var speakerType = "center"

    var isJoined = false
    var recorders = mutableListOf<Record>()

    data class Record(
        val recordID: String,
        val username: String,
        val startTime: Long,
        var endTime: Long?,
    ) : Serializable {
        var isSelected = false
        var isRecorderDone = false
        var isPlaying = false
        var text: String? = null
    }

    companion object {
        fun isRoomID(list: List<Room>, searchTerm: String): Room? {
            return list.find { it.roomID == searchTerm }
        }

        fun isSpeakingElse(list: List<Room>, searchTerm: String): Boolean {
            return list.any { it.roomID != searchTerm && it.speaking }
        }
    }
}


