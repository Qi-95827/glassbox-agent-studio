import { get, post, resetCsrfToken } from './client';

export const authApi = {
  status: () => get('/auth/status'),
  initialize: async payload => {
    const result = await post('/auth/initialize', payload);
    resetCsrfToken();
    return result;
  },
  login: async payload => {
    const result = await post('/auth/login', payload);
    resetCsrfToken();
    return result;
  },
  logout: async () => {
    const result = await post('/auth/logout');
    resetCsrfToken();
    return result;
  },
  users: () => get('/auth/users'),
  addUser: payload => post('/auth/users', payload),
  claimLegacy: () => post('/auth/claim-legacy')
};
