package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.DeviceMgmtService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/**
 * 设备管理增强：应用目录库 / 一键分发 / 远程升级 / 权限管理 / 功能管理。
 *
 * 全部挂在 `/v1/devices/{deviceId}/mgmt` 下，借设备归属做鉴权（[DeviceMgmtService] 内部再校验一次）。
 * 目录库按 userId 维护，但入口统一带 deviceId 是为了复用既有的"设备属于当前家长"校验链路。
 */
@RestController
@RequestMapping("/v1/devices/{deviceId}/mgmt")
class DeviceMgmtController(private val deviceMgmtService: DeviceMgmtService) {

    // ---------- 应用目录库 ----------
    @PostMapping("/app-catalog", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadCatalog(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("packageName") packageName: String,
        @RequestParam("versionCode") versionCode: Long,
        @RequestParam("versionName", required = false) versionName: String?,
        @RequestParam("appName", required = false) appName: String?,
        @RequestParam("description", required = false) description: String?,
        @RequestParam("iconUrl", required = false) iconUrl: String?
    ) = ApiResponse.ok(
        deviceMgmtService.uploadCatalog(
            userId, deviceId, file, packageName, versionCode, versionName, appName, description, iconUrl
        )
    )

    @GetMapping("/app-catalog")
    fun listCatalog(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ) = ApiResponse.ok(deviceMgmtService.listCatalog(userId, deviceId))

    @DeleteMapping("/app-catalog/{catalogId}")
    fun deleteCatalog(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @PathVariable catalogId: String
    ): ApiResponse<*> {
        deviceMgmtService.deleteCatalog(userId, deviceId, catalogId)
        return ApiResponse.ok<Any?>(null)
    }

    // ---------- 一键分发 / 远程升级 ----------
    @PostMapping("/app-catalog/{catalogId}/distribute")
    fun distribute(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @PathVariable catalogId: String,
        @RequestBody(required = false) req: DistributeRequest?
    ): ApiResponse<*> {
        val force = req?.force ?: false
        return ApiResponse.ok(deviceMgmtService.distribute(userId, deviceId, catalogId, force))
    }

    @PostMapping("/app-catalog/{catalogId}/upgrade")
    fun upgrade(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @PathVariable catalogId: String
    ) = ApiResponse.ok(deviceMgmtService.upgrade(userId, deviceId, catalogId))

    @GetMapping("/app-catalog/upgrade-check")
    fun upgradeCheck(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ) = ApiResponse.ok(deviceMgmtService.upgradeCheck(userId, deviceId))

    // ---------- 权限管理 ----------
    @GetMapping("/permissions")
    fun getPermissions(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ) = ApiResponse.ok(deviceMgmtService.getPermissions(userId, deviceId))

    @PutMapping("/permissions")
    fun setPermissions(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: PermissionPolicyDto
    ) = ApiResponse.ok(deviceMgmtService.setPermissions(userId, deviceId, req))

    // ---------- 功能管理 ----------
    @GetMapping("/features")
    fun getFeatures(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ) = ApiResponse.ok(deviceMgmtService.getFeatures(userId, deviceId))

    @PutMapping("/features")
    fun setFeatures(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: FeaturePolicyDto
    ) = ApiResponse.ok(deviceMgmtService.setFeatures(userId, deviceId, req))
}
