package com.padguard.server.support

import com.padguard.server.domain.User
import com.padguard.server.repository.UserRepository
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

/**
 * 只用于 JVM 单测：给「本用例不测账号复核」的推送用例一个恒为 active 的仓储替身。
 *
 * 生产代码不允许再用可空依赖表达"单测不测鉴权"——那会把 fail-open 带进生产。
 */
object PushTestUsers {
    fun permissive(): UserRepository {
        val repo = mock(UserRepository::class.java)
        `when`(repo.findById(anyString())).thenAnswer { inv ->
            Optional.of(User(id = inv.arguments[0] as String, status = "active", tokenVersion = 0))
        }
        return repo
    }
}
