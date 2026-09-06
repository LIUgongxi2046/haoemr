import { afterEach, describe, expect, it, vi } from 'vitest';
import { loginClinicalSession } from './auth-session';

describe('loginClinicalSession', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reports unavailable backend separately from invalid credentials', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));

    await expect(loginClinicalSession('linwei', 'HaoEMR-dev!'))
      .rejects.toThrow('登录服务暂不可用，请确认验收后端已在 8080 端口启动');
  });

  it('uses the invalid credential message only for HTTP 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));

    await expect(loginClinicalSession('linwei', 'wrong-password'))
      .rejects.toThrow('用户名或密码错误');
  });

  it('reports unexpected backend responses with their status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })));

    await expect(loginClinicalSession('linwei', 'HaoEMR-dev!'))
      .rejects.toThrow('登录服务异常（HTTP 503），请检查验收后端');
  });
});
