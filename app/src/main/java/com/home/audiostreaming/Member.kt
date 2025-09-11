package com.home.audiostreaming


data class Member(
    val authorizationID: String,
    val agencyName: String,
    val name: String,

){
    var isTolking = false
    var isLastTolked = false

}

