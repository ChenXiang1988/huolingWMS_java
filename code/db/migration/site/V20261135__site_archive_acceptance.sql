-- 单仓端写接口受理记录（本次变更 tasks 1.10、契约 API-011 与 API-017）。
--
-- 与 Hub 侧 hub_archive_acceptance 同口径但不同库：单仓的写接口只有变更申请一枚，
-- 因此把幂等要素（fingerprint）直接放在受理表里，不再单开一张幂等表——
-- 唯一键（site_id, api_code, idempotency_key）已经保证「同一键只产生一条受理记录」。
--
-- 状态取值照契约「字段结构与枚举」：PROCESSING、SUCCEEDED、FAILED；
-- 结果由 API-017 按受理号返回，处理中不伪造结果。

create table if not exists site_archive_acceptance (
  id              bigint       primary key,
  accept_no       varchar(32)  not null,
  site_id         bigint       not null,
  api_code        varchar(16)  not null,
  idempotency_key varchar(64)  not null,
  fingerprint     varchar(64)  not null,
  status_snapshot varchar(16)  not null,
  accept_status   varchar(16)  not null,
  result_json     text,
  error_code      varchar(32),
  error_message   varchar(512),
  created_at      timestamptz  not null,
  updated_at      timestamptz  not null,
  constraint uk_site_archive_acceptance_no unique (accept_no),
  constraint uk_site_archive_acceptance_key unique (site_id, api_code, idempotency_key),
  constraint ck_site_archive_acceptance_status
    check (accept_status in ('PROCESSING', 'SUCCEEDED', 'FAILED')),
  constraint ck_site_archive_acceptance_snapshot check (status_snapshot in ('PENDING'))
);

comment on table site_archive_acceptance is '单仓写接口受理记录：受理号加状态快照，结果由 API-017 查询';
comment on column site_archive_acceptance.fingerprint is '请求指纹，用于同键异体判定（契约「幂等约定」）';
comment on column site_archive_acceptance.status_snapshot is '受理时的业务状态快照；变更申请受理时固定为 PENDING';

create index if not exists idx_site_archive_acceptance_site
  on site_archive_acceptance (site_id, created_at desc);

revoke delete, truncate on site_archive_acceptance from public;
