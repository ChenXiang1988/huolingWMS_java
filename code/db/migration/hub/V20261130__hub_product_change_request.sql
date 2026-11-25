-- 单仓字段变更申请（本次变更 design「数据模型与 DDL」，对应 tasks 1.4）。
-- 依据 specs product-archive-review（单仓不直接改主数据，经运营审核后写回）、00a INV-06、INV-19。
-- 申请归 Hub 权威库；单仓端只能发起，不能改写档案本身。

create table if not exists hub_product_change_request (
  id               bigint       primary key,
  request_no       varchar(32)  not null,
  archive_id       bigint       not null,
  archive_no       varchar(32)  not null,
  owner_id         bigint       not null,
  warehouse_id     bigint       not null,
  field_name       varchar(48)  not null,
  target_value     varchar(512) not null,
  reason           varchar(512) not null,
  status           varchar(16)  not null,
  review_reason    varchar(512),
  archive_version  bigint,
  version          bigint       not null default 0,
  created_at       timestamptz  not null,
  created_by       bigint       not null,
  updated_at       timestamptz  not null,
  updated_by       bigint       not null,
  constraint uk_hub_product_change_request_no unique (request_no),
  constraint ck_hub_product_change_request_status
    check (status in ('PENDING', 'APPROVED', 'REJECTED')),
  constraint ck_hub_product_change_request_rejected
    check (status <> 'REJECTED' or (review_reason is not null and length(btrim(review_reason)) > 0)),
  constraint ck_hub_product_change_request_approved
    check (status <> 'APPROVED' or archive_version is not null)
);

comment on table hub_product_change_request is '单仓主数据字段变更申请；同一档案同时只允许一条待审核（部分唯一索引）';
comment on column hub_product_change_request.warehouse_id is '发起仓库，数据域注入本仓，越仓申请返回 FORBIDDEN_SITE';
comment on column hub_product_change_request.field_name is '变更字段闭集名，闭集外按 PARAM_INVALID 拒绝';
comment on column hub_product_change_request.archive_version is '审核通过写回后的档案新版本号，驳回时为空';

-- 同一档案同时只允许一条待审核申请：并发发起只成功一条，其余按 STATE_INVALID 拒绝。
create unique index if not exists uk_hub_product_change_request_pending
  on hub_product_change_request (archive_id)
  where status = 'PENDING';

create index if not exists idx_hub_product_change_request_hub
  on hub_product_change_request (owner_id, warehouse_id, status, created_at desc);

create index if not exists idx_hub_product_change_request_site
  on hub_product_change_request (warehouse_id, created_at desc);

revoke delete, truncate on hub_product_change_request from public;
