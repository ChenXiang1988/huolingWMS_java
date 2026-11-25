-- 单仓生效档案缓存（本次变更 design「数据模型与 DDL」，对应 tasks 1.5）。
-- 依据 D12（主数据权威源在 Hub，Site 只存副本）、design「幂等与异步事件」（按事件标识去重、摘带数据时间戳）。
-- 本表每个仓一套独立库（D08），因此仓标识仍是显式列，便于同一实例内跨仓核对与回放。

create table if not exists site_sku_snapshot (
  id                 bigint       primary key,
  warehouse_id       bigint       not null,
  owner_id           bigint       not null,
  archive_id         bigint       not null,
  archive_no         varchar(32)  not null,
  owner_code         varchar(64)  not null,
  name               varchar(128) not null,
  barcode            varchar(64)  not null,
  base_unit          varchar(16)  not null,
  storage_condition  varchar(32)  not null,
  category           varchar(64),
  specification      varchar(128),
  brand              varchar(64),
  status             varchar(16)  not null,
  version            bigint       not null,
  event_id           varchar(64)  not null,
  data_timestamp     timestamptz  not null,
  applied_at         timestamptz  not null,
  constraint uk_site_sku_snapshot_archive unique (warehouse_id, archive_no),
  constraint uk_site_sku_snapshot_event unique (event_id),
  constraint ck_site_sku_snapshot_status
    check (status in ('ACTIVE', 'DISABLED')),
  constraint ck_site_sku_snapshot_version check (version > 0)
);

comment on table site_sku_snapshot is '单仓生效档案缓存，权威源在 Hub；单仓不写主数据，只读本表并通过变更申请回写';
comment on column site_sku_snapshot.version is '已应用的权威版本号，端间一致性基准';
comment on column site_sku_snapshot.event_id is '下行事件标识（档案编号加版本号），按它去重，重复投递不重复应用';
comment on column site_sku_snapshot.data_timestamp is '数据时间戳，降级与待刷新提示必须带上，不把缓存伪装成权威结果';

create index if not exists idx_site_sku_snapshot_warehouse
  on site_sku_snapshot (warehouse_id, status, name);

create index if not exists idx_site_sku_snapshot_owner
  on site_sku_snapshot (owner_id, warehouse_id);
