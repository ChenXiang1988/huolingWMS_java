-- 商品档案写接口的幂等落点（本次变更 design「幂等与异步事件」与契约「幂等约定」）。
--
-- 为什么另建一张表而不复用 wms_acceptance：契约把作用域定为「归属货主加接口加键」，
-- 而 wms_acceptance 的唯一键是（操作账号、接口、幂等键），且没有窗口列；
-- 历史脚本合入后不可修改（01「迁移只增不改」），因此新增本表承载货主维度作用域与二十四小时窗口。
--
-- 判定顺序照契约：先按（作用域、接口、幂等键）索引到记录，再比对请求指纹；
-- 指纹只作比较列，不进唯一键，否则同键异体会拿到新指纹而绕过去重。

create table if not exists hub_idempotency (
  id              bigint      primary key,
  scope_key       varchar(64) not null,
  api_code        varchar(16) not null,
  idempotency_key varchar(64) not null,
  fingerprint     varchar(64) not null,
  status          varchar(16) not null,
  result_json     text,
  error_code      varchar(32),
  created_at      timestamptz not null,
  expires_at      timestamptz not null,
  constraint uk_hub_idempotency_key unique (scope_key, api_code, idempotency_key),
  constraint ck_hub_idempotency_status
    check (status in ('IN_PROGRESS', 'SUCCEEDED', 'FAILED'))
);

comment on table hub_idempotency is '写接口幂等记录；作用域为归属货主加接口加键，窗口不少于二十四小时';
comment on column hub_idempotency.scope_key is '作用域键：货主端取归属货主，运营端取 HUB，单仓端取本仓标识';
comment on column hub_idempotency.fingerprint is '请求指纹，覆盖接口与请求体，不含幂等键本身';
comment on column hub_idempotency.result_json is '首次受理结果，重复请求原样返回（同键同指纹）';
comment on column hub_idempotency.expires_at is '窗口到期时间；到期后同键可重新受理';

create index if not exists idx_hub_idempotency_expires
  on hub_idempotency (expires_at);

-- 本表是技术表而不是业务账：窗口到期后按 expires_at 清理是正常运维动作（INV-18 约束的是业务数据）。
