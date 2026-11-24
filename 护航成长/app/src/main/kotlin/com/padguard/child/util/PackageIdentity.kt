package com.padguard.child.util

import com.padguard.child.BuildConfig

/**
 * 判定 [pkg] 是否为「护航守护」自身包。
 *
 * ## 严禁用裸 `startsWith("com.padguard.child")`
 * 本项目守护是**单 APK、单包名**形态，不存在合法的「子包」。
 * 用前缀判断会留下可被任意第三方 APK 利用的越权缺口：
 * - `com.padguard.child.evil` 是 `com.padguard.child` 的前缀延伸 → 被当成守护自身；
 * - `com.padguard.child1` 同样满足 `startsWith` → 也被当成守护自身。
 * 命中"自身"意味着应用锁 / 时段管控 / 防卸载全部失效——等于把家长管控核心闸门
 * 交给一个**可安装**的第三方包名。因此只允许**精确相等**。
 *
 * ## 自身包名必须随构建变体取，不能写死
 * `debug` 构建带 `applicationIdSuffix = ".debug"`，设备上真实包名是
 * `com.padguard.child.debug`。历史实现把自身包名写死为 release 的
 * `com.padguard.child`，精确相等在 debug 设备上恒为 false：自家锁屏页、门禁页、
 * **屏幕采集授权页**全被当成第三方应用顶回桌面（bounce / no-permission overlay），
 * MediaProjection 系统授权框活不过 0.2 秒，授权拿不到，家长端实时看屏一帧都收不到
 * （Android 10 设备无 takeScreenshot，看屏只能走投影通道，症状最明显）。
 *
 * 对照值取 AGP 按变体生成的 [BuildConfig.APPLICATION_ID]（debug 含 `.debug` 后缀），
 * 既保持精确相等的安全红线，又不会在联调包上把自己判成外人。
 * 攻击者依旧无法安装同名包（签名不同会被系统拒绝）。
 */
fun isOwnPackage(pkg: String): Boolean = pkg == BuildConfig.APPLICATION_ID
