import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { useAuthStore } from '../store/authStore'
import { groupsFromToken } from './oidcConfig'

/** 把 react-oidc-context 的会话态同步进 authStore 镜像(供 UI/守卫同步读取)。 */
export default function AuthBridge() {
  const auth = useAuth()
  const set = useAuthStore((s) => s.set)
  const clear = useAuthStore((s) => s.clear)

  useEffect(() => {
    if (auth.isLoading) return
    if (auth.isAuthenticated && auth.user) {
      const profile = auth.user.profile
      set({
        status: 'authed',
        userId: profile.sub,
        username:
          (profile.preferred_username as string | undefined) ??
          (profile.name as string | undefined) ??
          profile.sub,
        authorities: groupsFromToken(auth.user.access_token),
      })
    } else {
      clear()
    }
  }, [auth.isLoading, auth.isAuthenticated, auth.user, set, clear])

  return null
}
