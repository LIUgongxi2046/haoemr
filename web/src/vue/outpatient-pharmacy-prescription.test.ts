import { describe, expect, it } from 'vitest';

const views = import.meta.glob('./views/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

describe('门诊药房处方来源约束', () => {
  it('只允许从当前患者已签署且未超量的药品处方项开始调配', () => {
    const pharmacy = views['./views/OutpatientPharmacyPage.vue'];
    expect(pharmacy).toContain('loadOutpatientWorkspaceSnapshot');
    expect(pharmacy).toContain("['SIGNED', 'ACTIVE', 'IN_PROGRESS']");
    expect(pharmacy).toContain("item.item_type === 'MEDICATION'");
    expect(pharmacy).toContain('remainingQuantity > 0');
    expect(pharmacy).toContain('order_id: prescription.orderId');
    expect(pharmacy).toContain('order_item_id: prescription.itemId');
    expect(pharmacy).toContain('form.quantity > prescription.remainingQuantity');
    expect(pharmacy).not.toContain('v-model="form.drugCode"');
  });
});
