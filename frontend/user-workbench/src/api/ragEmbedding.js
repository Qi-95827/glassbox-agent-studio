import { get, post, put } from './client';

export const ragEmbeddingApi = {
  get: () => get('/rag-embedding'),
  save: payload => put('/rag-embedding', payload),
  probe: () => post('/rag-embedding/probe')
};
