-- 货主档案主表、编号序列与存量补录清单（本次变更 tasks 1.3；design「数据模型与 DDL」）。
--
-- 依据：契约 1.5 字段结构、1.8 API-020；design 的 OI-01 至 OI-09；00a 的 INV-18（不物理删除）。
-- 主键沿用 11 的口径：雪花由应用生成，存量档案沿用原 owner_id，使账号与商品档案的引用无需改写。
-- 时间列一律 timestamptz 存 UTC（D38 的日切口径不适用：货主档案无计费日切）。
--
-- 脚本内统一用 if not exists / create or replace / drop trigger if exists，
-- 使本地重建与手工重跑都安全（tasks 1.3 的「可重复执行」）。

-- ownerName 模糊筛选（%keyword%）无法走 btree，依赖 pg_trgm 的 gin 索引；
-- PostgreSQL 13 起该扩展为 trusted，库 owner 可自行创建（回写登记已注明实例前置）。
create extension if not exists pg_trgm;

-- 编号序列：'OW' || to_char(nextval(...), 'FM000000')，FM 只补零不截断，序号超过六位自然变长。
create sequence if not exists seq_wms_owner_no start with 1 increment by 1;

create table if not exists wms_owner (
  id                bigint       primary key,
  owner_no          varchar(32)  not null,
  owner_name        varchar(128) not null,
  contact_name      varchar(64)  not null,
  contact_phone     varchar(32)  not null,
  credit_code       varchar(18)  not null,
  status            varchar(16)  not null default 'ACTIVE',
  status_action     varchar(16)  not null default 'CREATE',
  status_reason     varchar(200),
  status_changed_at timestamptz  not null,
  status_changed_by bigint       not null,
  version           bigint       not null default 0,
  created_at        timestamptz  not null,
  created_by        bigint       not null,
  updated_at        timestamptz  not null,
  updated_by        bigint       not null,
  constraint ck_wms_owner_status        check (status in ('ACTIVE', 'DISABLED')),
  constraint ck_wms_owner_status_action check (status_action in ('CREATE', 'DISABLE', 'ENABLE')),
  constraint ck_wms_owner_reason        check ((status_action = 'CREATE') = (status_reason is null)),
  constraint ck_wms_owner_no_format     check (owner_no ~ '^OW[A-Z0-9]{6,30}$'),
  constraint ck_wms_owner_name          check (btrim(owner_name) <> '' and char_length(owner_name) <= 128),
  constraint ck_wms_owner_contact_name  check (btrim(contact_name) <> '' and char_length(contact_name) <= 64),
  constraint ck_wms_owner_contact_phone check (contact_phone ~ '^[0-9+() -]{6,32}$'),
  constraint ck_wms_owner_credit_code   check (credit_code ~ '^[0-9A-HJ-NP-RT-UW-Y]{18}$'),
  constraint ck_wms_owner_version       check (version >= 0)
);

comment on table wms_owner is '货主档案主表，Hub 为权威源（M01 主数据）；不物理删除，停用以状态表达（INV-18）';
comment on column wms_owner.id is '主键，雪花由应用生成；存量档案沿用原 owner_id，使账号与商品档案引用无需改写';
comment on column wms_owner.owner_no is '货主编号，系统生成且全局唯一，创建后不可变（OI-01）';
comment on column wms_owner.owner_name is '货主名称，允许重复，不作为任何唯一性条件（OI-05）';
comment on column wms_owner.contact_name is '联系人，必填（OI-03）';
comment on column wms_owner.contact_phone is '联系电话，只校验长度与字符集，不做号段校验';
comment on column wms_owner.credit_code is '统一社会信用代码，18 位大写，全局唯一且停用档同样占位（OI-04）';
comment on column wms_owner.status is '状态闭集 ACTIVE/DISABLED，不表达合同是否生效（OI-02、D33）';
comment on column wms_owner.status_action is '最近一次状态变更动作 CREATE/DISABLE/ENABLE（21 E-19）';
comment on column wms_owner.status_reason is '状态变更原因：CREATE 必空，DISABLE/ENABLE 必填（ck_wms_owner_reason 绑死）';
comment on column wms_owner.version is '版本号，建档为 0，此后每次成功写入递增一（OI-09）';

-- 索引：每条对应一条具体查询，没有查询支撑的不建；status 低基数不单独建索引。
create unique index if not exists uk_wms_owner_no
  on wms_owner (owner_no);

-- 无谓词的完整唯一索引：货主停用后同一法人不允许再建第二条档案，停用档继续占位（design「软删除与唯一键」）。
create unique index if not exists uk_wms_owner_credit_code
  on wms_owner (credit_code);

create index if not exists idx_wms_owner_name_trgm
  on wms_owner using gin (owner_name gin_trgm_ops);

create index if not exists idx_wms_owner_sort_name
  on wms_owner (owner_name, owner_no);

create index if not exists idx_wms_owner_sort_updated
  on wms_owner (updated_at desc, owner_no);

-- 编号与主键创建后不可变；状态只允许 可用→停用(DISABLE) 与 停用→可用(ENABLE)；版本必须逐次递增一。
-- 触发器只挡「绕过应用的写」，正常路径仍由聚合与 kernel 守卫先在应用层拒绝，两层同一口径。
create or replace function wms_owner_guard() returns trigger as $$
begin
  if new.id <> old.id or new.owner_no <> old.owner_no then
    raise exception '货主主键与货主编号创建后不可变更';
  end if;
  if new.status is distinct from old.status then
    if not ((old.status = 'ACTIVE'   and new.status = 'DISABLED' and new.status_action = 'DISABLE')
         or (old.status = 'DISABLED' and new.status = 'ACTIVE'   and new.status_action = 'ENABLE')) then
      raise exception '货主状态只允许 可用→停用(DISABLE) 与 停用→可用(ENABLE)';
    end if;
  end if;
  if new.version <> old.version + 1 then
    raise exception '货主档案版本号必须逐次递增一';
  end if;
  return new;
end;
$$ language plpgsql;

drop trigger if exists trg_wms_owner_guard on wms_owner;
create trigger trg_wms_owner_guard before update on wms_owner
  for each row execute function wms_owner_guard();

-- INV-18：数据不物理删除。业务账号不授予 delete 与 truncate，删除一律走状态停用。
revoke delete, truncate on wms_owner from public;

-- 存量 owner_id 的补录清单（迁移用，不进业务链路；补录留痕保留在库内，不删表）。
-- 四项留空：信用代码、联系人、联系电话在存量数据里没有来源，只能人工逐条核对（18 第 47 行）。
-- 列级校验放在 V20261138 里做，本表不加 CHECK：补录过程中允许半成品行，由 1138 集中报错并指出来源。
create table if not exists wms_owner_import (
  owner_id      bigint       primary key,
  owner_name    varchar(128),
  contact_name  varchar(64),
  contact_phone varchar(32),
  credit_code   varchar(18),
  imported_at   timestamptz,
  created_at    timestamptz  not null default now(),
  updated_at    timestamptz  not null default now()
);

comment on table wms_owner_import is '存量货主补录清单：V20261137 生成候选行，V20261138 校验齐备后写入 wms_owner';
comment on column wms_owner_import.owner_id is '沿用原 owner_id，导入 wms_owner 时作为主键，保持既有引用不变';
comment on column wms_owner_import.imported_at is '回填时间；为空即待补录，V20261138 依此判定并回写';
