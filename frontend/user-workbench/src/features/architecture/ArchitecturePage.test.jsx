import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ArchitectureCanvas from './ArchitectureCanvas';
import ArchitectureLensButton from './ArchitectureLensButton';
import { ALL_ARCHITECTURE_TOPICS } from './architectureCatalog';
import { I18nProvider } from '../../i18n/I18nProvider';

function renderWithProviders(ui, { locale = 'zh-CN' } = {}) {
  window.localStorage.setItem('agent_studio_locale', locale);
  return render(
    <I18nProvider>
      <MemoryRouter>{ui}</MemoryRouter>
    </I18nProvider>
  );
}

describe('Architecture visualization', () => {
  it('renders all 16 architecture topics (8 main + 8 deep-dive) without crashing', () => {
    expect(ALL_ARCHITECTURE_TOPICS.length).toBe(16);
    for (const topic of ALL_ARCHITECTURE_TOPICS) {
      const { unmount } = renderWithProviders(<ArchitectureCanvas topicId={topic.id} />);
      expect(screen.getByText(topic.title['zh-CN'])).toBeInTheDocument();
      expect(screen.getByTestId('architecture-node-spotlight')).toBeInTheDocument();
      unmount();
    }
  });

  it('switches node spotlight when clicking a diagram gate in Tool Gateway', () => {
    renderWithProviders(<ArchitectureCanvas topicId="tool-gateway" />);
    const gate1 = screen.getByRole('button', { name: '工具注册与版本' });
    fireEvent.click(gate1);
    expect(screen.getByTestId('architecture-node-spotlight')).toHaveTextContent('ToolGateway.java');
  });

  it('drills down from main topic node into deep-dive sub-topic', () => {
    const onNavigate = vi.fn();
    renderWithProviders(
      <ArchitectureCanvas topicId="agent-assembly" onNavigateTopic={onNavigate} />
    );
    fireEvent.click(screen.getByRole('button', { name: '显式授权上下文' }));
    fireEvent.click(screen.getByRole('button', { name: /深入查看 05\.1 三层长期记忆/ }));
    expect(onNavigate).toHaveBeenCalledWith('layered-memory');
  });

  it('switches between backend and frontend sub-views in layers-stack', () => {
    renderWithProviders(<ArchitectureCanvas topicId="layers-stack" />);
    fireEvent.click(screen.getByRole('button', { name: /前端模块架构/ }));
    expect(screen.getByTestId('architecture-node-spotlight')).toHaveTextContent(
      'features/architecture/'
    );
  });

  it('renders English diagram and opens modal from ArchitectureLensButton', () => {
    renderWithProviders(<ArchitectureLensButton topic="handoff-envelope" />, { locale: 'en-US' });
    const trigger = screen.getByRole('button', { name: /Handoff Envelope/i });
    fireEvent.click(trigger);
    expect(screen.getByText('Handoff Envelope & Verification')).toBeInTheDocument();
    expect(screen.getByText('Explore all 16 diagrams in Architecture Atlas')).toBeInTheDocument();
  });
});
