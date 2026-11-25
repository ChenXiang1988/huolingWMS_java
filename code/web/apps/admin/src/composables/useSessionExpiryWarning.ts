import { ElMessageBox } from 'element-plus'
import { onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, describeApiError } from '@wms/request'
import { useAuthStore } from '../stores/auth'

/**
 * `12` 的 `UI-X05`：会话超时前预警，剩余 5 分钟提示并可续期。
 * 阈值只在 `EXPIRY_WARNING_LEAD_MINUTES` 一处定义，续期走契约 API-002。
 */
export const EXPIRY_WARNING_LEAD_MINUTES = 5

const LEAD_MS = EXPIRY_WARNING_LEAD_MINUTES * 60 * 1000
const NOTICE_TITLE = '会话即将到期'
const NOTICE_MESSAGE = `访问令牌将在 ${EXPIRY_WARNING_LEAD_MINUTES} 分钟内到期。立即续期可继续当前操作；稍后不再重复提示，到期后需重新登录。`

/**
 * 预警只在挂了外壳的页面（即已登录、有菜单）里启用，登录页不排提示。
 * 提示形态取既有的 `ElMessageBox`（`AccountsView`／`RolesView` 已在用），不新造一套外壳；
 * 不用 `ElNotification` 是因为 `12` 的 `UI-C23` 规定通知只承载异步任务完成。
 */
export function useSessionExpiryWarning() {
  const auth = useAuthStore()
  const router = useRouter()

  let timer: number | undefined
  /** 已提示过的到期时刻（毫秒）。同一周期只提示一次，点「稍后」后不再重复弹。 */
  let warnedAt: number | null = null
  let renewing = false

  function clearTimer() {
    if (timer !== undefined) {
      window.clearTimeout(timer)
      timer = undefined
    }
  }

  /** AUTH_1004 的处置按契约「调用方处置」列：跳登录页，并留下回跳地址（UI-X05）。 */
  function redirectToLogin() {
    const current = router.currentRoute.value.fullPath
    auth.$reset()
    const target = current.startsWith('/login')
      ? { path: '/login' }
      : { path: '/login', query: { redirect: current } }
    router.replace(target).catch(() => {
      // 导航被取消或重复时无需处理：会话已清空，守卫下一次判定仍会回登录页。
    })
  }

  /** 「立即续期」：调 API-002；成功即关闭提示，失败按错误码分流（见下）。 */
  async function renew(loadingTarget: { confirmButtonLoading: boolean }, close: () => void) {
    loadingTarget.confirmButtonLoading = true
    try {
      await auth.refreshSession()
      close()
    } catch (error) {
      if (error instanceof ApiError && error.code === 1004) {
        close()
        redirectToLogin()
        return
      }
      throw error
    } finally {
      loadingTarget.confirmButtonLoading = false
    }
  }

  async function prompt(scheduledFor: number) {
    // 期间已续期（到期时刻变了）、已登出、或已过到期时刻：不弹，交给请求层 401 的那次续期兜底。
    if (auth.expiresAt !== scheduledFor || !auth.loggedIn || Date.now() >= scheduledFor) {
      return
    }
    warnedAt = scheduledFor

    try {
      await ElMessageBox.confirm(NOTICE_MESSAGE, NOTICE_TITLE, {
        confirmButtonText: '立即续期',
        cancelButtonText: '稍后',
        type: 'warning',
        closeOnClickModal: false,
        beforeClose: async (action, instance, done) => {
          // 「稍后」、关闭，或提示期间会话已被登出：直接收起，不再续期。
          if (action !== 'confirm' || !auth.loggedIn) {
            done()
            return
          }
          if (renewing) {
            return
          }
          renewing = true
          try {
            await renew(instance, done)
          } catch (error) {
            // 其余失败就地展示错误码与人话（UI-S06），两个出口保留，可再试或稍后。
            instance.message = `${NOTICE_MESSAGE} 续期失败：${describeApiError(error)}`
          } finally {
            renewing = false
          }
        },
      })
    } catch {
      // 点「稍后」或关闭提示：本周期已记 warnedAt，不再重复弹。
    }
  }

  /**
   * 按内存里的到期时刻排提示：`delay = 到期时刻 - 现在 - 预警提前量`。
   * `delay <= 0` 表示有效期本就不超过 5 分钟，不排提示（否则一登录就弹），
   * 此时由请求层「401 续期一次」兜住。
   */
  function arm() {
    clearTimer()
    const expiresAt = auth.expiresAt
    if (!auth.loggedIn || expiresAt === null || warnedAt === expiresAt) {
      return
    }
    const delay = expiresAt - Date.now() - LEAD_MS
    if (delay <= 0) {
      return
    }
    timer = window.setTimeout(() => {
      timer = undefined
      void prompt(expiresAt)
    }, delay)
  }

  // 登录、续期（含 401 静默续期）都会重算到期时刻，计时随之重置。
  watch(() => auth.expiresAt, arm)
  // 登出与计时互斥：会话结束即取消，不再弹。
  watch(
    () => auth.loggedIn,
    (loggedIn) => {
      if (loggedIn) {
        arm()
      } else {
        clearTimer()
      }
    },
  )

  // 页面刷新后由路由守卫静默续期写入到期时刻，这里按其恢复计时，不重复排定。
  arm()

  onUnmounted(clearTimer)
}
