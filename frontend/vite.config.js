import { defineConfig, loadEnv } from 'vite'
import process from 'node:process'
import react from '@vitejs/plugin-react'

const PRODUCTION_API_ORIGIN = 'https://rabbithole-api.samwobschall.dev'

// What the built page may load. If a script were ever injected, it couldn't load code or send data
// anywhere else. Only added to the build: the dev server needs inline scripts for hot reloading.
function contentSecurityPolicy(apiOrigins) {
  return [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    // Spotify serves artist photos from these hosts.
    "img-src 'self' https://*.scdn.co https://*.spotifycdn.com",
    `connect-src 'self' ${apiOrigins.join(' ')}`,
    // The track preview player.
    'frame-src https://open.spotify.com',
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ')
}

function contentSecurityPolicyPlugin(apiOrigins) {
  return {
    name: 'content-security-policy',
    apply: 'build',
    transformIndexHtml() {
      return [
        {
          tag: 'meta',
          attrs: { 'http-equiv': 'Content-Security-Policy', content: contentSecurityPolicy(apiOrigins) },
          injectTo: 'head-prepend',
        },
      ]
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const configuredApi = loadEnv(mode, process.cwd(), 'VITE_').VITE_API_BASE_URL
  const apiOrigins = [...new Set([PRODUCTION_API_ORIGIN, configuredApi && new URL(configuredApi).origin].filter(Boolean))]

  return {
    plugins: [react(), contentSecurityPolicyPlugin(apiOrigins)],
  }
})
