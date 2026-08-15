/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_API_TARGET: string
  readonly VITE_WORKFLOW_TENANT: string
  readonly VITE_AUTH_ENABLED: string
  readonly VITE_CASDOOR_AUTHORITY: string
  readonly VITE_CASDOOR_CLIENT_ID: string
  readonly VITE_OIDC_SCOPE: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
