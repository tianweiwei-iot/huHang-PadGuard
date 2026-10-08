package com.padguard.server.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** 应用目录库条目（输出） */
data class AppCatalogDto(
    val id: String,
    val packageName: String,
    val appName: String?,
    val versionCode: Long,
    val versionName: String?,
    /** 可下载安装的 APK 地址（/v1/files/{fileId}） */
    val apkUrl: String,
    val fileName: String?,
    val sizeBytes: Long,
    val iconUrl: String?,
    val description: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/** 分发 / 升级任务（输出） */
data class AppDistributionDto(
    val id: String,
    val deviceId: String,
    val catalogId: String?,
    val packageName: String,
    val versionCode: Long,
    val kind: String,
    val msgId: String?,
    val status: String,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val finishedAt: Long?
)

/**
 * 权限策略（输出 / 输入共用）。
 *
 * permissions 为"应当被授予"的权限名集合；服务端按设备当前已存集合做差量，
 * 只下发真正变化的项（新增=授予，移除=收回）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PermissionPolicyDto(
    /** 目标包；缺省为孩子端自身 */
    val targetPackage: String? = null,
    val permissions: List<String> = emptyList()
)

/**
 * 功能策略（输出 / 输入共用）。
 * features：featureKey -> 是否启用（启用=施加该限制/关闭该能力）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FeaturePolicyDto(
    val features: Map<String, Boolean> = emptyMap()
)

/**
 * 升级检查结果（输出）：目录里某包名的最新版本 vs 设备已装版本。
 * upgradeAvailable = catalog 最新 versionCode > 设备已装 versionCode。
 */
data class UpgradeCheckItem(
    val catalogId: String,
    val packageName: String,
    val appName: String?,
    val catalogVersionCode: Long,
    val catalogVersionName: String?,
    val installedVersionCode: Long,
    val installedVersionName: String?,
    val upgradeAvailable: Boolean
)

/** 一键分发 / 升级请求：指定目录条目 id，下发到目标设备 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DistributeRequest(
    /** 目录库条目 id；必填 */
    val catalogId: String,
    /** 强制下发（即便不是升级也照发）；缺省 false 时升级接口会先做版本比较 */
    val force: Boolean = false
)
