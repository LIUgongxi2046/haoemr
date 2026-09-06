import { describe, expect, it } from 'vitest';
import workbenchSource from './components/SimulationWorkbenchPage.vue?raw';
import hierarchySource from './views/SimulationHierarchyPage.vue?raw';
import shellSource from './components/ClinicalShell.vue?raw';
import overviewSource from './views/MockInterfacePage.vue?raw';
import routerSource from './router.ts?raw';
import { mockInterfaceSubmenus, simulationWorkbenches } from './simulation-workbenches';

describe('mock interface submenu contract', () => {
  it('registers one overview and all 17 configured workbenches', () => {
    expect(mockInterfaceSubmenus).toHaveLength(18);
    expect(Object.keys(simulationWorkbenches)).toHaveLength(17);
    expect(new Set(mockInterfaceSubmenus.map(([id]) => id)).size).toBe(18);
  });

  it('keeps every workbench actionable and safety documented', () => {
    for (const definition of Object.values(simulationWorkbenches)) {
      expect(definition.steps).toHaveLength(4);
      expect(definition.safeguards.length).toBeGreaterThanOrEqual(3);
      expect(definition.resultFocus.length).toBeGreaterThan(0);
      expect(definition.defaultEntity.trim()).not.toBe('');
      expect(definition.systemType.trim()).not.toBe('');
    }
  });

  it('keeps the scenario result and API documentation panels top-aligned', () => {
    expect(workbenchSource).toContain('.simulation-layout > .admin-panel + .admin-panel { margin-top: 0; }');
  });

  it('implements profile, scenario, run, detail and evidence as real third-to-seventh-level routes', () => {
    for (const routeName of [
      'mock-interface-profile', 'mock-interface-scenario', 'mock-interface-runs',
      'mock-interface-run-detail', 'mock-interface-run-evidence',
    ]) expect(routerSource).toContain(`name: '${routeName}'`);
    expect(hierarchySource).toContain('listMockInterfaceRuns');
    expect(hierarchySource).toContain('getMockInterfaceRun');
    expect(hierarchySource).toContain('getMockInterfaceEvidence');
    expect(hierarchySource).toContain('规则型、可复现、无临床写权');
  });

  it('binds invocations to published profiles and exposes durable run drill-downs', () => {
    expect(workbenchSource).toContain('production_adapter_state: \'SYNTHETIC_ONLY\'');
    expect(workbenchSource).toContain('critical_value_policy');
    expect(workbenchSource).toContain('五级·运行历史');
    expect(workbenchSource).toContain('六级·运行详情');
  });

  it('keeps retired AI simulation URLs as compatibility entries into the maintained real pages', () => {
    expect(routerSource).toContain("'ai-capture': '/ai-capture'");
    expect(routerSource).toContain("'model-connection': '/model-connection'");
    expect(routerSource).toContain("'model-routing': '/model-routing'");
    expect(overviewSource).toContain("delegated ? `/${workbenchId}` : `/mock-interfaces/${workbenchId}`");
    expect(routerSource).toContain("'agent': () => import('./views/AiCapturePage.vue')");
  });

});
