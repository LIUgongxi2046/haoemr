import { reactive } from 'vue';

export interface BrandConfig {
  systemName: string;
  logoUrl: string;
}

export const DEFAULT_BRAND_CONFIG: Readonly<BrandConfig> = Object.freeze({
  systemName: 'HaoEMR',
  logoUrl: '/brand/haonan-medical-ai-logo.png',
});

const STORAGE_KEY = 'haoemr.brand-config';
const MAX_LOGO_DATA_URL_LENGTH = 2_000_000;

function validLogoUrl(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > MAX_LOGO_DATA_URL_LENGTH) return false;
  return value === ''
    || value.startsWith('/')
    || /^https?:\/\//i.test(value)
    || /^data:image\/(?:png|jpeg|webp|gif);base64,/i.test(value);
}

function normalize(value: unknown): BrandConfig {
  if (!value || typeof value !== 'object') return { ...DEFAULT_BRAND_CONFIG };
  const candidate = value as Partial<BrandConfig>;
  const systemName = typeof candidate.systemName === 'string' ? candidate.systemName.trim().slice(0, 80) : '';
  const logoUrl = validLogoUrl(candidate.logoUrl) ? candidate.logoUrl : DEFAULT_BRAND_CONFIG.logoUrl;
  return {
    systemName: systemName || DEFAULT_BRAND_CONFIG.systemName,
    logoUrl: logoUrl || DEFAULT_BRAND_CONFIG.logoUrl,
  };
}

function readStored(): BrandConfig {
  if (typeof localStorage === 'undefined') return { ...DEFAULT_BRAND_CONFIG };
  try {
    return normalize(JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null'));
  } catch {
    return { ...DEFAULT_BRAND_CONFIG };
  }
}

export const brandConfig = reactive<BrandConfig>(readStored());

export function saveBrandConfig(input: Partial<BrandConfig>): BrandConfig {
  const next = normalize({ ...brandConfig, ...input });
  brandConfig.systemName = next.systemName;
  brandConfig.logoUrl = next.logoUrl;
  if (typeof localStorage !== 'undefined') localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  return next;
}

export function resetBrandConfig(): BrandConfig {
  return saveBrandConfig(DEFAULT_BRAND_CONFIG);
}

export function readLogoFile(file: File): Promise<string> {
  if (!/^image\/(?:png|jpeg|webp|gif)$/i.test(file.type)) {
    return Promise.reject(new Error('Logo 仅支持 PNG、JPG、WEBP 或 GIF 图片。'));
  }
  if (file.size > 1_500_000) return Promise.reject(new Error('Logo 文件不能超过 1.5 MB。'));
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : '';
      if (!validLogoUrl(result)) reject(new Error('Logo 文件读取失败，请重新选择图片。'));
      else resolve(result);
    };
    reader.onerror = () => reject(new Error('Logo 文件读取失败，请重新选择图片。'));
    reader.readAsDataURL(file);
  });
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key !== STORAGE_KEY) return;
    let stored: unknown = null;
    try { stored = JSON.parse(event.newValue || 'null'); } catch { stored = null; }
    const next = normalize(stored);
    brandConfig.systemName = next.systemName;
    brandConfig.logoUrl = next.logoUrl;
  });
}
