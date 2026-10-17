interface Disclaimer506cProps {
  text?: string;
  version?: string;
}

const DEFAULT_TEXT =
  "This is not an offer to sell, or a solicitation of an offer to buy, any security. " +
  "This offering is made pursuant to an exemption from registration under Rule 506(c) of " +
  "Regulation D of the Securities Act of 1933, as amended. This offering is available only " +
  "to verified accredited investors as defined in Rule 501(a) of Regulation D. Past " +
  "performance is not indicative of future results. Investment involves risk, including " +
  "possible loss of principal.";

export default function Disclaimer506c({ text, version = "v1.0" }: Disclaimer506cProps) {
  return (
    <div className="disclaimer-506c-atom mt-8 p-4 bg-gray-50 border border-gray-200 rounded-lg">
      <p className="text-xs text-gray-500 leading-relaxed">
        <span className="font-semibold">Legal Disclaimer ({version}): </span>
        {text ?? DEFAULT_TEXT}
      </p>
    </div>
  );
}
