// reCAPTCHA Enterprise v3 wrapper (vision §12.6).
// Returns null silently when NEXT_PUBLIC_RECAPTCHA_SITE_KEY is not set (dev mode).
export async function getRecaptchaToken(action: string): Promise<string | null> {
  const key = process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY;
  if (!key || typeof window === "undefined") return null;

  return new Promise((resolve) => {
    const w = window as unknown as { grecaptcha?: { ready: (cb: () => void) => void; execute: (k: string, opts: { action: string }) => Promise<string> } };
    if (!w.grecaptcha) {
      resolve(null);
      return;
    }
    w.grecaptcha.ready(() => {
      w.grecaptcha!
        .execute(key, { action })
        .then(resolve)
        .catch(() => resolve(null));
    });
  });
}
