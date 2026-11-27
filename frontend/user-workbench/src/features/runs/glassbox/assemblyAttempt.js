/** 装配证据的唯一选取规则：最近一次拥有装配快照的尝试（与后端 toolFunnel 完全一致）。 */
export function latestAssemblyAttempt(attempts) {
  return [...(attempts ?? [])].reverse().find(item => item?.assembly) ?? null;
}
