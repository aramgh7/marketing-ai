import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Marketing AI Platform",
  description: "AI-powered ad optimization",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
