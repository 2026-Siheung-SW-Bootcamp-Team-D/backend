package com.siheungbootcamp.teamd.global.auth

data class ParticipantPrincipal(val participantId: Long, val boardId: String, val role: ParticipantRole)

enum class ParticipantRole { HOST, MEMBER }
