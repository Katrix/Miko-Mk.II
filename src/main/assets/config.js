export default {
  backendUrl: window.EXTERNAL_CONFIG.backendUrl ?? (process.env.NODE_ENV === 'production' ? undefined : 'http://localhost:9000/'),
  wsMusicBackendUrl: window?.EXTERNAL_CONFIG?.wsMusicBackendUrl ?? (process.env.NODE_ENV === 'production' ? undefined : 'ws://localhost:9000/musicWs'),
  production: window?.EXTERNAL_CONFIG?.production ?? process.env.NODE_ENV === 'production',
  clientId: window?.EXTERNAL_CONFIG?.clientId ?? (process.env.NODE_ENV === 'production' ? undefined : '269964299131289600'),
}
