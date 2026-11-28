-- INV-18 / OI-06：货主档案不物理删除，停用以状态表达。
--
-- 为什么 V20261136 的 `revoke delete, truncate ... from public` 不够：
-- 迁移以 wms 账号执行，而 wms 就是 wms_owner 的表属主；属主权限不受
-- 「从 public 回收」影响，属主自己（以及任何被授予 delete 的角色）照样能删。
-- 实证：加这条手段之前，`delete from wms_owner where id = ?` 在集成测试的清理里每次都成功。
--
-- 真正的手段是行级 BEFORE DELETE 触发器：它对所有角色生效，包括表属主，
-- 只有 `session_replication_role = replica` 或显式 `alter table ... disable trigger`
-- （都需要表属主权限，属运维动作）才能绕过。
-- revoke 保留为非属主角色的一层，两层叠加：触发器挡「有 delete 权限的人」，revoke 挡「没被授予的人」。
--
-- 与 design「强制手段分层」的 OI-06 逐字对应；脚本只新增，不改任何历史脚本，可重复执行。

create or replace function wms_owner_reject_delete() returns trigger as $$
begin
  raise exception '货主档案不允许物理删除：停用以状态表达，编号与信用代码继续占位（INV-18）';
  return null;
end;
$$ language plpgsql;

comment on function wms_owner_reject_delete() is
  'INV-18：拒绝 wms_owner 的任何行级删除，表属主也绕不过';

drop trigger if exists trg_wms_owner_no_delete on wms_owner;
create trigger trg_wms_owner_no_delete before delete on wms_owner
  for each row execute function wms_owner_reject_delete();

-- 非属主角色的一层：即使将来把业务账号从属主换成独立角色，也不会被误授予删除能力。
revoke delete, truncate on wms_owner from public;
