-- 审核留痕与本地消息表（本次变更 design「数据模型与 DDL」，对应 tasks 1.3）。
-- 依据 22（T-05 本地消息表同事务写入）、26 3.6（改档留痕）、00a INV-18。
-- 审核通过必须在同一本地事务内完成：档案状态与版本、审核留痕、下行消息；
-- 三张写入同事务，任一步失败整体回滚，不留半成品（design「事务边界与并发」）。

create table if not exists hub_sku_review (
  id          bigint      primary key,
  archive_id  bigint      not null,
  archive_no  varchar(32) not null,
  owner_id    bigint      not null,
  action      varchar(16) not null,
  from_status varchar(16) not null,
  to_status   varchar(16) not null,
  reason      varchar(512),
  version     bigint      not null,
  operator_id bigint      not null,
  created_at  timestamptz not null,
  constraint ck_hub_sku_review_action check (action in ('APPROVE', 'REJECT')),
  constraint ck_hub_sku_review_from_status
    check (from_status in ('DRAFT', 'PENDING', 'ACTIVE', 'REJECTED', 'DISABLED')),
  constraint ck_hub_sku_review_to_status
    check (to_status in ('DRAFT', 'PENDING', 'ACTIVE', 'REJECTED', 'DISABLED')),
  constraint ck_hub_sku_review_reason
    check (action <> 'REJECT' or (reason is not null and length(btrim(reason)) > 0))
);

comment on table hub_sku_review is '审核动作与驳回原因留痕，只增不改；驳回必须带原因（specs product-archive-review）';
comment on column hub_sku_review.version is '审核完成后的档案版本号，与下行消息的事件版本一致';

create index if not exists idx_hub_sku_review_archive
  on hub_sku_review (archive_id, created_at desc);

revoke update, delete, truncate on hub_sku_review from public;

create table if not exists hub_outbox (
  id            bigint       primary key,
  event_id      varchar(64)  not null,
  event_type    varchar(48)  not null,
  aggregate_no  varchar(32)  not null,
  version       bigint       not null,
  owner_id      bigint       not null,
  payload_json  text         not null,
  status        varchar(16)  not null,
  retry_count   integer      not null default 0,
  next_retry_at timestamptz,
  last_error    varchar(256),
  created_at    timestamptz  not null,
  dispatched_at timestamptz,
  constraint uk_hub_outbox_event unique (event_id),
  constraint ck_hub_outbox_status check (status in ('PENDING', 'SENT', 'FAILED')),
  constraint ck_hub_outbox_retry check (retry_count >= 0)
);

comment on table hub_outbox is '本地消息表：与业务同事务写入，提交后异步投递，失败靠定时补偿（22 T-05）';
comment on column hub_outbox.event_id is '事件标识：档案系统编号加版本号，消费方按它去重';
comment on column hub_outbox.payload_json is '下行摘要：档案编号、版本号、数据时间戳与变更字段摘要，不含内部实体（14 外部边界）';

create index if not exists idx_hub_outbox_pending
  on hub_outbox (status, next_retry_at, created_at);

revoke delete, truncate on hub_outbox from public;
