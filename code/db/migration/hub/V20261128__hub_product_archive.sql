-- 商品档案主表（本次变更 design「数据模型与 DDL」，对应 tasks 1.2）。
-- 依据 26（商品档案）、00a（INV-06、INV-18）、22（状态登记）。
-- 主键雪花由应用生成，不用序列；时间列一律 timestamptz 存 UTC。
-- 脚本按 Flyway 版本化执行；语句内部统一用 if not exists 与 create or replace，
-- 使本地重建与手工重跑都安全（tasks 1.2 的「可重复执行」）。

create table if not exists hub_sku_archive (
  id                      bigint       primary key,
  owner_id                bigint       not null,
  archive_no              varchar(32)  not null,
  owner_code              varchar(64)  not null,
  name                    varchar(128) not null,
  barcode                 varchar(64)  not null,
  base_unit               varchar(16)  not null,
  storage_condition       varchar(32)  not null,
  category                varchar(64),
  specification           varchar(128),
  brand                   varchar(64),
  barcode_type            varchar(32),
  package_spec            varchar(64),
  package_conversion_rate numeric(18, 6),
  net_weight              numeric(18, 6),
  gross_weight            numeric(18, 6),
  volume                  numeric(18, 6),
  length                  numeric(18, 6),
  width                   numeric(18, 6),
  height                  numeric(18, 6),
  high_value              boolean      not null default false,
  hazardous               boolean      not null default false,
  fragile                 boolean      not null default false,
  batch_managed           boolean      not null default false,
  serial_managed          boolean      not null default false,
  shelf_life_days         integer,
  image_url               varchar(512),
  remark                  varchar(512),
  status                  varchar(16)  not null,
  disabled_flag           boolean      not null default false,
  version                 bigint       not null default 0,
  activated_at            timestamptz,
  data_timestamp          timestamptz  not null,
  created_at              timestamptz  not null,
  created_by              bigint       not null,
  updated_at              timestamptz  not null,
  updated_by              bigint       not null,
  constraint uk_hub_sku_archive_no unique (archive_no),
  constraint ck_hub_sku_archive_status
    check (status in ('DRAFT', 'PENDING', 'ACTIVE', 'REJECTED', 'DISABLED')),
  constraint ck_hub_sku_archive_disabled_flag
    check ((status = 'DISABLED') = disabled_flag),
  constraint ck_hub_sku_archive_package_rate
    check (package_conversion_rate is null or package_conversion_rate > 0),
  constraint ck_hub_sku_archive_shelf_life
    check (shelf_life_days is null or shelf_life_days > 0)
);

comment on table hub_sku_archive is '商品档案主表，Hub 为权威源；归属货主创建后不可变（INV-06、26 3.1）';
comment on column hub_sku_archive.owner_id is '归属货主，唯一隔离维度；更新时由触发器拦住变更';
comment on column hub_sku_archive.archive_no is '系统编号，创建时生成，全局唯一，不随提交改变';
comment on column hub_sku_archive.owner_code is '货主系统编码，货主内唯一（判重条件排除草稿与停用）';
comment on column hub_sku_archive.status is '状态闭集：DRAFT/PENDING/ACTIVE/REJECTED/DISABLED（放行 22 状态登记）';
comment on column hub_sku_archive.disabled_flag is '停用标记：与状态 DISABLED 同步，并与唯一索引条件一致';
comment on column hub_sku_archive.version is '版本号，每次成功变更递增一，下行去重依据';
comment on column hub_sku_archive.data_timestamp is '数据时间戳，端间一致性基准，下行摘要携带';

-- 货主内判重：编码与条码各自唯一。判重条件排除草稿与停用，与 Kernel 的 SkuArchiveDedupRule 同一口径：
-- 草稿态允许重复（货主正在改），停用后同编码可被新草稿使用（本次变更 design「强制手段分层」）。
create unique index if not exists uk_hub_sku_archive_owner_code
  on hub_sku_archive (owner_id, owner_code)
  where disabled_flag = false and status <> 'DRAFT';

create unique index if not exists uk_hub_sku_archive_owner_barcode
  on hub_sku_archive (owner_id, barcode)
  where disabled_flag = false and status <> 'DRAFT';

create index if not exists idx_hub_sku_archive_owner_status
  on hub_sku_archive (owner_id, status, updated_at desc);

create index if not exists idx_hub_sku_archive_updated
  on hub_sku_archive (updated_at desc, id desc);

create index if not exists idx_hub_sku_archive_hub_list
  on hub_sku_archive (status, owner_id, updated_at desc);

-- 归属货主与系统编号创建后不可变更：数据库层只允许插入，更新时直接报错（INV-06、26 3.1）。
-- 应用层同样不接受归属字段，两层都不放行，任何一侧绕过都被拦住。
create or replace function hub_sku_archive_freeze_identity() returns trigger as $$
begin
  if new.owner_id <> old.owner_id then
    raise exception '归属货主创建后不可变更（INV-06）';
  end if;
  if new.archive_no <> old.archive_no then
    raise exception '系统编号创建后不可变更';
  end if;
  return new;
end;
$$ language plpgsql;

drop trigger if exists trg_hub_sku_archive_freeze_identity on hub_sku_archive;
create trigger trg_hub_sku_archive_freeze_identity
  before update on hub_sku_archive
  for each row execute function hub_sku_archive_freeze_identity();

-- INV-18：数据不物理删除。业务账号不授予 delete 与 truncate，删除一律走状态停用。
revoke delete, truncate on hub_sku_archive from public;
