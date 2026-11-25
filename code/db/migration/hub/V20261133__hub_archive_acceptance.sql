-- 商品档案写接口的受理记录（本次变更 tasks 1.9，design「幂等与异步事件」「数据模型与 DDL」）。
--
-- 契约「写接口统一响应」：所有写接口返回 202 与 acceptNo，结果由 API-015 至 API-017 受理查询返回。
-- 因此受理记录必须承载三样东西：受理号、受理时的业务状态快照、执行完成后的结果摘要或错误码。
--
-- 与 hub_idempotency 的分工：幂等表回答「这个键是不是首次受理」，本表回答「这次受理现在到哪一步」。
-- 两者在同一本地事务内写入（design「幂等与异步事件」），本表的唯一键与幂等表同口径，
-- 因此同一幂等键只会产生一条受理记录，重复提交只回放首次受理号。
--
-- 状态取值照契约「字段结构与枚举」：PROCESSING、SUCCEEDED、FAILED；未知值由数据库约束拒绝。
-- 受理记录是业务留痕：只增不改，只有状态与结果列随后更新，不授予删除权限（INV-18）。

create table if not exists hub_archive_acceptance (
  id              bigint       primary key,
  accept_no       varchar(32)  not null,
  api_code        varchar(16)  not null,
  scope_key       varchar(64)  not null,
  account_id      bigint       not null,
  idempotency_key varchar(64)  not null,
  status_snapshot varchar(16)  not null,
  accept_status   varchar(16)  not null,
  result_json     text,
  error_code      varchar(32),
  error_message   varchar(512),
  created_at      timestamptz  not null,
  updated_at      timestamptz  not null,
  constraint uk_hub_archive_acceptance_no unique (accept_no),
  constraint uk_hub_archive_acceptance_key unique (scope_key, api_code, idempotency_key),
  constraint ck_hub_archive_acceptance_status
    check (accept_status in ('PROCESSING', 'SUCCEEDED', 'FAILED')),
  constraint ck_hub_archive_acceptance_snapshot
    check (status_snapshot in ('DRAFT', 'PENDING', 'ACTIVE', 'REJECTED', 'DISABLED'))
);

comment on table hub_archive_acceptance is '写接口受理记录：同事务随幂等记录写入，结果由受理查询接口按 acceptNo 返回';
comment on column hub_archive_acceptance.scope_key is '受理作用域：货主端取归属货主，运营端取 HUB，单仓端取 SITE:<本仓>';
comment on column hub_archive_acceptance.status_snapshot is '受理时的业务状态快照，写接口响应只回这个值，不回执行结果';
comment on column hub_archive_acceptance.accept_status is 'PROCESSING 处理中、SUCCEEDED 成功、FAILED 失败（契约「字段结构与枚举」）';
comment on column hub_archive_acceptance.result_json is '执行完成后的结果摘要；处理中为空，失败时为空并写 error_code';
comment on column hub_archive_acceptance.error_code is '失败时的契约错误码，取值只来自错误码表';

create index if not exists idx_hub_archive_acceptance_scope
  on hub_archive_acceptance (scope_key, created_at desc);

create index if not exists idx_hub_archive_acceptance_status
  on hub_archive_acceptance (accept_status, created_at);

revoke delete, truncate on hub_archive_acceptance from public;
