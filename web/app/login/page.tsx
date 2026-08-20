"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowRight, Eye, EyeOff, LockKeyhole, Mail, ShieldCheck } from "lucide-react";
import { FormEvent, useState } from "react";
import { login } from "../lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      await login(email, password);
      router.replace("/");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "ログインできませんでした");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="login-shell">
      <section className="login-brand-panel">
        <Link className="brand brand-light" href="/"><span className="brand-mark"><span /></span><strong>Family Orbit</strong></Link>
        <div className="login-message"><small>FAMILY SAFETY, GENTLY DESIGNED</small><h1>いつもの一日に、<br />そっと安心を。</h1><p>大切な家族の「今」を、必要なときだけ確かめられる見守りサービスです。</p></div>
        <div className="orbit-visual" aria-hidden="true"><span className="orbit-ring ring-one" /><span className="orbit-ring ring-two" /><i className="orbit-dot dot-one" /><i className="orbit-dot dot-two" /></div>
        <div className="privacy-promise"><ShieldCheck size={20} /><span><strong>プライバシーを中心に設計</strong><small>位置共有は子ども側にも常に表示されます。</small></span></div>
      </section>
      <section className="login-form-panel">
        <form className="login-form" onSubmit={submit}>
          <div className="login-heading"><small>WELCOME BACK</small><h2>おかえりなさい</h2><p>保護者アカウントでログインしてください。</p></div>
          <label><span>メールアドレス</span><div className="input-wrap"><Mail size={18} /><input type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.jp" /></div></label>
          <label><span>パスワード</span><div className="input-wrap"><LockKeyhole size={18} /><input type={showPassword ? "text" : "password"} autoComplete="current-password" required minLength={12} value={password} onChange={(event) => setPassword(event.target.value)} placeholder="12文字以上" /><button type="button" aria-label={showPassword ? "パスワードを隠す" : "パスワードを表示"} onClick={() => setShowPassword((value) => !value)}>{showPassword ? <EyeOff size={17} /> : <Eye size={17} />}</button></div></label>
          <div className="form-meta"><label className="remember"><input type="checkbox" defaultChecked />この端末を記憶する</label><button type="button" className="text-button">パスワードを忘れた方</button></div>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="primary-button" disabled={submitting}>{submitting ? "確認しています…" : "ログイン"}<ArrowRight size={18} /></button>
          {process.env.NEXT_PUBLIC_DEMO_MODE !== "false" && <Link className="demo-link" href="/">ログインせずデモを見る</Link>}
          <p className="legal-copy">続行すると、利用規約とプライバシーポリシーに同意したものとみなされます。</p>
        </form>
      </section>
    </main>
  );
}

