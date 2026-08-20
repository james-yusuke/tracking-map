import type { Metadata } from "next";
import { headers } from "next/headers";
import "./globals.css";

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host = requestHeaders.get("x-forwarded-host") || requestHeaders.get("host") || "localhost:3000";
  const protocol = requestHeaders.get("x-forwarded-proto") || (host.startsWith("localhost") ? "http" : "https");
  const base = new URL(`${protocol}://${host}`);
  const title = "Family Orbit | 家族の見守りダッシュボード";
  const description = "家族の現在地、移動履歴、安全エリアの状態を安心して確認できる閲覧専用ダッシュボード。";
  return {
    metadataBase: base,
    title,
    description,
    robots: { index: false, follow: false },
    openGraph: { title, description, type: "website", locale: "ja_JP", images: [{ url: "/og.png", width: 1729, height: 910, alt: "Family Orbit — いつもの一日に、そっと安心を。" }] },
    twitter: { card: "summary_large_image", title, description, images: ["/og.png"] },
  };
}

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="ja"
      className="h-full antialiased"
    >
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
