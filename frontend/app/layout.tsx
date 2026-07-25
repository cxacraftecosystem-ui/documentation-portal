import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";

import { AdminViewProvider } from "@/components/AdminViewProvider";
import { AuthProvider } from "@/components/AuthProvider";
import { ThemeProvider } from "@/components/ThemeProvider";
import { ToastProvider } from "@/components/ui/Toast";
import { PREFERENCES_BOOT_SCRIPT, THEME_COLOR } from "@/lib/preferences";
import "./globals.css";

const inter = localFont({
  src: "../fonts/inter-latin-var.woff2",
  display: "swap",
  variable: "--font-inter",
  weight: "100 900"
});

const jakarta = localFont({
  src: "../fonts/jakarta-latin-var.woff2",
  display: "swap",
  variable: "--font-jakarta",
  weight: "200 800"
});

export const metadata: Metadata = {
  title: {
    default: "Field Repository",
    template: "%s · Field Repository"
  },
  description:
    "Field documentation repository for artisans, crafts, workshops, products, tools, processes, questionnaires, media and transcripts."
};

// Address-bar colour follows the theme. These two are the OS-driven fallback for the pre-JavaScript
// paint; ThemeProvider rewrites them to the resolved theme once a user has made an explicit choice.
export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: THEME_COLOR.light },
    { media: "(prefers-color-scheme: dark)", color: THEME_COLOR.dark }
  ]
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    // suppressHydrationWarning: the boot script below writes data-theme (and the accessibility
    // flags) onto <html> before React hydrates, so the attributes deliberately differ from the
    // server-rendered markup.
    <html className={`${inter.variable} ${jakarta.variable}`} lang="en" suppressHydrationWarning>
      <body className="font-sans">
        {/* First child of <body>, and blocking: it must run before any content paints, otherwise
            every load flashes the light theme before ThemeProvider mounts. */}
        <script dangerouslySetInnerHTML={{ __html: PREFERENCES_BOOT_SCRIPT }} />
        <AuthProvider>
          <ThemeProvider>
            {/* One toast queue and one live region for the whole app. It used to be mounted per
                page because this layout hosted no client providers; it hosts three now, and a
                per-page provider is a footgun — useToast() throws on any page that forgets it. */}
            <ToastProvider>
              <AdminViewProvider>{children}</AdminViewProvider>
            </ToastProvider>
          </ThemeProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
