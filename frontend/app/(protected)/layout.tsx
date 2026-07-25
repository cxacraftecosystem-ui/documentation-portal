import { AppShell } from "@/components/AppShell";

/**
 * Every protected page renders inside AppShell, which owns the two things that must apply to all of
 * them: the sign-in redirect, and the route guards declared in lib/permissions.ts (ROUTE_GUARDS).
 * Enforcing access here rather than page by page is why a direct URL to /users, /review, /data or a
 * create form cannot render content the API would refuse.
 */
export default function ProtectedLayout({ children }: { children: React.ReactNode }) {
  return <AppShell>{children}</AppShell>;
}
