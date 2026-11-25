-- 运营端六个角色，随代码版本初始化（05、19）。
-- 可重复执行：角色 id 固定，重复执行只更新名称与端标识，不产生新行。

insert into wms_role (id, role_code, role_name, applies_to_client, status, version, created_at, created_by, updated_at, updated_by)
values
  (1001, 'MASTER_DATA_ADMIN',       '主数据管理员',   'HUB', 'ENABLED', 0, now(), 0, now(), 0),
  (1002, 'WAREHOUSE_NETWORK_ADMIN', '仓网管理员',     'HUB', 'ENABLED', 0, now(), 0, now(), 0),
  (1003, 'PERMISSION_ADMIN',        '权限管理员',     'HUB', 'ENABLED', 0, now(), 0, now(), 0),
  (1004, 'BILLING_SPECIALIST',      '计费专员',       'HUB', 'ENABLED', 0, now(), 0, now(), 0),
  (1005, 'RECONCILIATION_CLERK',    '对账员',         'HUB', 'ENABLED', 0, now(), 0, now(), 0),
  (1006, 'CROSS_WAREHOUSE_DISPATCHER', '跨仓调度员',  'HUB', 'ENABLED', 0, now(), 0, now(), 0)
on conflict (id) do update
set role_code = excluded.role_code,
    role_name = excluded.role_name,
    applies_to_client = excluded.applies_to_client,
    updated_at = now();
