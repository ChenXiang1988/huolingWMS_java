-- INV-18 / OI-06 补强：货主档案不接受 TRUNCATE，表属主也不例外。
--
-- 为什么还要一条新脚本：V20261143 建了行级 BEFORE DELETE 触发器，拦住的是「按行删」；
-- 而 `truncate wms_owner` 走的是表级路径，不触发行级触发器——表属主（迁移账号 wms 就是属主）
-- 照样能一条语句清空全表。行级 DELETE 已无例外，TRUNCATE 仍是缺口，补齐后 INV-18 才闭合。
--
-- 手段是语句级 BEFORE TRUNCATE 触发器：TRUNCATE 从不产生行，只能用 `for each statement`。
-- 它对所有角色生效（含表属主），只有 `session_replication_role = replica`
-- 或显式 `alter table ... disable trigger`（都需属主权限，属运维动作）才能绕过，
-- 与 V20261143 的行级触发器口径一致。
--
-- 为什么不改 V20261143：已在库中应用，改内容会让校验和变化、升级路径断掉。
-- 本脚本只新增，可重复执行（create or replace + drop trigger if exists 再建）。
--
-- 与 design「强制手段分层」OI-06、`00a` INV-18 对应：数据不物理删除，停用以状态表达。

create or replace function wms_owner_reject_truncate() returns trigger as $$
begin
  raise exception '货主档案不允许 truncate：停用以状态表达，编号与信用代码继续占位（INV-18）';
  return null;
end;
$$ language plpgsql;

comment on function wms_owner_reject_truncate() is
  'INV-18：拒绝 wms_owner 的 TRUNCATE（语句级触发器，表属主也绕不过；行级删除见 V20261143）';

drop trigger if exists trg_wms_owner_no_truncate on wms_owner;
create trigger trg_wms_owner_no_truncate before truncate on wms_owner
  for each statement execute function wms_owner_reject_truncate();

-- revoke 保留为非属主角色的一层（属主权限不因 revoke from public 改变，故不能只靠它）。
revoke truncate on wms_owner from public;
