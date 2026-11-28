/**
 * 商品档案接口的字符串错误码到提示的映射，只此一份。
 * 取值与措辞逐条对齐变更 `add-three-end-product-archive` 契约章「错误码表」的「调用方处置」列；
 * 页面只引用 `describeApiError`，不各自复制码值与文案。
 */
export const archiveErrorHints: Record<string, string> = {
  PARAM_INVALID: '字段缺失或取值越界，请按提示修正后重提',
  RULE_DUPLICATE_CODE: '该货主内已存在相同编码，请修改编码后重提',
  RULE_DUPLICATE_BARCODE: '该货主内已存在相同条码，请修改条码后重提',
  /** 货主档案：统一社会信用代码全局唯一，重复只在受理结果出现（契约 1.4、`OA-10`）。 */
  RULE_DUPLICATE_CREDIT_CODE: '该统一社会信用代码已被其他货主使用，请核对后改用正确主体',
  STATE_INVALID: '当前状态不允许该操作，请刷新后按当前状态操作',
  CONFLICT_VERSION: '档案已被他人修改，请刷新后重新提交',
  NOT_FOUND_ARCHIVE: '档案不存在或不在可见范围内，请修正档案编号',
  /** 货主档案：不存在与不在数据域内同一响应，不泄露存在性（契约 1.4）。 */
  NOT_FOUND_OWNER: '货主档案不存在或不在你的数据域内，请修正编号或换有权限账号',
  FORBIDDEN_OWNER: '无权访问该货主的数据，请换用有权限的账号操作',
  FORBIDDEN_SITE: '当前站点无权访问该数据，请换有权限的站点或改选本仓可见档案',
  IDEMPOTENT_IN_PROGRESS: '上一次提交仍在处理中，请按重试间隔再次提交',
  IDEMPOTENT_KEY_CONFLICT: '本次提交与上次内容不同，请刷新后重提',
  RATE_LIMITED: '请求过于频繁，请按提示间隔退避后重试',
  DEPENDENCY_UNAVAILABLE: '依赖服务暂时不可用，请稍后重试或查看缓存数据',
  INTERNAL_ERROR: '服务处理失败，请带追踪编号报障',
}
