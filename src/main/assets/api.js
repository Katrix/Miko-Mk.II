import queryString from 'query-string'
import config from './config'

export class API {
  static async request(url, method = 'GET', data = {}) {
    const isFormData = data instanceof FormData
    const isBodyRequest = method === 'POST' || method === 'PUT' || method === 'PATCH'

    const query = isFormData || isBodyRequest || !Object.entries(data).length ? '' : '?' + queryString.stringify(data)
    const body = isBodyRequest ? (isFormData ? data : JSON.stringify(data)) : undefined

    const headers = {}
    if (!isFormData) {
      headers['Content-Type'] = 'application/json'
    }
    if (typeof csrf !== 'undefined') {
      headers['Csrf-Token'] = csrf
    }

    const res = await fetch(config.backendUrl + 'api/' + url + query, {
      method,
      headers,
      body,
      // Technically not needed, but some internal compat stuff assumes cookies will be present
      mode: config.production ? 'same-origin' : 'cors',
      credentials: 'include',
    })

    if (res.ok) {
      if (res.status !== 204) {
        return await res.json()
      } else {
        return {}
      }
    } else {
      throw res.status
    }
  }
}
