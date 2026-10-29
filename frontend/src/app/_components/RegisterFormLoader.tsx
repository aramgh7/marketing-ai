"use client";
import dynamic from "next/dynamic";

const RegisterForm = dynamic(() => import("./RegisterForm"), {
  ssr: false,
  loading: () => (
    <div style={{ padding: "36px" }}>
      <div style={{ height: 26, background: "#f3f4f6", borderRadius: 6, marginBottom: 8, width: "65%" }} />
      <div style={{ height: 14, background: "#f3f4f6", borderRadius: 4, marginBottom: 28, width: "85%" }} />
      {[1, 2, 3, 4].map((i) => (
        <div key={i} style={{ height: 44, background: "#f3f4f6", borderRadius: 8, marginBottom: 14 }} />
      ))}
      <div style={{ height: 44, background: "#e0e7ff", borderRadius: 9 }} />
    </div>
  ),
});

export default function RegisterFormLoader() {
  return <RegisterForm />;
}
