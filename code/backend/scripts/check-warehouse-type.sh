#!/usr/bin/env bash
# AR-10：仓型比较只允许出现在 strategy 包，其余位置出现即失败。
# 依据 14：这条无法用包依赖表达（策略实现合法地需要读仓型），改由脚本扫描。
set -euo pipefail

cd "$(dirname "$0")/.."

violations=$(grep -rEn '==[[:space:]]*(WarehouseType|仓型)\.' --include='*.java' \
  wms-hub/src/main wms-site/src/main wms-open/src/main wms-billing/src/main 2>/dev/null \
  | grep -v '/strategy/' || true)

if [ -n "$violations" ]; then
  echo "AR-10 失败：仓型比较只允许出现在 strategy 包"
  echo "$violations"
  exit 1
fi

echo "AR-10 通过"
