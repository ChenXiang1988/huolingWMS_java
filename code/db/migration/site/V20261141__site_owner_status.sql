-- 单仓端货主状态只读副本（本次变更 tasks 1.14；design「停用货主如何阻断单仓端新建业务」、
-- design「数据模型与 DDL」的表清单）。
--
-- 为什么要有这张表：新业务受理是现场热路径，同步查 Hub 会把 Hub 的任何抖动传染成「现场开不了单」。
-- 停用是低频人工动作，一次下行的成本远低于每单一次远端调用。权威源仍在 Hub（D12），
-- 本表只回答一个问题：这个货主现在能不能接新单。单仓端 MUST NOT 在这里写业务状态。
--
-- 主键是 owner_id 而不是「仓 + 货主」：每个仓一套独立库（D08），本表天然只属于本仓；
-- 货主状态是全局事实，同一库内不需要再按仓拆分。
--
-- 列的口径：
--   owner_no       货主编号，全局唯一，只读副本同样保留唯一约束
--   status         状态闭集 ACTIVE / DISABLED，与 Kernel 的 OwnerStatus 同一取值（21 E-18）
--   source_version 权威版本号，消费按它单调推进，用于乱序保护
--   event_id       下行事件标识（货主编号加版本号），按它去重
--   data_timestamp 权威数据的产生时间；闸门用它判断副本是否已过陈旧阈值
--   updated_at     本仓写入时间（消费时间），只作留痕，不参与陈旧判定
--
-- 可重复执行：create table / index if not exists，本地重建与手工重跑都安全。

create table if not exists site_owner_status (
  owner_id       bigint      primary key,
  owner_no       varchar(32) not null,
  status         varchar(16) not null,
  source_version bigint      not null,
  event_id       varchar(64) not null,
  data_timestamp timestamptz not null,
  updated_at     timestamptz not null,
  constraint uk_site_owner_status_no unique (owner_no),
  constraint uk_site_owner_status_event unique (event_id),
  constraint ck_site_owner_status_status check (status in ('ACTIVE', 'DISABLED')),
  constraint ck_site_owner_status_version check (source_version >= 0),
  constraint ck_site_owner_status_no_format check (owner_no ~ '^OW[A-Z0-9]{6,30}$')
);

comment on table site_owner_status is
  '单仓端货主状态只读副本，权威源在 Hub；单仓只读本表判定能否受理新业务，不写业务状态';
comment on column site_owner_status.source_version is
  '已应用的权威版本号；消费只在 source_version 不小于现值时覆盖，事件乱序不倒退';
comment on column site_owner_status.event_id is
  '下行事件标识（货主编号加版本号），按它去重，重复投递不产生第二行';
comment on column site_owner_status.data_timestamp is
  '权威数据的产生时间；超过 wms.owner.blocking.stale-threshold 即视为失联，闸门拒绝放行';
comment on column site_owner_status.updated_at is
  '本仓写入时间（消费时间），只用于排障留痕，不参与陈旧判定';

-- 没有额外索引：闸门与消费都按主键 owner_id 定位，唯一约束本身已覆盖 owner_no 与 event_id。
