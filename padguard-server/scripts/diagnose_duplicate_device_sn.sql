-- =====================================================================
-- device_sn 重复诊断（只读，不修改任何数据）
--
-- 目的：为「devices.device_sn 是否加唯一约束」提供决策依据。
-- 这个约束从第十三轮起一直搁置，原因只有一个：**唯一约束不是性能开关，是语义承诺**——
-- 它声明"一个 SN 全库最多一条台账"。如果存量数据里已经有重复，
-- 加约束会直接失败；如果强行清理，又必须先回答"重复到底是什么意思"：
--
--   A. 同一台平板建了两条台账（超时重试 / 重装后匹配失败）→ 该合并，
--      保留数据更全、更新更近的那条，另一条下线；
--   B. 两台不同的平板报了同一个 SN（厂商 SN 撞号 / 测试机抄了序列号）→ 合并会丢设备，
--      必须保留两条，约束反而加不上去；
--   C. 某条 SN 为 NULL 或空串 → 唯一约束需排除 NULL（PostgreSQL 默认允许多个 NULL，
--      但空串会被约束拦住，需要先清理或改收集逻辑）。
--
-- 用法：psql / 任意 SQL 客户端连上生产库直接执行。
-- 每段独立，可按需执行；全部只读。
-- =====================================================================

-- ① 重复 SN 总览：有多少个 SN 出现在多条台账上
--    结果为空 = 存量干净，加唯一约束不会失败（仍需决策 B 场景是否可能再现）。
SELECT device_sn,
       COUNT(*)                AS device_count,
       COUNT(DISTINCT user_id) AS distinct_parents,   -- 大于 1 → B 场景（跨家长撞号）
       MIN(created_at)         AS first_seen,
       MAX(created_at)         AS last_seen
FROM devices
WHERE device_sn IS NOT NULL AND device_sn <> ''
GROUP BY device_sn
HAVING COUNT(*) > 1
ORDER BY device_count DESC, last_seen DESC;

-- ② 重复明细：每一行重复 SN 的全部台账
--    判断 A/B 的关键就在 distinct_parents：
--    同一 user_id 的多条 → 几乎可以确定是 A（同一台平板的重复台账），可合并；
--    不同 user_id       → 是 B 或"设备转手"，转手也是合法的两条历史台账（旧的带旧 user_id）。
--    注意：本项目解绑/换绑会改 user_id 而不是新建行（见 DeviceService.unbind），
--    所以"跨家长"更可能是转手，需要结合 created_at / last_online_at 人工判断。
SELECT d.id, d.device_sn, d.user_id, u.phone        AS owner_phone,
       d.model, d.os_version,
       d.created_at, d.last_online_at, d.online_status
FROM devices d
LEFT JOIN users u ON u.id = d.user_id
WHERE d.device_sn IN (
    SELECT device_sn FROM devices
    WHERE device_sn IS NOT NULL AND device_sn <> ''
    GROUP BY device_sn HAVING COUNT(*) > 1
)
ORDER BY d.device_sn, d.created_at;

-- ③ 空值/空串：加唯一约束前必须清掉空串（NULL 可以留给约束，空串不行）
SELECT COUNT(*) AS blank_sn_devices
FROM devices
WHERE device_sn = '';

-- ④ 指纹维度交叉验证：同一 fingerprint 是否也有重复
--    fingerprint 与 SN 是两条独立的设备识别线。若 SN 无重复而 fingerprint 有，
--    说明绑定匹配逻辑（matchExistingDevice）一直在走 fingerprint 分支，
--    SN 唯一约束的紧迫性下降、fingerprint 的反而上升。
SELECT fingerprint,
       COUNT(*)                AS device_count,
       COUNT(DISTINCT user_id) AS distinct_parents
FROM devices
WHERE fingerprint IS NOT NULL AND fingerprint <> ''
GROUP BY fingerprint
HAVING COUNT(*) > 1
ORDER BY device_count DESC;

-- ⑤ 若确认全部是 A 场景（同家长重复台账）并决定合并，先预演受影响面：
--    这些表都以 device_id 外挂，合并前必须知道要搬多少行（统计，不执行搬迁）。
--    保留规则：每组 SN 中 created_at 最新的一条保留（id 兜底防同刻并列），
--    其余全部是"要搬数据 / 下线的台账"。表清单请与当前表结构核对后再执行合并。
WITH dup AS (
    SELECT id FROM (
        SELECT d.id,
               ROW_NUMBER() OVER (
                   PARTITION BY d.device_sn
                   ORDER BY d.created_at DESC, d.id DESC
               ) AS rn
        FROM devices d
        WHERE d.device_sn IS NOT NULL AND d.device_sn <> ''
          AND d.device_sn IN (
              SELECT device_sn FROM devices
              WHERE device_sn IS NOT NULL AND device_sn <> ''
              GROUP BY device_sn HAVING COUNT(*) > 1
          )
    ) t
    WHERE rn > 1   -- rn=1 的是每组保留的那条
)
SELECT 'alerts'          AS table_name, COUNT(*) AS rows_to_move FROM alerts         WHERE device_id IN (SELECT id FROM dup)
UNION ALL
SELECT 'usage_sessions',              COUNT(*) FROM usage_sessions  WHERE device_id IN (SELECT id FROM dup)
UNION ALL
SELECT 'policies',                    COUNT(*) FROM policies        WHERE device_id IN (SELECT id FROM dup);

-- =====================================================================
-- 结论判读
--   ① 无结果 + ③ 为 0  → 可以安全加唯一约束（建议 `CREATE UNIQUE INDEX ... WHERE device_sn IS NOT NULL`）；
--   ① 有结果且全部同家长 → 按 A 合并（保留 created_at 最新、last_online_at 非空的一条），
--                          合并完成后再加约束；
--   ① 有结果且存在跨家长 → 先人工逐条判断（转手 or 撞号），撞号场景下**不能**加约束，
--                          应改为"SN + fingerprint 联合唯一"或维持现状 + 绑定匹配兜底
--                          （DeviceBindLocks 已阻断并发重复，是当前的实际防线）。
-- =====================================================================
