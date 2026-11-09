import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';

import CommandChip from './CommandChip';
import DataTable, { type Column } from './DataTable';
import EnvBadge from './EnvBadge';
import StatusBadge from './StatusBadge';

type Row = { id: string; command: string; env: string; outcome: string; who: string };

const ROWS: Row[] = [
  { id: '1', command: 'update', env: 'PRD', outcome: 'Sucesso', who: 'dba.demo' },
  { id: '2', command: 'rollback-count', env: 'HML', outcome: 'Falha', who: 'dev.demo' },
  { id: '3', command: 'checks run', env: 'DEV', outcome: 'Bloqueado', who: 'dev.demo' },
  { id: '4', command: 'diff', env: 'TST', outcome: 'Drift', who: 'admin.lqm' },
];

const COLUMNS: Column<Row>[] = [
  { key: 'command', header: 'Comando', sortKey: 'command', cell: (r) => <CommandChip command={r.command} /> },
  { key: 'env', header: 'Ambiente', cell: (r) => <EnvBadge code={r.env} /> },
  { key: 'outcome', header: 'Resultado', sortKey: 'outcome', cell: (r) => <StatusBadge>{r.outcome}</StatusBadge> },
  { key: 'who', header: 'Autor', cell: (r) => r.who },
];

const meta = {
  title: 'Components/DataTable',
  component: DataTable<Row>,
  args: { columns: COLUMNS, rows: ROWS, rowKey: (r: Row) => r.id, caption: 'Execuções' },
} satisfies Meta<typeof DataTable<Row>>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

function SortableTable(args: React.ComponentProps<typeof DataTable<Row>>) {
  const [sort, setSort] = useState('command,asc');
  return (
    <DataTable {...args} sort={sort}
      onSort={(key) => setSort((now) => (now === `${key},asc` ? `${key},desc` : `${key},asc`))} />
  );
}

/** The server sorts: the table only shows the arrow and reports the click. */
export const Sortable: Story = { render: (args) => <SortableTable {...args} /> };

export const ClickableRows: Story = {
  args: { onRowClick: () => undefined, rowLabel: (r: Row) => `Abrir ${r.command}` },
};

/** A failing row is tinted. */
export const FailingRowTinted: Story = {
  args: { rowClass: (r: Row) => (r.outcome === 'Falha' ? 'bg-danger-bg/40' : undefined) },
};

export const Loading: Story = { args: { loading: true, rows: [], skeletonRows: 5 } };
