-- 存量货主候选档案行（本次变更 tasks 1.4；design「存量 owner_id 回填与加约束顺序」第二步）。
--
-- 前提事实：wms_account.owner_id 与 hub_sku_archive.owner_id 现在都是裸数字，没有外键；
-- 信用代码、联系人、联系电话在存量数据里没有来源，只能人工补录。
-- 本脚本只做「把需要补录的货主列出来」，不写 wms_owner，也不改任何既有表。
--
-- 可重复执行：on conflict (owner_id) do nothing，重复跑不覆盖已补录的内容
-- （运营填好的四项不会因为重跑回到空值）。

insert into wms_owner_import (owner_id, owner_name, created_at, updated_at)
select candidate.owner_id,
       -- 展示名取该货主下最早的账号名：建档时人工核对成本最低的那条来源。
       (select a.account_name
          from wms_account a
         where a.owner_id = candidate.owner_id
         order by a.created_at asc, a.id asc
         limit 1),
       now(),
       now()
  from (
        select owner_id from wms_account where owner_id is not null
        union
        select owner_id from hub_sku_archive where owner_id is not null
       ) as candidate
on conflict (owner_id) do nothing;

-- 待补录清单报表：补录完成前，这里给出的条数就是外键发布窗口还差多少条。
do $$
declare
  pending bigint;
  total   bigint;
begin
  select count(*) filter (where imported_at is null), count(*)
    into pending, total
    from wms_owner_import;
  raise notice '货主档案补录清单：候选 % 条，待补录 % 条（四项补齐后执行 V20261138）', total, pending;
end $$;
