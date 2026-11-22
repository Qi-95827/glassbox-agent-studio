import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nProvider, useI18n } from './I18nProvider';
import { setModuleLocale, translate } from './index';
import StatusChip from '../components/StatusChip';

function Probe() {
  const { locale, t, toggleLocale } = useI18n();
  return (
    <div>
      <span data-testid="locale">{locale}</span>
      <span data-testid="items">{t('common.items', { count: 12 })}</span>
      <span data-testid="missing">{t('missing.translation.key')}</span>
      <span data-testid="save">{t('common.save')}</span>
      <button type="button" onClick={toggleLocale}>
        toggle
      </button>
    </div>
  );
}

describe('I18nProvider', () => {
  it('支持英文、中文切换与模板插值，并同步 html lang', async () => {
    const user = userEvent.setup();
    render(
      <I18nProvider initialLocale="zh-CN">
        <Probe />
      </I18nProvider>
    );

    expect(screen.getByTestId('locale')).toHaveTextContent('zh-CN');
    expect(screen.getByTestId('items')).toHaveTextContent('12 项');
    expect(screen.getByTestId('save')).toHaveTextContent('保存');
    expect(screen.getByTestId('missing')).toHaveTextContent('missing.translation.key');
    expect(document.documentElement.lang).toBe('zh-CN');

    await user.click(screen.getByRole('button', { name: 'toggle' }));
    expect(screen.getByTestId('locale')).toHaveTextContent('en-US');
    expect(screen.getByTestId('items')).toHaveTextContent('12 items');
    expect(screen.getByTestId('save')).toHaveTextContent('Save');
    expect(document.documentElement.lang).toBe('en-US');
    expect(document.title).toContain('Visual Code Agent Workbench');
  });

  it('英文缺失 key 时回退中文，再回退 key 本身', () => {
    expect(translate('en-US', 'common.save')).toBe('Save');
    expect(translate('en-US', 'common.notYetTranslated')).toBe('common.notYetTranslated');
    expect(translate('fr-FR', 'common.save')).toBe('保存');
  });

  it('未包裹 Provider 时默认中文，英文 Provider 下组件渲染英文', () => {
    // 前一个用例切换过语言；独立组件应显式回到模块默认中文再断言。
    setModuleLocale('zh-CN');
    const { unmount } = render(<StatusChip value="RUNNING" />);
    expect(screen.getByText('运行中')).toBeInTheDocument();
    unmount();

    render(
      <I18nProvider initialLocale="en-US">
        <StatusChip value="RUNNING" />
      </I18nProvider>
    );
    expect(screen.getByText('Running')).toBeInTheDocument();
  });
});
