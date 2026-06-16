import type { Metadata } from "next";

import { AdminViewProvider } from "@/components/AdminViewProvider";
import { AuthProvider } from "@/components/AuthProvider";
import "./globals.css";

export const metadata: Metadata = {
  title: "Field Documentation Repository",
  description: "API-first repository for field documentation, media, reviews and exports."
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <AuthProvider>
          <AdminViewProvider>{children}</AdminViewProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
