import assert from 'node:assert/strict'
import { resolveRouteRedirect } from '../src/auth/route-policy.js'

const cases = [
  ['public route allows anonymous visitor', {}, { token: '', isAdmin: false }, undefined],
  ['protected route sends anonymous visitor to auth', { auth: true }, { token: '', isAdmin: false }, '/auth'],
  ['guest-only auth page sends signed-in visitor to chat', { guest: true }, { token: 'token', isAdmin: false }, '/chat'],
  ['regular protected route allows authenticated user', { auth: true }, { token: 'token', isAdmin: false }, undefined],
  ['admin route sends regular user to chat', { auth: true, admin: true }, { token: 'token', isAdmin: false }, '/chat'],
  ['anonymous admin route redirects to auth first', { auth: true, admin: true }, { token: '', isAdmin: false }, '/auth'],
  ['admin route allows authenticated admin', { auth: true, admin: true }, { token: 'token', isAdmin: true }, undefined],
]

for (const [name, meta, auth, expected] of cases) {
  assert.equal(resolveRouteRedirect(meta, auth), expected, name)
}

console.log(`Passed ${cases.length} route policy checks`)
