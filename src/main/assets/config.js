export default {
  backendUrl: process.env.NODE_ENV === 'production' ? '/' : 'http://localhost:9000/',
  wsBackendBaseUrl: process.env.NODE_ENV === 'production' ? 'wss://' + location.host : 'ws://localhost:9000/',
  production: process.env.NODE_ENV === 'production',
  clientId: '288367502130413568',
}
