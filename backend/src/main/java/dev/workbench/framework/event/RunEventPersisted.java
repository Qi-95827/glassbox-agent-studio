package dev.workbench.framework.event;

/** 事件已经提交到数据库；仅供观测层在事务提交后转发 SSE。 */
public record RunEventPersisted(RunEvent event) {}
