-- 受理记录补「目标货主」列，幂等键列宽放宽到 128（本次变更 tasks 1.7；design「幂等与异步受理」）。
--
-- 为什么加 subject_owner_id：受理与幂等机制上移到 masterdata.acceptance 后由商品档案与货主档案共用，
-- 而 scope_key 对运营端恒为 HUB，无法按货主域区分；API-027 的数据域收窄只能靠这一列。
--
-- 不回填：商品档案的受理单该列恒为空（语义不一致的默认值比空值更危险），
-- 货主档案的受理单由应用在撰写时写入目标货主；建档受理单为空（受理时档案还不存在）。
--
-- 列宽放宽是元数据变更，不重写表，但会短暂取 ACCESS EXCLUSIVE 锁，须放在业务低峰执行；
-- 只放宽不收窄，历史记录原样可用（design「变更型脚本说明」）。
-- 可重复执行：add column if not exists、create index if not exists、type 放宽为幂等操作。

alter table hub_archive_acceptance
  add column if not exists subject_owner_id bigint;

comment on column hub_archive_acceptance.subject_owner_id is
  '受理单的目标货主：改档/停用/启用写目标货主，建档为空；API-027 数据域收窄的唯一依据';

create index if not exists idx_hub_archive_acceptance_subject
  on hub_archive_acceptance (scope_key, subject_owner_id, created_at desc);

alter table hub_archive_acceptance
  alter column idempotency_key type varchar(128);

alter table hub_idempotency
  alter column idempotency_key type varchar(128);

comment on column hub_archive_acceptance.idempotency_key is
  '幂等键，1 至 128 位，字符集 A-Za-z0-9._:-（契约 1.3）';
comment on column hub_idempotency.idempotency_key is
  '幂等键，1 至 128 位，字符集 A-Za-z0-9._:-（契约 1.3）';
