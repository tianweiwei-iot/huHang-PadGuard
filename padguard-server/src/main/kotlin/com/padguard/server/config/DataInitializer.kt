package com.padguard.server.config

import com.padguard.server.domain.User
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.Passwords
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

/** 演示数据：内置一个家长账号，便于 P0 直接联调 */
@Component
class DataInitializer(
    private val userRepository: UserRepository
) {
    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        val phone = "13800000000"
        if (!userRepository.existsByPhone(phone)) {
            userRepository.save(
                User(
                    id = UUID.randomUUID().toString(),
                    phone = phone,
                    passwordHash = Passwords.encode("admin123"),
                    nickname = "演示家长",
                    role = "PARENT",
                    sceneType = "FAMILY",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }
}
