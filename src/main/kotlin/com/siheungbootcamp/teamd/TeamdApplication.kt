package com.siheungbootcamp.teamd

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class TeamdApplication

fun main(args: Array<String>) {
	runApplication<TeamdApplication>(*args)
}
