import type { ReactNode } from "react";
import ActionFeedback from "../components/ActionFeedback";
import DialogAccessibility from "../components/DialogAccessibility";
import "./globals.css";
import "./institutional.css";
import "./layout-fixes.css";
import "./enhancements.css";

export const metadata = {
  title: "NHRC eGMS",
  description: "NHRC institutional grants management platform"
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body><DialogAccessibility/><ActionFeedback/>{children}</body>
    </html>
  );
}
