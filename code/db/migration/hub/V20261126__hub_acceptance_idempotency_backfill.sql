-- 回填幂等键（三步走的第二步）。历史受理单的原始幂等键没有留存：
-- 旧指纹是 sha256(key + '|' + body)，不可逆，无法还原键值。
-- 因此按受理单主键生成占位值，保证 (account_id, api_code, idempotency_key) 唯一，
-- 且不会与任何真实键冲突（真实键由客户端生成，没有 legacy- 前缀的形态约束，但该前缀由本脚本独占）。
-- 历史行保持「不可再去重」的既有语义：即便客户端拿旧键重提，也只会命中新受理单的键空间。

update wms_acceptance
   set idempotency_key = 'legacy-' || id
 where idempotency_key is null;
