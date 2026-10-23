"use client";

/**
 * Audit F-19: a failed load must not look like "no data". Pages render this instead of the empty
 * state, with the server's message (ApiError.message → `detail` from the backend) and a Retry button.
 */
export default function LoadError({
  message,
  onRetry,
  title = "Could not load data",
}: {
  message?: string | null;
  onRetry?: () => void;
  title?: string;
}) {
  return (
    <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
      <div className="font-semibold">{title}</div>
      {message && <div className="mt-1 text-red-700 break-words">{message}</div>}
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-3 px-3 py-1.5 rounded bg-white border border-red-300 text-red-700 text-xs font-medium hover:bg-red-100"
        >
          Retry
        </button>
      )}
    </div>
  );
}
