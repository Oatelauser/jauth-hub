import { describe, expect, it } from 'vitest';
import { fmtDateTime } from './format';

describe('fmtDateTime 展示口径', () => {
  it('空值出 —（SSR 三元同款）', () => {
    expect(fmtDateTime(null)).toBe('—');
    expect(fmtDateTime(undefined)).toBe('—');
    expect(fmtDateTime('')).toBe('—');
  });

  it('ISO 即时串出本地 yyyy-MM-dd HH:mm 形状', () => {
    expect(fmtDateTime('2026-09-29T10:00:00Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });
});
