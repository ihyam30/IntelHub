/**
 * @param {{ auth?: unknown, guest?: unknown, admin?: unknown }} meta
 * @param {{ token: string, isAdmin: boolean }} auth
 * @returns {string | undefined}
 */
export function resolveRouteRedirect(meta, auth) {
  if (meta.auth && !auth.token) return '/auth'
  if (meta.guest && auth.token) return '/chat'
  if (meta.admin && !auth.isAdmin) return '/chat'
}
