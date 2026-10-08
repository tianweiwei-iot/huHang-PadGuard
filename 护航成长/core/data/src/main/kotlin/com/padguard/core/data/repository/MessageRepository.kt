package com.padguard.core.data.repository

import com.padguard.core.data.db.ParentMessageDao
import com.padguard.core.data.db.ParentMessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家长发布信息的本地仓库。
 *
 * 家长端下发的信息经 [com.padguard.core.engine.command.CommandExecutor] 落到本仓库，
 * 孩子端「消息」页（[com.padguard.child.ui.message.MessageListScreen]）直接读取，
 * 实现"发布的信息放入消息页、孩子随时可查看"。
 *
 * 去重：以 [ParentMessageEntity.serverMsgId] 唯一约束做 upsert，
 * 同一条消息经推送 + 轮询兜底重复下发时只更新不新增。
 */
@Singleton
class MessageRepository @Inject constructor(
    private val dao: ParentMessageDao
) {

    /** 全部家长消息（时间倒序），消息列表页消费 */
    fun observeAll(): Flow<List<ParentMessageEntity>> = dao.observeAll()

    /** 单条消息（消息详情页用） */
    fun observeByServerId(serverMsgId: String): Flow<ParentMessageEntity?> =
        dao.observeByServerId(serverMsgId)

    suspend fun getByServerId(serverMsgId: String): ParentMessageEntity? =
        dao.getByServerId(serverMsgId)

    /** 落库 / 更新一条消息 */
    suspend fun upsert(entity: ParentMessageEntity) = dao.upsert(entity)

    /** 标记已读（孩子打开详情后调用） */
    suspend fun markRead(serverMsgId: String) = dao.markRead(serverMsgId)

    /** 未读数量，消息 Tab 红点用 */
    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()
}
