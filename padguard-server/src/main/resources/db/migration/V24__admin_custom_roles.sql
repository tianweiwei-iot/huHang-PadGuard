-- 后台自定义角色：运营可在「角色权限」页新建角色并配置权限矩阵。
-- 内置角色继续由代码常量 AdminRoles.ROLES 定义（权威默认值，便于代码审查与升级）；
-- 自定义角色存于此表，二者在 AdminPermissionService.listRoles() 合并后，
-- 对前端矩阵渲染与服务端权限判定统一生效。兼容 H2 与 PostgreSQL，语句幂等。

CREATE TABLE IF NOT EXISTS admin_roles (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    privileges_json TEXT,
    created_at BIGINT,
    updated_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_admin_roles_name ON admin_roles(name);
