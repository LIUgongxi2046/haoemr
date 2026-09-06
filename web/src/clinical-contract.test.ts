import { describe, expect, it } from 'vitest';
import { z } from 'zod';

import {
  ClinicalApiError, clinicalServiceError,
  filterOperationalTeamQueuePayload,
} from './clinical-api';
import { parseClinicalRequest, parseClinicalResponse } from './clinical-contract';

const schema = z.object({ patient_id: z.string().uuid() }).strict();

describe('clinical contract boundary', () => {
  it('identifies request contract failures without blaming the service response', () => {
    expect(() => parseClinicalRequest(schema, { patient_id: '' })).toThrowError(
      expect.objectContaining<Partial<ClinicalApiError>>({
        code: 'REQUEST_CONTRACT_MISMATCH',
        status: 400,
        message: expect.stringContaining('patient_id'),
      }),
    );
  });

  it('identifies response contract failures and exposes only the field path', () => {
    expect(() => parseClinicalResponse(schema, { patient_id: 'not-a-uuid' })).toThrowError(
      expect.objectContaining<Partial<ClinicalApiError>>({
        code: 'RESPONSE_CONTRACT_MISMATCH',
        status: 502,
        message: expect.stringContaining('patient_id'),
      }),
    );
  });

  it('returns validated contract data unchanged', () => {
    const payload = { patient_id: '018f0000-0000-7000-8000-000000000001' };
    expect(parseClinicalResponse(schema, payload)).toEqual(payload);
  });

  it('preserves Spring problem details instead of reducing every 400 to a generic message', () => {
    const error = clinicalServiceError({ detail: '委托截止时间必须晚于当前时间' }, 400);
    expect(error).toMatchObject({ code: 'HTTP_400', status: 400 });
    expect(error.message).toBe('委托截止时间必须晚于当前时间');
  });

  it('removes withdrawn legacy queue rows before validating queue identities', () => {
    expect(filterOperationalTeamQueuePayload([
      { queue_id: 'legacy-id', queue_status: 'WITHDRAWN' },
      { queue_id: '018f0000-0000-7000-8000-000000000002', queue_status: 'ENQUEUED' },
    ])).toEqual([
      { queue_id: '018f0000-0000-7000-8000-000000000002', queue_status: 'ENQUEUED' },
    ]);
  });
});
