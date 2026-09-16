package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.User
import com.padguard.server.dto.LoginResponse
import com.padguard.server.dto.LoginSmsRequest
import com.padguard.server.dto.LoginPasswordRequest
import com.padguard.server.dto.TokenResponse
import com.padguard.server.dto.UserDto
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwt: JwtTokenProvider
) {
    fun loginWithPassword(req: LoginPasswordRequest): LoginResponse {
        val user = userRepository.findByPhone(req.phone)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "用户不存在", Audience.PARENT)
        if (!Passwords.matches(req.password, user.passwordHash)) {
            throw BizException(ParentErr.UNAUTHORIZED, "密码错误", Audience.PARENT)
        }
        return buildLoginResponse(user)
    }

    fun loginWithSms(req: LoginSmsRequest): LoginResponse {
        val user = userRepository.findByPhone(req.phone)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "用户不存在", Audience.PARENT)
        return buildLoginResponse(user)
    }

    fun refresh(refreshToken: String): TokenResponse {
        val userId = try {
            jwt.parseUserId(refreshToken)
        } catch (e: Exception) {
            throw BizException(ParentErr.UNAUTHORIZED, "refresh 失效", Audience.PARENT)
        }
        return TokenResponse(jwt.createAccessToken(userId), jwt.createRefreshToken(userId))
    }

    private fun buildLoginResponse(user: User): LoginResponse {
        val access = jwt.createAccessToken(user.id)
        val refresh = jwt.createRefreshToken(user.id)
        return LoginResponse(
            user = UserDto(user.id, user.phone, user.nickname, user.avatar, user.role, user.sceneType),
            token = access,
            refreshToken = refresh
        )
    }
}
