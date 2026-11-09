/** Liquibase command chip. The terms stay in English and in mono (docs/10 § 4). */
const TONES: Record<string, string> = {
  'update': 'bg-primary-50 text-primary',
  'checks run': 'bg-purple-bg text-purple',
  'tag': 'bg-[#eef0f4] text-text-2',
};

function toneFor(command: string): string {
  if (TONES[command]) return TONES[command];
  if (command.startsWith('rollback')) return 'bg-warning-bg text-warning';
  if (command.startsWith('diff')) return 'bg-teal-bg text-teal';
  return 'bg-[#eef0f4] text-text-2';
}

export default function CommandChip({ command }: { command: string }) {
  return (
    <span
      className={`inline-flex whitespace-nowrap rounded-badge px-2 py-0.5 font-mono text-[12px] font-semibold ${toneFor(command)}`}
    >
      {command}
    </span>
  );
}
