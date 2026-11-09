import type { Meta, StoryObj } from '@storybook/react-vite';

import EnvBadge from './EnvBadge';

const meta = {
  title: 'Components/EnvBadge',
  component: EnvBadge,
} satisfies Meta<typeof EnvBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Dev: Story = { args: { code: 'DEV' } };
export const Prd: Story = { args: { code: 'PRD' } };
/** The list of environments is configurable (D-15): one with no colour token falls back to grey. */
export const NoToken: Story = { args: { code: 'SANDBOX' } };

export const AllEnvironments: Story = {
  args: { code: 'DEV' },
  render: () => (
    <div className="flex gap-2">
      {['DEV', 'TST', 'HML', 'PRD'].map((code) => <EnvBadge key={code} code={code} />)}
    </div>
  ),
};
