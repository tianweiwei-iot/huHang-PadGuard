/* ============================================================
   PadGuard 管控后台 · Mock 数据层（已清空）
   ------------------------------------------------------------
   历史说明：本文件原本包含一整套"生成式演示数据"（128 个家长、
   210 台设备、管理员 / 订阅 / 订单 / 营收 / 服务端指标 / 审计 / 告警），
   用于在后台真实接口未落地前让界面可演示。

   自后台真实接口（/v1/admin/**）在服务端落地后，这些假数据已失去意义，
   且会**严重误导运营**（看到的是编造的家长与营收，而非真实业务）。
   因此演示数据已全部删除，仅保留一个空结构，供 api.js 在「实时接口
   不可用」时安全降级（返回空集合而非编造数据），避免界面崩溃。

   真实数据统一由 padguard-server 提供；后台默认已切换为 Live 模式
   （见 api.js 的 config.useMock = false）。
   ============================================================ */
(function (global) {
  const PG = global.PG || (global.PG = {});

  // 仅保留空结构 + 安全降级所需的辅助函数；不含任何生成的演示数据。
  PG.Mock = {
    PLANS: [],
    ROLES: [],
    parents: [],
    devices: [],
    admins: [],
    subscriptions: [],
    orders: [],
    revenue: [],
    server: { status: "UNKNOWN", ready: false, db: "UNKNOWN", uptimeMs: 0, startedAt: "", jvmHeapUsedMb: 0, jvmHeapMaxMb: 0, threads: 0, requestsPerMin: 0, errorRate: "0.00", dbConnections: "0/0", instances: [], logs: [] },
    audit: [],
    alerts: [],
    roleById: () => undefined,
    planById: () => undefined,
    parentByPhone: () => undefined,
  };
})(window);
