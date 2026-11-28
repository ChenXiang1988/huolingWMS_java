-- 账号的归属货主绑定后不可变更（本次变更 tasks 1.15；design「账号与登录」的「绑定不可变」、
-- spec「绑定不可改」、OI-10）。
--
-- 为什么要库层触发器：应用层已经在 AccountCommandService.update 拒绝携带不同 ownerId 的修改，
-- 但绑定是「写一次、永不变」的身份事实，任何走 SQL 的运维脚本、批处理或后续新增的写路径都可能绕过应用层。
-- 触发器把这条规则变成库的性质：空→值、值→空、值→另一值一律拒绝，与 OI-10 的口径逐条对应。
--
-- 为什么编号是 V20261142：hub 目录已用到 V20261140，V20261141 按设计章留给单仓端的
-- site_owner_status（脚本按执行单元分目录，两边的编号不在同一张 flyway_schema_history 里，
-- 这里刻意不占用 1141，避免同一号码在两处指代不同脚本）。
--
-- 可重复执行：create or replace function + drop trigger if exists 再建，本地重建与手工重跑都安全。
-- 只加约束、不改列、不动数据：存量账号的 owner_id 原样保留，重跑无副作用。

create or replace function wms_account_owner_immutable() returns trigger as $$
begin
  if new.owner_id is distinct from old.owner_id then
    raise exception '账号的归属货主绑定后不可变更，换货主请停用该账号后另开新账号';
  end if;
  return new;
end;
$$ language plpgsql;

comment on function wms_account_owner_immutable() is
  '账号归属货主不可变（OI-10）：迁移账号、账号状态与密码的更新都不经过 owner_id，正常路径不受影响';

drop trigger if exists trg_wms_account_owner_immutable on wms_account;
create trigger trg_wms_account_owner_immutable before update on wms_account
  for each row execute function wms_account_owner_immutable();
