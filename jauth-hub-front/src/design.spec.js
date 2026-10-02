import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// vitest 默认不处理 CSS（导入得空串，jsdom 下 import.meta.url 又是 http 方案），
// 直接按工程根相对路径 fs 读源文件做在场冒烟
const css = readFileSync(resolve(process.cwd(), 'src/assets/app.css'), 'utf8');

// 设计 token 与组件类在场冒烟（B2a 设计系统落盘的契约面）：B2b 自助面按这些类名拼装，
// token 或分节类被改名/删除时此处先红。
describe('设计系统 app.css', () => {
  it('token 在场：色彩/字号/间距/圆角/阴影自定义属性', () => {
    for (const token of [
      '--color-canvas',
      '--color-surface',
      '--color-accent',
      '--color-danger',
      '--color-success',
      '--font-size-body',
      '--space-1',
      '--radius-md',
      '--shadow-card',
    ]) {
      expect(css).toContain(token + ':');
    }
  });

  it('组件类在场：按钮三型/表单/警示条/徽标/数据表/空态/对话框', () => {
    for (const cls of [
      '.btn.primary',
      '.btn.secondary',
      '.btn.danger',
      '.btn:disabled',
      '.field',
      '.field-error',
      '.card',
      '.panel',
      '.alert.error',
      '.alert.success',
      '.alert.info',
      '.pill',
      '.data-table',
      '.empty',
      '.dialog',
    ]) {
      expect(css).toContain(cls);
    }
  });

  it('暗色教学皮肤只活在 /front/demo 教学区分区：主站无渐变/侧栏/步骤条/教学块，demo 分区俱全（B4 宪法）', () => {
    const cut = css.indexOf('---------- 16.');
    expect(cut).toBeGreaterThan(0); // 教学区分节在场（被误删即此断言先红）
    const mainSite = css.slice(0, cut);
    const demoZone = css.slice(cut);
    for (const frag of ['gradient', '.sidebar', '.steps', '.teach', '.httplog']) {
      expect(mainSite).not.toContain(frag);
    }
    expect(demoZone).toContain('gradient');
    expect(demoZone).toContain('.demo-steps');
    expect(demoZone).toContain('.demo-log-entries');
    expect(demoZone).toContain('.demo-teach');
  });
});
