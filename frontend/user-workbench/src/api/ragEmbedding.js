import { get, put } from './client';

export const ragEmbeddingApi = {
  get: () => get('/rag-embedding'),
  save: payload => put('/rag-embedding', payload)
};
