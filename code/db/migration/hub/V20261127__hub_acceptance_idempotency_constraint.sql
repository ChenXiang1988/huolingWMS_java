-- 幂等落点的唯一约束（三步走的第三步）。
-- 换约束的理由：契约「幂等约定」第 2、3 条规定作用域是操作账号 + 接口 + 请求指纹，
-- 判定顺序是「按键索引 → 再比对指纹」。若把指纹放进唯一索引，同键异体会拿到新指纹而绕过去重，
-- 因此唯一索引只能建在（账号、接口、幂等键）上，指纹仅作比较列。

alter table wms_acceptance
  alter column idempotency_key set not null;

alter table wms_acceptance
  drop constraint if exists uk_wms_acceptance_fingerprint;

alter table wms_acceptance
  add constraint uk_wms_acceptance_idempotency_key unique (account_id, api_code, idempotency_key);
