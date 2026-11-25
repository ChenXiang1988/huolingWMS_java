-- 单仓端主数据字段变更申请的本地暂存与上行状态（本次变更 tasks 1.10）。
--
-- 为什么单仓还要留一份：契约 API-011 先返回受理号，API-014 再按本仓查询申请状态与驳回原因；
-- 权威表在 Hub 库（hub_product_change_request），单仓库拿不到，因此本表承载「本仓已受理的申请」
-- 与上行进度，审核结果由 Hub 的下行事件回写（design「事件与回调」）。
--
-- 唯一约束与 Hub 侧同口径：同一档案同时只允许一条待审核申请（部分唯一索引，条件为 PENDING）。
-- 上行字段（upload_status / upload_attempts / next_retry_at）让投递器按退避重试，不阻塞受理。
-- last_event_id 用来去重：同一条下行事件重复投递时不再改一次状态（至少一次投递）。

create table if not exists site_archive_change_request (
  id              bigint       primary key,
  request_no      varchar(32)  not null,
  accept_no       varchar(32)  not null,
  site_id         bigint       not null,
  archive_id      varchar(32)  not null,
  archive_version bigint       not null,
  owner_id        bigint       not null,
  change_field    varchar(32)  not null,
  target_value    varchar(512) not null,
  reason          varchar(512) not null,
  status          varchar(16)  not null,
  review_reason   varchar(512),
  archive_reviewed_version bigint,
  last_event_id   varchar(64),
  upload_status   varchar(16)  not null,
  upload_attempts integer      not null default 0,
  last_error      varchar(256),
  next_retry_at   timestamptz,
  created_at      timestamptz  not null,
  updated_at      timestamptz  not null,
  constraint uk_site_archive_change_request_no unique (request_no),
  constraint ck_site_archive_change_request_status
    check (status in ('PENDING', 'APPROVED', 'REJECTED')),
  constraint ck_site_archive_change_request_upload
    check (upload_status in ('PENDING', 'UPLOADED', 'FAILED')),
  constraint ck_site_archive_change_request_version check (archive_version >= 0),
  constraint ck_site_archive_change_request_reject_reason
    check (status <> 'REJECTED' or (review_reason is not null and length(btrim(review_reason)) > 0))
);

comment on table site_archive_change_request is '单仓变更申请：本仓受理与上行进度，权威记录在 Hub';
comment on column site_archive_change_request.archive_id is '档案系统编号，与 Hub 的 archive_no 同源';
comment on column site_archive_change_request.archive_version is '发起申请时本仓缓存的档案版本，用于提示缓存落后';
comment on column site_archive_change_request.last_event_id is '最后一条已应用的下行事件标识，按它去重';
comment on column site_archive_change_request.upload_status is 'PENDING 待上行、UPLOADED 已写入 Hub、FAILED 上行失败';

-- 同一档案同时只允许一条待审核申请（与 hub_product_change_request 的口径一致）。
create unique index if not exists uk_site_archive_change_request_pending
  on site_archive_change_request (site_id, archive_id)
  where status = 'PENDING';

create index if not exists idx_site_archive_change_request_page
  on site_archive_change_request (site_id, created_at desc);

create index if not exists idx_site_archive_change_request_upload
  on site_archive_change_request (upload_status, next_retry_at);

revoke delete, truncate on site_archive_change_request from public;
