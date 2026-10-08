import type { LucideIcon } from "lucide-react";
import {
  Bell,
  Gauge,
  HardDrive,
  KeyRound,
  LayoutDashboard,
  Phone,
  ScrollText,
  ShieldCheck,
  SlidersHorizontal,
  Smartphone,
  Timer,
  UserPlus,
  Users,
} from "lucide-react";

export interface NavEntry {
  to: string;
  label: string;
  icon: LucideIcon;
}

/** The sidebar of design/admin.pen, in its order. */
export const NAV: NavEntry[] = [
  { to: "/", label: "Overview", icon: LayoutDashboard },
  { to: "/privacy-checks", label: "Privacy checks", icon: ShieldCheck },
  { to: "/users", label: "Users", icon: Users },
  { to: "/sign-ups", label: "Sign-ups", icon: UserPlus },
  { to: "/operators", label: "Operators", icon: KeyRound },
  { to: "/storage", label: "Storage", icon: HardDrive },
  { to: "/retention", label: "Retention", icon: Timer },
  { to: "/push", label: "Push delivery", icon: Bell },
  { to: "/calls", label: "Calls", icon: Phone },
  { to: "/client-versions", label: "Client versions", icon: Smartphone },
  { to: "/configuration", label: "Configuration", icon: SlidersHorizontal },
  { to: "/rate-limits", label: "Rate limits", icon: Gauge },
  { to: "/audit-log", label: "Audit log", icon: ScrollText },
];
