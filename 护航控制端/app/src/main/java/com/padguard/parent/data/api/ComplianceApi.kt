package com.padguard.parent.data.api

import com.padguard.parent.data.model.AffectedDto
import com.padguard.parent.data.model.ApiResponse
import com.padguard.parent.data.model.AppealDto
import com.padguard.parent.data.model.AppealIdDto
import com.padguard.parent.data.model.DataExportDto
import com.padguard.parent.data.model.DeletionRequestDto
import com.padguard.parent.data.model.DeletionRequestReasonDto
import com.padguard.parent.data.model.DeletionTicketDto
import com.padguard.parent.data.model.GuardianConsentStatusDto
import com.padguard.parent.data.model.SubmitAppealRequestDto
import com.padguard.parent.data.model.Under14GuardianConsentRequestDto
import com.padguard.parent.data.model.WithdrawConsentRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 数据主体权利 / 合规 API（对应服务端 ParentDataRightsController，路径前缀 /v1/）。
 *
 * 全部走父端鉴权拦截器注入 userId，设备归属由服务端 ComplianceService 内部校验。
 * 这些端点是「家长能实际行使个人信息权利」的客户端闭环——服务端早已就绪，
 * 此前家长端零接线，导致导出/撤回/注销/申诉在法律意义上不可用。
 */
interface ComplianceApi {

    /** 撤回采集授权：deviceIds 为空表示名下全部设备。 */
    @POST("compliance/consent/withdraw")
    suspend fun withdrawConsent(@Body request: WithdrawConsentRequestDto): Response<ApiResponse<AffectedDto>>

    /** 恢复采集授权。 */
    @POST("compliance/consent/reinstate")
    suspend fun reinstateConsent(@Body request: WithdrawConsentRequestDto): Response<ApiResponse<AffectedDto>>

    /** 不满十四周岁监护人单独同意：家长申报出生日期 + 勾选同意文案版本，留痕不代表实名核验。 */
    @POST("compliance/guardian-consent/under14")
    suspend fun submitUnder14GuardianConsent(
        @Body request: Under14GuardianConsentRequestDto
    ): Response<ApiResponse<GuardianConsentStatusDto>>

    /** 单独同意状态回显。 */
    @GET("compliance/guardian-consent/under14/{deviceId}")
    suspend fun guardianConsentStatus(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<GuardianConsentStatusDto>>

    /** 导出本账号全部个人数据（结构化，字段与服务端 ComplianceService.exportData 对齐）。 */
    @GET("compliance/export")
    suspend fun exportData(): Response<ApiResponse<DataExportDto>>

    /** 提交账号注销请求，返回冷静期凭证。 */
    @POST("compliance/account/deletion/request")
    suspend fun requestDeletion(@Body request: DeletionRequestReasonDto): Response<ApiResponse<DeletionRequestDto>>

    /** 凭凭证确认注销。 */
    @POST("compliance/account/deletion/confirm")
    suspend fun confirmDeletion(@Body request: DeletionTicketDto): Response<ApiResponse<Unit>>

    /** 冷静期内取消注销。 */
    @POST("compliance/account/deletion/cancel")
    suspend fun cancelDeletion(@Body request: DeletionTicketDto): Response<ApiResponse<Unit>>

    /** 提交申诉。 */
    @POST("compliance/appeals")
    suspend fun submitAppeal(@Body request: SubmitAppealRequestDto): Response<ApiResponse<AppealIdDto>>

    /** 本人申诉列表。 */
    @GET("compliance/appeals")
    suspend fun listAppeals(): Response<ApiResponse<List<AppealDto>>>
}
