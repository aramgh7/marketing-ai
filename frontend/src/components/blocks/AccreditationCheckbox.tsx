"use client";

interface AccreditationCheckboxProps {
  required?: boolean;
  labelText?: string;
  onChange?: (checked: boolean) => void;
}

export default function AccreditationCheckbox({
  required = false,
  labelText = "I confirm that I am an accredited investor as defined under SEC Rule 501(a)",
  onChange,
}: AccreditationCheckboxProps) {
  return (
    <div className="accreditation-checkbox-atom my-4">
      <label className="flex items-start gap-3 cursor-pointer group">
        <input
          type="checkbox"
          name="self_accredited"
          required={required}
          className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          onChange={(e) => onChange?.(e.target.checked)}
        />
        <span className="text-sm text-gray-700 group-hover:text-gray-900">
          {labelText}
        </span>
      </label>
      <p className="text-xs text-gray-400 mt-1 ml-7">
        Open to accredited investors. This opportunity is not an offer to sell
        securities to non-accredited investors.
      </p>
    </div>
  );
}
