<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { AcceptPanel, ActionButton, ActionDrawer, DataTable, FormInput, FormSection, StatusTag } from '@wms/design'
import {
  accountStatusLabels,
  accountStatusTags,
  clientLabels,
  scopeDimensionLabels,
  scopeDimensions,
  scopeTypeLabels,
  type ScopeDimension,
} from '@wms/dict'
import { describeApiError, newIdempotencyKey } from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useAuthStore } from '../stores/auth'
import {
  createAcceptancePoller,
  identifierPattern,
  type AcceptTicket,
  type PermissionGroup,
  type RoleItem,
  type ScopeItem,
} from '../api/identity'

/**
 * 受理失败后的重提入口：保留原输入，重提前先按 API-010 取最新 version。
 * 角色主键与数据域标识保留契约的字符串形态，重提时原样回传。
 */
type PendingOp =
  | { kind: 'permissions'; roleId: string; permissionCodes: string[] }
  | { kind: 'scopes'; roleId: string; scopes: ScopeItem[] }

const auth = useAuthStore()
const roles = ref<RoleItem[]>([])
const groups = ref<PermissionGroup[]>([])
const loading = ref(false)
const error = ref('')
const acceptNo = ref<string | null>(null)
const submitting = ref(false)
const pollAcceptance = createAcceptancePoller(auth.http())

const canAssign = computed(() => auth.hasPermission(PERMISSIONS.roleAssign))
const canScope = computed(() => auth.hasPermission(PERMISSIONS.roleScope))

const permissionDrawer = ref(false)
const current = ref<RoleItem | null>(null)
const checked = ref<string[]>([])

const scopeDrawer = ref(false)
const scopeRole = ref<RoleItem | null>(null)
const scopeLoading = ref(false)
const scopeError = ref('')
const scopeLoadFailed = ref(false)
const scopeBaseline = ref('')
const scopeForm = reactive<Record<ScopeDimension, { scopeType: 'ALL' | 'LIST'; ids: string }>>({
  OWNER: { scopeType: 'ALL', ids: '' },
  WAREHOUSE: { scopeType: 'ALL', ids: '' },
})

const pendingOp = ref<PendingOp | null>(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    roles.value = await auth.http().get<RoleItem[]>('/hub/api/roles?client=HUB')
    groups.value = await auth.http().get<PermissionGroup[]>('/hub/api/permissions')
    syncOpenDrawers()
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    loading.value = false
  }
}

/** 列表刷新后把抽屉绑定的角色换成本次拉取的对象，version 随之更新。 */
function syncOpenDrawers() {
  const assigned = current.value
  if (assigned) {
    current.value = roles.value.find((role) => role.id === assigned.id) ?? assigned
  }
  const scoped = scopeRole.value
  if (scoped) {
    scopeRole.value = roles.value.find((role) => role.id === scoped.id) ?? scoped
  }
}

function openAssign(role: RoleItem) {
  current.value = role
  checked.value = [...role.permissionCodes]
  permissionDrawer.value = true
}

/** API-013：`{permissionCodes[], version}`，整体替换。 */
async function sendPermissions(role: RoleItem, permissionCodes: string[]) {
  if (submitting.value) {
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const data = await auth.http().put<AcceptTicket>(
      `/hub/api/roles/${role.id}/permissions`,
      { permissionCodes, version: role.version },
      newIdempotencyKey(),
    )
    acceptNo.value = data.acceptNo
    pendingOp.value = { kind: 'permissions', roleId: role.id, permissionCodes }
    permissionDrawer.value = false
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

async function submitPermissions() {
  if (!current.value) {
    return
  }
  await sendPermissions(current.value, [...checked.value])
}

function serializeScopes() {
  return JSON.stringify({
    OWNER: scopeForm.OWNER,
    WAREHOUSE: scopeForm.WAREHOUSE,
  })
}

/** UI-P09：左侧切换节点前若表单未提交必须提示。 */
const scopeDirty = computed(() => scopeRole.value !== null && serializeScopes() !== scopeBaseline.value)

/** 从行入口打开时总是按 API-015 重取当前配置，避免沿用上次的旧值。 */
async function openScopes(role: RoleItem) {
  scopeRole.value = role
  scopeDrawer.value = true
  await loadScopes(role)
}

async function selectScopeRole(role: RoleItem) {
  if (scopeRole.value?.id === role.id) {
    return
  }
  if (scopeRole.value && scopeDirty.value) {
    try {
      await ElMessageBox.confirm('当前数据域修改尚未提交，切换角色将丢弃修改。', '请确认', {
        confirmButtonText: '切换',
        cancelButtonText: '留在当前角色',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  scopeRole.value = role
  await loadScopes(role)
}

async function loadScopes(role: RoleItem) {
  scopeLoading.value = true
  scopeError.value = ''
  scopeLoadFailed.value = false
  try {
    const data = await auth.http().get<ScopeItem[]>(`/hub/api/roles/${role.id}/scopes`)
    applyScopes(data)
  } catch (e) {
    // 取不到当前配置时不允许提交，避免把一个空白表单整体替换上去。
    scopeError.value = describeApiError(e)
    scopeLoadFailed.value = true
    resetScopeForm()
  } finally {
    scopeLoading.value = false
  }
}

/** UI-S04：接口失败在区域内给重试出口。 */
function reloadScopes() {
  const role = scopeRole.value
  if (role) {
    void loadScopes(role)
  }
}

/** 表单回到「全部」，并把基线对齐，避免把残留值当成待提交的修改。 */
function resetScopeForm() {
  for (const dimension of scopeDimensions) {
    scopeForm[dimension].scopeType = 'ALL'
    scopeForm[dimension].ids = ''
  }
  scopeBaseline.value = serializeScopes()
}

/** API-015 只返回已配置的维度，缺的维度按「全部」展示（未配置等于不收窄）。 */
function applyScopes(list: ScopeItem[]) {
  for (const dimension of scopeDimensions) {
    const found = list.find((item) => item.dimension === dimension)
    scopeForm[dimension].scopeType = found?.scopeType ?? 'ALL'
    scopeForm[dimension].ids = found ? found.targetIds.join(', ') : ''
  }
  scopeBaseline.value = serializeScopes()
}

/**
 * 标识按契约以字符串提交：只切分、去空白、按格式过滤，不做 `Number()`／`parseInt`。
 * 主键是雪花号，转数字会丢精度，回传后变成不存在的标识。
 */
function toIds(text: string): string[] {
  return text
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter((value) => identifierPattern.test(value))
}

/** 提交前只做必填与格式校验：`LIST` 至少要给一个标识，否则服务端返回 `SCOPE_6002`。 */
function collectScopes(): ScopeItem[] | null {
  const scopes: ScopeItem[] = []
  for (const dimension of scopeDimensions) {
    const entry = scopeForm[dimension]
    if (entry.scopeType === 'LIST') {
      const targetIds = toIds(entry.ids)
      if (!targetIds.length) {
        scopeError.value = `${scopeDimensionLabels[dimension]}维度选「${scopeTypeLabels.LIST}」时须至少给出一个标识`
        return null
      }
      scopes.push({ dimension, scopeType: 'LIST', targetIds })
    } else {
      scopes.push({ dimension, scopeType: 'ALL', targetIds: [] })
    }
  }
  scopeError.value = ''
  return scopes
}

/** API-016：`{scopes[], version}`，整体替换。 */
async function sendScopes(role: RoleItem, scopes: ScopeItem[]) {
  if (submitting.value) {
    return
  }
  submitting.value = true
  scopeError.value = ''
  try {
    const data = await auth.http().put<AcceptTicket>(
      `/hub/api/roles/${role.id}/scopes`,
      { scopes, version: role.version },
      newIdempotencyKey(),
    )
    acceptNo.value = data.acceptNo
    pendingOp.value = { kind: 'scopes', roleId: role.id, scopes }
    scopeDrawer.value = false
  } catch (e) {
    scopeError.value = describeApiError(e)
    error.value = scopeError.value
  } finally {
    submitting.value = false
  }
}

async function submitScopes() {
  if (!scopeRole.value) {
    return
  }
  const scopes = collectScopes()
  if (!scopes) {
    return
  }
  await sendScopes(scopeRole.value, scopes)
}

/** UI-F03：失败给重提入口，保留原输入；重提前按 API-010 取最新 version。 */
async function retryLast() {
  const op = pendingOp.value
  if (!op) {
    return
  }
  error.value = ''
  try {
    const fresh = await auth.http().get<RoleItem[]>('/hub/api/roles?client=HUB')
    roles.value = fresh
    const role = fresh.find((item) => item.id === op.roleId)
    if (!role) {
      error.value = '角色不存在，请刷新列表'
      return
    }
    if (op.kind === 'permissions') {
      checked.value = [...op.permissionCodes]
      await sendPermissions(role, op.permissionCodes)
    } else {
      await sendScopes(role, op.scopes)
    }
  } catch (e) {
    error.value = describeApiError(e)
  }
}

/** 受理结果到达后再刷新列表：写操作不当作提交即生效（D20），版本号也随列表更新。 */
async function handleSettled(status: string) {
  if (status === 'SUCCEEDED') {
    pendingOp.value = null
  }
  await load()
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>角色管理</template>
    <AcceptPanel
      :accept-no="acceptNo"
      :poll="pollAcceptance"
      @retry="retryLast"
      @settled="handleSettled"
    />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon style="margin-bottom: 12px" />
    <DataTable :data="roles" :loading="loading">
      <el-table-column prop="roleCode" label="角色编码" min-width="220" />
      <el-table-column prop="roleName" label="角色名称" min-width="160" />
      <el-table-column label="端" width="120">
        <template #default="{ row }">{{ clientLabels[row.client] ?? row.client }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusTag :label="accountStatusLabels[row.status]" :type="accountStatusTags[row.status]" />
        </template>
      </el-table-column>
      <el-table-column label="权限点" min-width="120">
        <template #default="{ row }">{{ row.permissionCodes.length }} 项</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <ActionButton v-if="canAssign" variant="text" @click="openAssign(row)">分配权限</ActionButton>
          <ActionButton v-if="canScope" variant="text" @click="openScopes(row)">数据域</ActionButton>
        </template>
      </el-table-column>
    </DataTable>

    <ActionDrawer
      v-model="permissionDrawer"
      :title="`分配权限 · ${current?.roleName ?? ''}`"
      :width="720"
    >
      <el-checkbox-group v-model="checked">
        <div v-for="group in groups" :key="group.module" style="margin-bottom: 16px">
          <div style="font-weight: 600; margin-bottom: 8px">{{ group.moduleName }}</div>
          <el-checkbox v-for="item in group.items" :key="item.code" :value="item.code">
            {{ item.code }}
          </el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <ActionButton variant="secondary" @click="permissionDrawer = false">取消</ActionButton>
        <ActionButton variant="primary" :disabled="submitting" @click="submitPermissions">提交</ActionButton>
      </template>
    </ActionDrawer>

    <ActionDrawer v-model="scopeDrawer" title="数据域配置" :width="880">
      <div class="scope-config">
        <nav class="scope-roles">
          <div
            v-for="role in roles"
            :key="role.id"
            class="scope-role"
            :class="{ active: scopeRole?.id === role.id }"
            @click="selectScopeRole(role)"
          >
            <div class="scope-role-name">{{ role.roleName }}</div>
            <div class="scope-role-code">{{ role.roleCode }}</div>
          </div>
        </nav>
        <div class="scope-detail">
          <el-empty v-if="!scopeRole" description="选择左侧角色后配置数据域" />
          <div v-else-if="scopeLoadFailed" class="scope-failed">
            <el-alert :title="scopeError" type="error" :closable="false" show-icon />
            <ActionButton variant="text" @click="reloadScopes">重新加载数据域</ActionButton>
          </div>
          <div v-else v-loading="scopeLoading">
            <el-alert
              v-if="scopeError"
              :title="scopeError"
              type="error"
              :closable="false"
              show-icon
              style="margin-bottom: 12px"
            />
            <el-form label-width="90px">
              <FormSection
                v-for="dimension in scopeDimensions"
                :key="dimension"
                :title="`${scopeDimensionLabels[dimension]}维度`"
              >
                <el-form-item label="范围">
                  <el-radio-group v-model="scopeForm[dimension].scopeType">
                    <el-radio value="ALL">{{ scopeTypeLabels.ALL }}</el-radio>
                    <el-radio value="LIST">{{ scopeTypeLabels.LIST }}</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item v-if="scopeForm[dimension].scopeType === 'LIST'" label="标识">
                  <FormInput
                    v-model="scopeForm[dimension].ids"
                    :placeholder="`${scopeDimensionLabels[dimension]} ID，逗号分隔`"
                  />
                </el-form-item>
              </FormSection>
              <p class="scope-note">未配置的维度按「全部」参与合并，不会因缺配置而无法作业。</p>
            </el-form>
          </div>
        </div>
      </div>
      <template #footer>
        <ActionButton variant="secondary" @click="scopeDrawer = false">取消</ActionButton>
        <ActionButton
          variant="primary"
          :disabled="!scopeRole || submitting || scopeLoadFailed"
          @click="submitScopes"
        >
          提交
        </ActionButton>
      </template>
    </ActionDrawer>
  </el-card>
</template>

<style scoped>
.scope-config {
  display: flex;
  gap: 24px;
}
.scope-roles {
  width: var(--wms-config-panel-width);
  flex-shrink: 0;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 12px;
}
.scope-role {
  padding: 8px 12px;
  border-radius: 4px;
  cursor: pointer;
}
.scope-role.active {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
.scope-role-name {
  font-size: 14px;
}
.scope-role-code {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.scope-detail {
  flex: 1;
  min-width: 0;
}
.scope-failed {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}
.scope-note {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
