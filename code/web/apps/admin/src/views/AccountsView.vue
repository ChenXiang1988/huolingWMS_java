<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { AcceptPanel, ActionButton, ActionDrawer, DataTable, DetailDrawer, FilterBar, FormInput, FormSection, StatusTag } from '@wms/design'
import { accountStatusLabels, accountStatusTags } from '@wms/dict'
import { describeApiError, newIdempotencyKey } from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useAuthStore } from '../stores/auth'
import {
  createAcceptancePoller,
  identifierPattern,
  type AccountItem,
  type AcceptTicket,
  type RoleItem,
} from '../api/identity'

/**
 * 受理失败后的重提入口：保留原输入，重提前先按 API-007 取最新 version。
 * 标识保留契约的字符串形态，重提时原样回传。
 */
type PendingOp =
  | { kind: 'status'; accountId: string; status: 'ENABLED' | 'DISABLED' }
  | { kind: 'password'; accountId: string; password: string }
  | { kind: 'roles'; accountId: string; roleIds: string[] }

const auth = useAuthStore()
const items = ref<AccountItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const acceptNo = ref<string | null>(null)
const submitting = ref(false)
const pollAcceptance = createAcceptancePoller(auth.http())
const filter = reactive({ keyword: '', status: '' })
const drawer = reactive({ visible: false, accountName: '', employeeId: '', initialPassword: '' })
const detailVisible = ref(false)
const detailRow = ref<AccountItem | null>(null)
const passwordDialog = reactive({ visible: false, value: '' })
const rolesDrawer = ref(false)
const roleOptions = ref<RoleItem[]>([])
/** 角色主键按契约保持字符串，勾选回填与提交都原样使用。 */
const checkedRoleIds = ref<string[]>([])
const pendingOp = ref<PendingOp | null>(null)

const canCreate = computed(() => auth.hasPermission(PERMISSIONS.accountCreate))
const canUpdate = computed(() => auth.hasPermission(PERMISSIONS.accountUpdate))
const canAssignRole = computed(() => auth.hasPermission(PERMISSIONS.accountRole))

/** 按当前状态二选一：启用中的账号给「停用」，已停用的给「启用」（API-008 的 status）。 */
const toggleLabel = computed(() => (detailRow.value?.status === 'ENABLED' ? '停用' : '启用'))

const detailSections = computed(() =>
  detailRow.value
    ? [
        {
          title: '基本信息',
          fields: [
            { label: '账号', value: detailRow.value.accountName },
            { label: '员工 ID', value: detailRow.value.employeeId ?? '未绑定' },
            { label: '状态', value: accountStatusLabels[detailRow.value.status] ?? detailRow.value.status },
            { label: '版本', value: String(detailRow.value.version) },
          ],
        },
        {
          title: '角色',
          fields: detailRow.value.roles.length
            ? detailRow.value.roles.map((role, index) => ({ label: `角色 ${index + 1}`, value: role }))
            : [{ label: '角色', value: '未分配' }],
        },
      ]
    : [],
)

function openDetail(row: AccountItem) {
  detailRow.value = row
  detailVisible.value = true
}

/** 列表刷新后把抽屉绑定的账号换成本次拉取的对象，version 随之更新。 */
function syncDetail() {
  const current = detailRow.value
  if (!current) {
    return
  }
  detailRow.value = items.value.find((item) => item.id === current.id) ?? current
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ page: String(page.value), size: String(size.value) })
    if (filter.keyword) query.set('keyword', filter.keyword)
    if (filter.status) query.set('status', filter.status)
    const data = await auth.http().get<{ items: AccountItem[]; total: number }>(
      `/hub/api/accounts?${query.toString()}`,
    )
    items.value = data.items
    total.value = data.total
    syncDetail()
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    loading.value = false
  }
}

/** UI-F05：危险操作二次确认，写清对象与后果。 */
async function confirmThen(message: string, confirmText: string, action: () => Promise<void>) {
  try {
    await ElMessageBox.confirm(message, '请确认', {
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await action()
}

async function submitCreate() {
  if (submitting.value) {
    return
  }
  // 员工 ID 按契约原样以字符串提交，只做格式校验，不转数字。
  const employeeId = drawer.employeeId.trim()
  if (!drawer.accountName || !drawer.initialPassword) {
    error.value = '账号与初始密码必填'
    return
  }
  if (!identifierPattern.test(employeeId)) {
    error.value = '员工 ID 为员工内部主键，须为数字标识'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const data = await auth.http().post<AcceptTicket>(
      '/hub/api/accounts',
      {
        accountName: drawer.accountName,
        employeeId,
        initialPassword: drawer.initialPassword,
        roleIds: [],
      },
      newIdempotencyKey(),
    )
    acceptNo.value = data.acceptNo
    pendingOp.value = null
    drawer.visible = false
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

/** API-008：`{status?, resetPassword?, version}`，`status` 与 `resetPassword` 互斥。 */
async function submitUpdate(
  row: AccountItem,
  body: { status?: 'ENABLED' | 'DISABLED'; resetPassword?: string },
  op: PendingOp,
) {
  if (submitting.value) {
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const data = await auth.http().patch<AcceptTicket>(
      `/hub/api/accounts/${row.id}`,
      { ...body, version: row.version },
      newIdempotencyKey(),
    )
    acceptNo.value = data.acceptNo
    pendingOp.value = op
    passwordDialog.visible = false
    detailVisible.value = false
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

async function toggleStatus() {
  const row = detailRow.value
  if (!row) {
    return
  }
  const target = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const message =
    target === 'DISABLED'
      ? `确认停用账号 ${row.accountName}？停用后该账号无法登录。`
      : `确认启用账号 ${row.accountName}？启用后该账号可登录。`
  await confirmThen(message, toggleLabel.value, () =>
    submitUpdate(row, { status: target }, { kind: 'status', accountId: row.id, status: target }),
  )
}

function openPassword() {
  if (!detailRow.value) {
    return
  }
  passwordDialog.value = ''
  passwordDialog.visible = true
}

async function submitPassword() {
  const row = detailRow.value
  if (!row) {
    return
  }
  const value = passwordDialog.value
  if (!value) {
    error.value = '新密码必填'
    return
  }
  if (value.length > 72) {
    error.value = '新密码长度上限 72'
    return
  }
  await submitUpdate(row, { resetPassword: value }, { kind: 'password', accountId: row.id, password: value })
}

/** API-009：`{roleIds[], version}`；角色清单来自 API-010，只取本端（HUB）角色。 */
async function openRoles() {
  const row = detailRow.value
  if (!row) {
    return
  }
  error.value = ''
  try {
    roleOptions.value = await auth.http().get<RoleItem[]>('/hub/api/roles?client=HUB')
    // API-007 的 roles[] 是角色编码，API-009 要 roleIds[]，按编码映射回本端角色清单。
    checkedRoleIds.value = roleOptions.value
      .filter((role) => row.roles.includes(role.roleCode))
      .map((role) => role.id)
    rolesDrawer.value = true
  } catch (e) {
    error.value = describeApiError(e)
  }
}

async function submitRoles() {
  const row = detailRow.value
  if (!row) {
    return
  }
  const roleIds = [...checkedRoleIds.value]
  if (submitting.value) {
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const data = await auth.http().put<AcceptTicket>(
      `/hub/api/accounts/${row.id}/roles`,
      { roleIds, version: row.version },
      newIdempotencyKey(),
    )
    acceptNo.value = data.acceptNo
    pendingOp.value = { kind: 'roles', accountId: row.id, roleIds }
    rolesDrawer.value = false
    detailVisible.value = false
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

/** UI-F03：失败给重提入口，保留原输入；重提前按 API-007 取最新 version。 */
async function retryLast() {
  const op = pendingOp.value
  if (!op) {
    return
  }
  error.value = ''
  try {
    const fresh = await auth.http().get<AccountItem>(`/hub/api/accounts/${op.accountId}`)
    if (op.kind === 'status') {
      await submitUpdate(fresh, { status: op.status }, op)
    } else if (op.kind === 'password') {
      await submitUpdate(fresh, { resetPassword: op.password }, op)
    } else {
      detailRow.value = fresh
      checkedRoleIds.value = [...op.roleIds]
      await submitRoles()
    }
  } catch (e) {
    error.value = describeApiError(e)
  }
}

/** 受理结果到达后再刷新列表：写操作不当作提交即生效（D20）。 */
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
    <template #header>账号管理</template>
    <AcceptPanel
      :accept-no="acceptNo"
      :poll="pollAcceptance"
      @retry="retryLast"
      @settled="handleSettled"
    />
    <FilterBar @search="load" @reset="() => { filter.keyword = ''; filter.status = ''; load() }">
      <FormInput v-model="filter.keyword" placeholder="账号名" style="width: 200px" />
      <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
        <el-option label="启用" value="ENABLED" />
        <el-option label="停用" value="DISABLED" />
      </el-select>
    </FilterBar>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon style="margin-bottom: 12px" />
    <div v-if="canCreate" style="margin-bottom: 12px">
      <ActionButton variant="primary" class="create-account" @click="drawer.visible = true">新建账号</ActionButton>
    </div>
    <DataTable :data="items" :loading="loading" :total="total" :page="page" :size="size">
      <el-table-column prop="accountName" label="账号" min-width="160" />
      <el-table-column prop="employeeId" label="员工 ID" width="140" />
      <el-table-column label="角色" min-width="220">
        <template #default="{ row }">{{ row.roles.join('、') }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusTag :label="accountStatusLabels[row.status]" :type="accountStatusTags[row.status]" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <ActionButton variant="text" @click="openDetail(row)">详情</ActionButton>
        </template>
      </el-table-column>
    </DataTable>

    <DetailDrawer
      v-model="detailVisible"
      :title="detailRow?.accountName ?? ''"
      subtitle="账号详情"
      :status="detailRow ? accountStatusLabels[detailRow.status] : undefined"
      :status-type="detailRow ? accountStatusTags[detailRow.status] : undefined"
      :sections="detailSections"
    >
      <template #footer>
        <ActionButton v-if="canUpdate" variant="secondary" :disabled="submitting" @click="toggleStatus">{{ toggleLabel }}</ActionButton>
        <ActionButton v-if="canUpdate" variant="secondary" :disabled="submitting" @click="openPassword">重置密码</ActionButton>
        <ActionButton v-if="canAssignRole" variant="secondary" :disabled="submitting" @click="openRoles">分配角色</ActionButton>
      </template>
    </DetailDrawer>

    <el-dialog v-model="passwordDialog.visible" title="重置密码" width="420px">
      <el-form label-width="90px">
        <FormSection title="重置凭证">
          <el-form-item label="新密码">
            <FormInput
              v-model="passwordDialog.value"
              type="password"
              maxlength="72"
              show-word-limit
              placeholder="非空，长度上限 72"
            />
          </el-form-item>
        </FormSection>
      </el-form>
      <template #footer>
        <ActionButton variant="secondary" @click="passwordDialog.visible = false">取消</ActionButton>
        <ActionButton variant="primary" :disabled="submitting" @click="submitPassword">提交</ActionButton>
      </template>
    </el-dialog>

    <ActionDrawer v-model="rolesDrawer" :title="`分配角色 · ${detailRow?.accountName ?? ''}`" :width="480">
      <p class="roles-hint">按本端（运营端）角色整体替换，空选表示清空。</p>
      <el-checkbox-group v-model="checkedRoleIds">
        <el-checkbox v-for="role in roleOptions" :key="role.id" :value="role.id">
          {{ role.roleName }}（{{ role.roleCode }}）
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <ActionButton variant="secondary" @click="rolesDrawer = false">取消</ActionButton>
        <ActionButton variant="primary" :disabled="submitting" @click="submitRoles">提交</ActionButton>
      </template>
    </ActionDrawer>

    <ActionDrawer v-model="drawer.visible" title="新建账号" :width="480">
      <el-form label-width="100px">
        <FormSection title="账号信息">
          <el-form-item label="账号">
            <FormInput v-model="drawer.accountName" placeholder="账号" />
          </el-form-item>
          <el-form-item label="员工 ID">
            <FormInput v-model="drawer.employeeId" placeholder="员工内部主键（数字标识）" />
          </el-form-item>
        </FormSection>
        <FormSection title="初始凭证">
          <el-form-item label="初始密码">
            <FormInput v-model="drawer.initialPassword" type="password" placeholder="初始密码" />
          </el-form-item>
        </FormSection>
      </el-form>
      <template #footer>
        <ActionButton variant="secondary" @click="drawer.visible = false">取消</ActionButton>
        <ActionButton variant="primary" :disabled="submitting" @click="submitCreate">提交</ActionButton>
      </template>
    </ActionDrawer>
  </el-card>
</template>

<style scoped>
.roles-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
