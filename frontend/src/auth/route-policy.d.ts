export type RouteAccessMeta = {
  auth?: unknown
  guest?: unknown
  admin?: unknown
}

export type RouteAuthState = {
  token: string
  isAdmin: boolean
}

export declare function resolveRouteRedirect(
  meta: RouteAccessMeta,
  auth: RouteAuthState,
): string | undefined
