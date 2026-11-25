/**
 * 幂等键生成，只此一份。写接口统一读 `Idempotency-Key` 头（契约「幂等约定」）。
 * 单独成文件是为了让请求层与各端接口模块共用同一实现，且不产生循环引用。
 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}
