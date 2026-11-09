import type { Meta, StoryObj } from '@storybook/react-vite';

import CommandChip from './CommandChip';

const meta = {
  title: 'Components/CommandChip',
  component: CommandChip,
} satisfies Meta<typeof CommandChip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Update: Story = { args: { command: 'update' } };
export const ChecksRun: Story = { args: { command: 'checks run' } };
export const Rollback: Story = { args: { command: 'rollback-count' } };
export const Diff: Story = { args: { command: 'diff' } };
export const Tag: Story = { args: { command: 'tag' } };

/** The Liquibase terms stay in English and in mono (docs/10 § 4). */
export const AllCommands: Story = {
  args: { command: 'update' },
  render: () => (
    <div className="flex gap-2">
      {['update', 'checks run', 'rollback', 'diff', 'tag'].map((command) => <CommandChip key={command} command={command} />)}
    </div>
  ),
};
