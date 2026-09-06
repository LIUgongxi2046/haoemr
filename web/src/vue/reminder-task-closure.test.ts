import { describe, expect, it } from 'vitest';
import assistantApi from '../api/assistant.ts?raw';
import reminderPage from './views/AiReminderDetailPage.vue?raw';
import taskPage from './views/ClinicalTasksPage.vue?raw';

describe('reminder-to-task closure', () => {
  it('uses the durable conversion API and displays a single linked task', () => {
    expect(assistantApi).toContain("'/clinical-reminder-conversions'");
    expect(assistantApi).toContain('clinicalReminderConversionCreateRequestWireSchema');
    expect(reminderPage).toContain('convertClinicalReminderToTask');
    expect(reminderPage).toContain('listClinicalReminderConversions');
    expect(reminderPage).toContain('conversionFor(reminder)');
    expect(reminderPage).toContain('justConverted.value[reminder.reminder_id]');
    expect(reminderPage).toContain('justConverted.value = { ...justConverted.value, [reminder.reminder_id]: conversion }');
    expect(reminderPage).toContain('任务 …{{ conversionFor(reminder)?.clinical_task_id.slice(-8) }}');
    expect(reminderPage).toContain('await remindersQuery.refetch();');
    expect(reminderPage).toContain('await nextTick();');
    expect(reminderPage).toContain('await conversionsQuery.refetch();');
  });

  it('opens the task center scoped to reminder-originated tasks', () => {
    expect(reminderPage).toContain("mode: 'outpatient', source: 'REMINDER'");
    expect(taskPage).toContain('sourceFilter');
    expect(taskPage).toContain('task.source_type === sourceFilter.value');
    expect(taskPage).toContain('route.query.mode');
  });
});
