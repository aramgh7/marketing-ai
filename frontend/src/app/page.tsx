import { redirect } from "next/navigation";

/** Roadmap 9-5: the root goes to the dashboard; the admin AuthGuard bounces to /login without a session. */
export default function Home() {
  redirect("/dashboard");
}
