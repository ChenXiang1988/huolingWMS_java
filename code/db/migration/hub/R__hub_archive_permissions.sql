-- 商品档案七个权限点与角色初始绑定（本次变更 tasks 1.11；05「商品档案新增七个权限点」、21 E-17）。
--
-- 为什么是 repeatable 脚本：权限点清单以代码为权威源（05「权限点写在代码里」），
-- 启动期 PermissionStartupSync 会把代码声明同步进 wms_permission；本脚本负责**初始绑定**，
-- 按（角色编码, 权限点编码）幂等补一次，已有绑定不动（运行时的权威路径是权限管理员的整体替换）。
--
-- 绑定口径取自 05「角色与模块的读写矩阵」的商品档案行：
--   主数据：主数据管理员「写」，其余运营端角色「读」；货主用户「写（仅商品档案）」；
--   单仓管理者写本仓变更申请、读本仓档案；现场作业员只读。
-- 角色 id 1007 至 1009 为本次新增的 SITE 与 OWNER 角色，1001 至 1006 见 R__hub_roles.sql。

insert into wms_permission (id, perm_code, module, status, created_at, updated_at)
values
  (2001, 'archive.owner.read',   'ARCHIVE', 'ENABLED', now(), now()),
  (2002, 'archive.owner.write',  'ARCHIVE', 'ENABLED', now(), now()),
  (2003, 'archive.hub.read',     'ARCHIVE', 'ENABLED', now(), now()),
  (2004, 'archive.hub.create',   'ARCHIVE', 'ENABLED', now(), now()),
  (2005, 'archive.hub.review',   'ARCHIVE', 'ENABLED', now(), now()),
  (2006, 'archive.site.read',    'ARCHIVE', 'ENABLED', now(), now()),
  (2007, 'archive.site.change',  'ARCHIVE', 'ENABLED', now(), now())
on conflict do nothing;

insert into wms_role (id, role_code, role_name, applies_to_client, status, version,
                      created_at, created_by, updated_at, updated_by)
values
  (1007, 'SITE_MANAGER',  '单仓管理者', 'SITE',  'ENABLED', 0, now(), 0, now(), 0),
  (1008, 'SITE_OPERATOR', '现场作业员', 'SITE',  'ENABLED', 0, now(), 0, now(), 0),
  (1009, 'OWNER_USER',    '货主用户',   'OWNER', 'ENABLED', 0, now(), 0, now(), 0)
on conflict (id) do update
set role_code = excluded.role_code,
    role_name = excluded.role_name,
    applies_to_client = excluded.applies_to_client,
    updated_at = now();

insert into wms_role_permission (role_id, permission_id)
select r.id, p.id
  from (values
    ('MASTER_DATA_ADMIN',              'archive.hub.read'),
    ('WAREHOUSE_NETWORK_ADMIN',        'archive.hub.read'),
    ('PERMISSION_ADMIN',               'archive.hub.read'),
    ('BILLING_SPECIALIST',             'archive.hub.read'),
    ('RECONCILIATION_CLERK',           'archive.hub.read'),
    ('CROSS_WAREHOUSE_DISPATCHER',     'archive.hub.read'),
    ('MASTER_DATA_ADMIN',              'archive.hub.create'),
    ('MASTER_DATA_ADMIN',              'archive.hub.review'),
    ('SITE_MANAGER',                   'archive.site.read'),
    ('SITE_MANAGER',                   'archive.site.change'),
    ('SITE_OPERATOR',                  'archive.site.read'),
    ('OWNER_USER',                     'archive.owner.read'),
    ('OWNER_USER',                     'archive.owner.write')
  ) as binding(role_code, perm_code)
  join wms_role r on r.role_code = binding.role_code
  join wms_permission p on p.perm_code = binding.perm_code
on conflict do nothing;
