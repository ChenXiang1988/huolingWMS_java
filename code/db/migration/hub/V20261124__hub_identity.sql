-- 运营端身份与权限：九张表。
-- 依据 19（权限系统设计）与 openspec/changes/add-hub-auth-and-console-shell 的 design.md。
-- 主键雪花由应用生成，不用序列；公共列按 11 的五组；数据不物理删除（INV-18）。

create table wms_account (
  id            bigint       primary key,
  account_name  varchar(64)  not null,
  password_hash varchar(100) not null,
  employee_id   bigint,
  owner_id      bigint,
  status        varchar(16)  not null,
  version       bigint       not null default 0,
  created_at    timestamptz  not null,
  created_by    bigint       not null,
  updated_at    timestamptz  not null,
  updated_by    bigint       not null,
  constraint uk_wms_account_name unique (account_name),
  constraint uk_wms_account_employee unique (employee_id),
  constraint ck_wms_account_status check (status in ('ENABLED', 'DISABLED'))
);

comment on table wms_account is '账号；与员工一对一，货主端账号 employee_id 为空';
comment on column wms_account.owner_id is '货主端账号所属货主；内部账号为空';

create index idx_wms_account_status on wms_account (status);

create table wms_role (
  id                bigint      primary key,
  role_code         varchar(48) not null,
  role_name         varchar(64) not null,
  applies_to_client varchar(16) not null,
  status            varchar(16) not null,
  version           bigint      not null default 0,
  created_at        timestamptz not null,
  created_by        bigint      not null,
  updated_at        timestamptz not null,
  updated_by        bigint      not null,
  constraint uk_wms_role_code unique (role_code),
  constraint ck_wms_role_client check (applies_to_client in ('HUB', 'SITE', 'MOBILE', 'OWNER')),
  constraint ck_wms_role_status check (status in ('ENABLED', 'DISABLED'))
);

comment on table wms_role is '角色按端隔离，登录时只加载本端角色（05）';

create table wms_permission (
  id         bigint      primary key,
  perm_code  varchar(96) not null,
  module     varchar(32) not null,
  status     varchar(16) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uk_wms_permission_code unique (perm_code),
  constraint ck_wms_permission_status check (status in ('ENABLED', 'DISABLED'))
);

comment on table wms_permission is '权限点由代码声明并同步，应用账号只授读（19）';

create table wms_role_permission (
  role_id       bigint not null,
  permission_id bigint not null,
  primary key (role_id, permission_id)
);

create index idx_wms_role_permission_perm on wms_role_permission (permission_id);

create table wms_account_role (
  account_id bigint not null,
  role_id    bigint not null,
  primary key (account_id, role_id)
);

create index idx_wms_account_role_role on wms_account_role (role_id);

create table wms_role_scope (
  id         bigint      primary key,
  role_id    bigint      not null,
  dimension  varchar(16) not null,
  scope_type varchar(16) not null,
  constraint uk_wms_role_scope unique (role_id, dimension),
  constraint ck_wms_role_scope_dimension check (dimension in ('OWNER', 'WAREHOUSE')),
  constraint ck_wms_role_scope_type check (scope_type in ('ALL', 'LIST'))
);

comment on table wms_role_scope is '角色数据域，只允许货主与仓两个维度（19）';

create table wms_role_scope_item (
  id            bigint primary key,
  role_scope_id bigint not null,
  target_id     bigint not null,
  constraint uk_wms_role_scope_item unique (role_scope_id, target_id)
);

comment on table wms_role_scope_item is '指定列表的具体标识；不建外键，有效性在应用层校验';

create table wms_authorization_log (
  id                bigint      primary key,
  operator_id       bigint      not null,
  target_account_id bigint      not null,
  action            varchar(48) not null,
  before_json       text,
  after_json        text,
  created_at        timestamptz not null
);

comment on table wms_authorization_log is '授权审计，只授插入权限（19）';

create index idx_wms_authorization_log_target on wms_authorization_log (target_account_id, created_at desc);

create table wms_acceptance (
  id          bigint      primary key,
  accept_no   varchar(32) not null,
  api_code    varchar(16) not null,
  account_id  bigint      not null,
  fingerprint varchar(64) not null,
  status      varchar(16) not null,
  result_json text,
  error_code  varchar(32),
  created_at  timestamptz not null,
  finished_at timestamptz,
  constraint uk_wms_acceptance_no unique (accept_no),
  constraint uk_wms_acceptance_fingerprint unique (account_id, api_code, fingerprint),
  constraint ck_wms_acceptance_status check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

comment on table wms_acceptance is '受理单：写入一律异步受理（D20），同指纹只产生一张';

create index idx_wms_acceptance_status on wms_acceptance (status, created_at);
