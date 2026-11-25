-- 受理单新增幂等键列（三步走的第一步：先加可空列，不触碰既有行）。
-- 依据：契约「幂等约定」第 3 条要求先按键索引受理单、再比对请求指纹。
-- 原表只有 fingerprint，且旧实现把幂等键拼进了指纹，无法区分「同键同体」与「同键异体」。
-- 列宽 64 与契约「键格式：1–64 位」一致。

alter table wms_acceptance
  add column if not exists idempotency_key varchar(64);

comment on column wms_acceptance.idempotency_key is
  '请求头 Idempotency-Key 原值，只做去重索引；与请求指纹分开存，命中后比对指纹判定同键异体';
