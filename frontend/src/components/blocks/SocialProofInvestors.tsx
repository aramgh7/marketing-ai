interface Quote {
  text: string;
  author?: string;
  amount?: string;
}

interface SocialProofInvestorsProps {
  quotes: Quote[];
  showAmounts?: boolean;
}

export default function SocialProofInvestors({
  quotes,
  showAmounts = false,
}: SocialProofInvestorsProps) {
  if (!quotes.length) return null;
  return (
    <div className="social-proof-investors-atom my-6 space-y-4">
      {quotes.map((q, i) => (
        <blockquote key={i} className="border-l-4 border-blue-500 pl-4 py-1">
          <p className="text-gray-700 italic">&ldquo;{q.text}&rdquo;</p>
          {q.author && (
            <footer className="text-sm text-gray-500 mt-1">
              — {q.author}
              {showAmounts && q.amount && (
                <span className="ml-2 text-blue-600 font-medium">{q.amount}</span>
              )}
            </footer>
          )}
        </blockquote>
      ))}
    </div>
  );
}
