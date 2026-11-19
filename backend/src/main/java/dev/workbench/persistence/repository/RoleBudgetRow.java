package dev.workbench.persistence.repository;

/** 角色预算投影。 */
public record RoleBudgetRow(
    String roleCode, Integer version, Integer maxModels, Integer maxTools) {}