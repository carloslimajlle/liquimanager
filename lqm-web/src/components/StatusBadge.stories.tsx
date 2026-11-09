import type { Meta, StoryObj } from '@storybook/react-vite';

import StatusBadge from './StatusBadge';

const meta = {
  title: 'Components/StatusBadge',
  component: StatusBadge,
} satisfies Meta<typeof StatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Success: Story = { args: { children: 'Sucesso' } };
export const Failure: Story = { args: { children: 'Falha' } };
export const Blocked: Story = { args: { children: 'Bloqueado' } };
export const Drift: Story = { args: { children: 'Drift' } };
export const Pending: Story = { args: { children: 'Pendente' } };
/** Text that is not known falls back to neutral. */
export const Unknown: Story = { args: { children: 'Qualquer coisa' } };
/** A tone can be forced: the right way once a second locale ships. */
export const ExplicitTone: Story = { args: { children: 'In review', tone: 'purple' } };

/** RNF-13: status is never colour alone, every badge carries its text. */
export const AllTones: Story = {
  args: { children: 'Sucesso' },
  render: () => (
    <div className="flex flex-wrap gap-2">
      {(['success', 'danger', 'warning', 'info', 'purple', 'minor', 'neutral'] as const).map((tone) => (
        <StatusBadge key={tone} tone={tone}>{tone}</StatusBadge>
      ))}
    </div>
  ),
};
