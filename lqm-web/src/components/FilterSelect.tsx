/**
 * A white pill with a native <select> inside (docs/10 FilterPill). Native on purpose:
 * it is keyboard- and screen-reader-accessible for free (RNF-13), and the option list
 * here is short enough that a custom popover would only add risk.
 */
export default function FilterSelect({
  label, value, options, onChange,
}: {
  label: string;
  value: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value: string) => void;
}) {
  return (
    <label className="relative inline-flex items-center">
      <span className="sr-only">{label}</span>
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="cursor-pointer appearance-none rounded-control border border-border bg-surface py-1.5 pr-7 pl-3 text-[12px] font-semibold shadow-card hover:bg-surface-alt"
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.value === '' ? label : option.label}
          </option>
        ))}
      </select>
      <span aria-hidden className="pointer-events-none absolute right-2.5 text-[9px]">▾</span>
    </label>
  );
}
