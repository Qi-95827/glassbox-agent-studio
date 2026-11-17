package dev.workbench.framework.event;

import java.util.List;

/** 事件增量查询结果；下次从 nextCursor 之后继续。 */
public record EventPage(List<RunEvent> events, long nextCursor) {}
