import type { Meta, StoryObj } from '@storybook/react-vite';

import KpiCard from './KpiCard';

const meta = {
  title: 'Components/KpiCard',
  component: KpiCard,
  decorators: [(Story) => <div className="w-64"><Story /></div>],
} satisfies Meta<typeof KpiCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The first card of each band: blue, with a gradient. */
export const Hero: Story = {
  args: { label: 'EXECUÇÕES', value: '1.284', sub: 'últimos 30 dias', hero: true, trend: { value: '12%', direction: 'up', good: true } },
};

export const Plain: Story = {
  args: { label: 'Taxa de sucesso', value: '97,2', unit: '%', sub: 'meta: 95%', tone: 'success' },
};

/** A drop in failures is `down`, and good news: green. */
export const GoodDecrease: Story = {
  args: { label: 'Falhas', value: '8', trend: { value: '3', direction: 'down', good: true } },
};

export const BadIncrease: Story = {
  args: { label: 'Drift aberto', value: '14', tone: 'danger', trend: { value: '4', direction: 'up', good: false } },
};

export const Warning: Story = {
  args: { label: 'Aprovações pendentes', value: '3', tone: 'warning', sub: 'a mais antiga tem 2 dias' },
};
