-- 货主档案补录落库与引用外键补齐
-- （本次变更 tasks 1.5、1.6；design「存量 owner_id 回填与加约束顺序」第三步）。
--
-- 【第二发布窗口，手工执行，不进 Flyway】
-- 本脚本刻意放在 code/db/ops/，不在 Flyway 自动迁移目录 code/db/migration/hub/ 内：
-- 应用配置的 flyway.locations 只扫 classpath:db/migration，本文件既不会被自动执行，
-- 也不会在 flyway_schema_history 里留下版本记录。
-- 理由（design「为什么第三步不进 Flyway 目录」）：补录依赖运营逐条填信用代码、联系人与电话，
-- 时间不由研发控制；放进自动迁移后，只要清单里有一条不齐备，任何环境（新装、本地开发、测试）
-- 一启动就中止，等于把运营节奏变成发布阻塞。放到运维目录后，中间态只缺引用完整性，
-- 读写全部照常，外键在本窗口内一次补齐。
--
-- 执行方式（发布负责人执行，全程单事务）：
--   psql -v ON_ERROR_STOP=1 -f code/db/ops/V20261138__hub_owner_import_and_foreign_keys.sql
-- ON_ERROR_STOP=1 是必需的：任一步 raise exception 后 psql 立即退出，未提交的事务随连接断开回滚，
-- 不会留下「货主写了半批、外键没加」的中间状态；不加该开关时 psql 会继续跑后续语句并最终回滚，
-- 但错误信息会被后面的 25P02 淹没，排障成本高。
--
-- 执行前置条件：
--   1) V20261136、V20261137 已随第一窗口自动执行（wms_owner、wms_owner_import 已存在）；
--   2) 补录清单已清零：
--        select count(*) from wms_owner_import where imported_at is null;   -- 必须为 0
--   3) 运营确认的「不保留」历史货主已按 18 清单处置（对应账号与 SKU 引用已解绑或删除）。
--
-- 执行步骤（同一事务，顺序固定，不允许调换）：
--   1. 校验：四项齐备、清单内信用代码不重复、与既有档案不冲突、字段形态合法、引用无缺失；
--   2. 写入：按原 owner_id 写入 wms_owner（id 取原值使既有引用不变，owner_no 取序列，
--      created_by = 0 表示迁移账号），并回写 imported_at；
--   3. 复校：wms_account 与 hub_sku_archive 的引用无孤儿；
--   4. 加外键：先 not valid 加约束、再 validate constraint，两步走避免 add constraint 期间
--      长时间持有 SHARE ROW EXCLUSIVE 全表锁（design 已定写法）；
--   5. 补索引：外键列必须有索引。hub_sku_archive.owner_id 已有 idx_hub_sku_archive_owner_status
--      且首列就是 owner_id，满足要求，不重复建。
--
-- 可重复执行：已导入行按 imported_at 跳过，不重复写、不覆盖运营已填内容；
-- 外键先 drop constraint if exists 再重建，索引用 if not exists；
-- 任一校验不过整体回滚，按报错条数补齐后重跑本脚本即可。

-- 不依赖调用方是否记得加 -v ON_ERROR_STOP=1：任一步报错即停止继续下发语句，
-- 未提交的事务随连接断开回滚，避免「校验失败后仍往下跑」产生的连带报错。
\set ON_ERROR_STOP on

begin;

-- 1. 校验 -------------------------------------------------------------------

do $$
declare
  incomplete  bigint;
  duplicated  bigint;
  conflicted  bigint;
  malformed   bigint;
  orphans     bigint;
begin
  -- 1.1 四项齐备（null、空串、全空格一律按缺失处理，与 kernel 的 OwnerFields 同一口径）。
  select count(*) into incomplete
    from wms_owner_import
   where imported_at is null
     and (btrim(coalesce(owner_name, '')) = ''
       or btrim(coalesce(contact_name, '')) = ''
       or btrim(coalesce(contact_phone, '')) = ''
       or btrim(coalesce(credit_code, '')) = '');
  if incomplete > 0 then
    raise exception '货主补录清单有 % 条四项不齐备，补齐 owner_name/contact_name/contact_phone/credit_code 后重跑',
      incomplete;
  end if;

  -- 1.2 清单内信用代码不重复：同一法人只允许一条档案（OI-04）。
  select count(*) into duplicated
    from (select credit_code
            from wms_owner_import
           where imported_at is null
           group by credit_code
          having count(*) > 1) as repeated;
  if duplicated > 0 then
    raise exception '货主补录清单内信用代码重复 % 组，同一法人只允许一条档案', duplicated;
  end if;

  -- 1.3 待导入的信用代码不得与既有档案冲突（冲突最终由唯一索引兜底，这里给出可读的原因）。
  select count(*) into conflicted
    from wms_owner_import i
    join wms_owner o on o.credit_code = i.credit_code and o.id <> i.owner_id
   where i.imported_at is null;
  if conflicted > 0 then
    raise exception '货主补录清单有 % 条信用代码与既有档案重复，核对主体后重跑', conflicted;
  end if;

  -- 1.4 形态与库层 CHECK 同一口径：信用代码 18 位大写、电话字符集、名称与联系人长度。
  --     不静默转大小写（契约 1.8）：小写提交在这里被拒，而不是被 upper() 悄悄改写后落库。
  select count(*) into malformed
    from wms_owner_import
   where imported_at is null
     and (credit_code !~ '^[0-9A-HJ-NP-RT-UW-Y]{18}$'
       or contact_phone !~ '^[0-9+() -]{6,32}$'
       or char_length(btrim(owner_name)) > 128
       or char_length(btrim(contact_name)) > 64);
  if malformed > 0 then
    raise exception '货主补录清单有 % 条字段形态不合法（信用代码 18 位大写、电话字符集、名称与联系人长度）',
      malformed;
  end if;

  -- 1.5 账号与商品档案的引用必须有归宿：要么在清单里，要么已经建档。
  --     对不上的只可能是「不保留清单」里的历史货主，须先按 18 清单处置其账号与 SKU 引用。
  select count(*) into orphans
    from (select owner_id from wms_account where owner_id is not null
          union all
          select owner_id from hub_sku_archive where owner_id is not null) as ref
   where not exists (select 1 from wms_owner_import i where i.owner_id = ref.owner_id)
     and not exists (select 1 from wms_owner o where o.id = ref.owner_id);
  if orphans > 0 then
    raise exception '有 % 条账号或商品档案引用找不到货主档案候选，按 18 清单处置后重跑', orphans;
  end if;
end $$;

-- 2. 写入货主档案并回写 imported_at -----------------------------------------

-- 写入口径（design 已定）：id 取原 owner_id（既有引用不变）、owner_no 取序列、
-- created_by = 0 表示迁移账号；状态为可用、动作为 CREATE、原因为空（与 ck_wms_owner_reason 一致）、版本 0。
insert into wms_owner (id, owner_no, owner_name, contact_name, contact_phone, credit_code,
    status, status_action, status_reason, status_changed_at, status_changed_by,
    version, created_at, created_by, updated_at, updated_by)
select i.owner_id,
       'OW' || to_char(nextval('seq_wms_owner_no'), 'FM000000'),
       btrim(i.owner_name),
       btrim(i.contact_name),
       btrim(i.contact_phone),
       btrim(i.credit_code),
       'ACTIVE',
       'CREATE',
       null,
       now(),
       0,
       0,
       now(),
       0,
       now(),
       0
  from wms_owner_import i
 where i.imported_at is null
   and not exists (select 1 from wms_owner o where o.id = i.owner_id)
on conflict (id) do nothing;

-- imported_at 只回写「确实已落库」的行：重跑时已导入行不再写第二遍。
update wms_owner_import i
   set imported_at = now(),
       updated_at  = now()
 where i.imported_at is null
   and exists (select 1 from wms_owner o where o.id = i.owner_id);

-- 3. 复校：写入之后引用必须全部有归宿 ----------------------------------------

do $$
declare
  account_orphans bigint;
  sku_orphans     bigint;
begin
  select count(*) into account_orphans
    from wms_account a
   where a.owner_id is not null
     and not exists (select 1 from wms_owner o where o.id = a.owner_id);
  if account_orphans > 0 then
    raise exception 'wms_account 有 % 条 owner_id 找不到货主档案，先补录或按 18 清单处置', account_orphans;
  end if;

  select count(*) into sku_orphans
    from hub_sku_archive s
   where not exists (select 1 from wms_owner o where o.id = s.owner_id);
  if sku_orphans > 0 then
    raise exception 'hub_sku_archive 有 % 条 owner_id 找不到货主档案，先补录或按 18 清单处置', sku_orphans;
  end if;
end $$;

-- 4. 加外键：先 not valid 再加，随后 validate ---------------------------------

-- 拆分理由：add constraint ... not valid 只取一次短锁、不扫表；validate constraint 取
-- SHARE UPDATE EXCLUSIVE，不阻塞读写。一步到位的 add constraint 会长时间持 SHARE ROW EXCLUSIVE，
-- 发布窗口内会挡住账号与商品档案的写入。
-- 可重复执行：先 drop constraint if exists 再重建，等价于幂等重跑。
alter table wms_account drop constraint if exists fk_wms_account_owner;
alter table wms_account
  add constraint fk_wms_account_owner foreign key (owner_id)
  references wms_owner (id) on update restrict on delete restrict not valid;
alter table wms_account validate constraint fk_wms_account_owner;

alter table hub_sku_archive drop constraint if exists fk_hub_sku_archive_owner;
alter table hub_sku_archive
  add constraint fk_hub_sku_archive_owner foreign key (owner_id)
  references wms_owner (id) on update restrict on delete restrict not valid;
alter table hub_sku_archive validate constraint fk_hub_sku_archive_owner;

comment on constraint fk_wms_account_owner on wms_account is
  '货主端账号的归属货主必须有档案；绑定后不可变由应用校验（OI-10）';
comment on constraint fk_hub_sku_archive_owner on hub_sku_archive is
  '商品档案的归属货主必须有档案；本次不改列、不改唯一键，停用货主不影响存量库存';

-- 5. 补索引：外键列必须有索引 ------------------------------------------------

create index if not exists idx_wms_account_owner on wms_account (owner_id);

-- 收口提示：清单剩余条数应为 0；非 0 说明本窗口还差补录，外键已加但清单未清，需人工确认。
do $$
declare
  pending bigint;
begin
  select count(*) into pending from wms_owner_import where imported_at is null;
  raise notice '货主档案补录与外键窗口执行完成：本次已回写 imported_at，剩余待补录 % 条', pending;
end $$;

commit;
