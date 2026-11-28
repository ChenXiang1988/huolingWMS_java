-- 货主档案两枚权限点与角色初始绑定（本次变更 tasks 1.16；契约 E-20、05「角色与模块的读写矩阵」）。
--
-- 为什么是 repeatable 脚本：权限点清单以代码为权威源（05「权限点写在代码里」），
-- 启动期 PermissionStartupSync 会把代码声明同步进 wms_permission；本脚本负责**初始绑定**，
-- 按（角色编码, 权限点编码）幂等补一次，已有绑定不动（运行时的权威路径是权限管理员的整体替换）。
--
-- module 取 'OWNER'（对应 24 的 M01 货主），与 PermissionStartupSync#moduleOf 的例外映射同值：
-- 脚本先执行、启动再同步，两处取值必须一致，否则新环境与老环境的模块名会分叉。
--
-- 绑定口径取自 05「角色与模块的读写矩阵」的主数据列：
--   写（两项）：主数据管理员；
--   读（一项）：主数据管理员、仓网管理员、权限管理员、计费专员、对账员、跨仓调度员。
-- 权限管理员必须可读：API-020 的第二个用途是「开通货主端账号时指定货主」，
-- 缺这个点会让权限管理员开不出货主端账号（契约 1.8 API-020「鉴权」）。

insert into wms_permission (id, perm_code, module, status, created_at, updated_at)
values
  (2011, 'owner.hub.read',  'OWNER', 'ENABLED', now(), now()),
  (2012, 'owner.hub.write', 'OWNER', 'ENABLED', now(), now())
on conflict do nothing;

insert into wms_role_permission (role_id, permission_id)
select r.id, p.id
  from (values
    ('MASTER_DATA_ADMIN',           'owner.hub.read'),
    ('MASTER_DATA_ADMIN',           'owner.hub.write'),
    ('WAREHOUSE_NETWORK_ADMIN',     'owner.hub.read'),
    ('PERMISSION_ADMIN',            'owner.hub.read'),
    ('BILLING_SPECIALIST',          'owner.hub.read'),
    ('RECONCILIATION_CLERK',        'owner.hub.read'),
    ('CROSS_WAREHOUSE_DISPATCHER',  'owner.hub.read')
  ) as binding(role_code, perm_code)
  join wms_role r on r.role_code = binding.role_code
  join wms_permission p on p.perm_code = binding.perm_code
on conflict do nothing;

-- API-020 的权限点由 archive.hub.create 改为 owner.hub.read（契约 1.8 API-020「BREAKING」）：
-- 现持有代建点的角色若没有新点，改造上线后代会 403。这里按「凡持旧点者补新点」幂等抹平，
-- 不撤销 archive.hub.create 本身——代建仍然需要它。
insert into wms_role_permission (role_id, permission_id)
select distinct rp.role_id, target.id
  from wms_role_permission rp
  join wms_permission legacy on legacy.id = rp.permission_id
                           and legacy.perm_code = 'archive.hub.create'
  join wms_permission target on target.perm_code = 'owner.hub.read'
on conflict do nothing;
