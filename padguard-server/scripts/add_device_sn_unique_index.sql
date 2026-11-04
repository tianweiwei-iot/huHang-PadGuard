-- =====================================================================
-- devices.device_sn 唯一索引：上线收口脚本（**不随 Flyway 自动执行**）
--
-- 为什么不做成 Flyway 迁移（Vxxx__...sql）：
--   Flyway 失败会**中止启动**。这份脚本的前提是"存量已干净"，
--   而存量是否干净只有生产库能回答 —— 把这样一个"可能必然失败"的 DDL 塞进启动链路，
--   等于用一个未知的数据问题把整个服务端锁死在启动阶段。
--   所以它保持手工执行：先诊断，确认干净，再建索引，失败也不影响服务可用性。
--
-- 执行顺序（缺一不可）：
--   1) psql ... -f scripts/diagnose_duplicate_device_sn.sql
--      ① 无结果、③ 为 0 才能继续；有结果先按脚本末尾的判读处理（合并台账 or 判定为撞号而放弃约束）
--   2) psql ... -f scripts/add_device_sn_unique_index.sql   ← 本文件
--   3) 验证：\d devices 里应出现 ux_devices_device_sn
--
-- 索引为什么是 partial（带 WHERE）：
--   `device_sn` 允许为空/null（只有 fingerprint 的设备、导入时缺列的设备）。
--   PostgreSQL 的普通唯一索引允许多个 NULL，但**不允许**多个空串，
--   而本项目历史上出现过空串写入，所以把空串一起排除在索引之外。
-- =====================================================================

-- ① 前置闸门：存量不干净就**主动报错**，绝不静默跳过。
--    静默跳过是最坏的结果：运维以为约束已生效，实际上并发去重仍只有进程内锁在挡。
DO $$
DECLARE
    dup_groups  bigint;
    blank_rows  bigint;
BEGIN
    SELECT count(*) INTO dup_groups FROM (
        SELECT device_sn FROM devices
        WHERE device_sn IS NOT NULL AND device_sn <> ''
        GROUP BY device_sn HAVING count(*) > 1
    ) t;
    SELECT count(*) INTO blank_rows FROM devices WHERE device_sn = '';

    IF dup_groups > 0 THEN
        RAISE EXCEPTION
            'device_sn 仍有 % 组重复，不能建唯一索引：先跑 diagnose_duplicate_device_sn.sql 并处理存量', dup_groups;
    END IF;
    IF blank_rows > 0 THEN
        RAISE EXCEPTION
            'devices 仍有 % 行 device_sn 为空串，不能建唯一索引：先 UPDATE devices SET device_sn = NULL WHERE device_sn = %L', blank_rows, '';
    END IF;
END $$;

-- ② 建索引（幂等：重复执行不报错）
CREATE UNIQUE INDEX IF NOT EXISTS ux_devices_device_sn
    ON devices (device_sn)
    WHERE device_sn IS NOT NULL AND device_sn <> '';

-- ③ 自检：把最终形态打出来，便于在部署记录里留痕
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'devices' AND indexname = 'ux_devices_device_sn';

-- =====================================================================
-- 建索引之后的行为变化（运维/客服需要知道）：
--   * 同一 SN 的第二条台账插入会抛唯一冲突，而不是静默多出一条。
--     正常路径不会走到这里：绑定/导入已按 SN 加键锁并在事务内先查后写；
--     真撞上说明存在"锁没覆盖到的入口"，此时**报错比静默分裂更好**——
--     家长看到一次失败可以重试，多出一条台账则是用量/策略永久分裂且无任何提示。
--   * 若未来要支持"同一 SN 允许多条历史台账"，必须改成
--     `(device_sn, user_id)` 联合唯一或 `(device_sn, fingerprint)` 联合唯一，
--     而不是删掉这个索引 —— 删掉等于回到"没有任何跨实例去重"的状态。
-- =====================================================================
