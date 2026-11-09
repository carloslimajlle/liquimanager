import type { Meta, StoryObj } from '@storybook/react-vite';

import OriginBadge from './OriginBadge';

const meta = {
  title: 'Components/OriginBadge',
  component: OriginBadge,
} satisfies Meta<typeof OriginBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The pipeline said which merge request it runs for: the number, linked, and the title. */
export const MergeRequest: Story = {
  args: { origin: { branch: 'chore/rename-004', mrIid: '18', mrTitle: 'Renomeia o arquivo do changeset 004', mrUrl: 'https://git.example/-/merge_requests/18' } },
};
/** No merge request in the report (a plain feature branch): the branch is all there is to say. */
export const Branch: Story = { args: { origin: { branch: 'feat/categoria-5-6' } } };
export const Unknown: Story = { args: { origin: undefined } };
