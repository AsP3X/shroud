// The JSON shapes of docs/admin-plan.md §3, as the fixtures in admin/api/fixtures show them.
// Dates: RFC 3339 for `*_at`, `YYYY-MM-DD` for `*_on`.

export type Role = "read" | "write";

export interface Session {
  operator: { id: string; name: string; role: Role };
  expires_at: string;
  reauth_until: string | null;
}

export interface Reauth {
  reauth_until: string;
}

export interface Setup {
  operator_name: string;
  totp_uri: string;
  qr_svg: string;
}

export interface SetupEnrolled {
  recovery_codes: string[];
}

export interface SetupUrl {
  setup_url: string;
}

export type ReadyState = "ok" | "error";

export interface Overview {
  server: { version: string; started_at: string; checked_at: string };
  stats: {
    accounts: number;
    accounts_7d: number;
    accounts_deleted: number;
    devices_active_30d: number;
    ws_connections: number;
    messages_sent_total: number;
  };
  ready: {
    status: "ok" | "not_ready";
    database: ReadyState;
    redis: ReadyState | "skipped";
    media: ReadyState;
  };
  configured: {
    apns: { environment: "production" | "sandbox"; topic: string } | null;
    web_push: { subscriptions: number } | null;
    unifiedpush: { allowed_hosts: string[]; public_hosts: boolean } | null;
    turn: { urls: string[]; credential_ttl_secs: number } | null;
    link_relay: { max_per_account: number };
  };
  metrics: {
    http_requests_total: number;
    http_errors_total: number;
    media_puts_total: number;
    media_gets_total: number;
    media_store_errors_total: number;
    media_legacy_reads_total: number;
    media_migrated_total: number;
    calls_created_total: number;
  };
  attention: { kind: "no_min_version" | "legacy_media" | "not_ready"; count?: number }[];
}

export type PushKind = "apns" | "web" | "unifiedpush" | "none" | "mixed";
export type AccountStatus = "active" | "deleted";

export interface UserRow {
  id: string;
  created_on: string;
  devices: number;
  last_active_on: string | null;
  push: PushKind;
  status: AccountStatus;
}

export interface Page<T> {
  items: T[];
  next_cursor: string | null;
}

/** `GET /users`: the page plus the totals the header and the status filter show (§3.9 #3). */
export interface UsersPage extends Page<UserRow> {
  totals: { accounts: number; active: number; deleted: number };
}

export type Platform = "ios" | "web" | "android" | "unknown";
export type DevicePush = "apns" | "apns+voip" | "web" | "unifiedpush" | "none";

export interface Device {
  id: string;
  platform: Platform;
  added_on: string;
  last_seen_on: string | null;
  revoked: boolean;
  push: DevicePush;
  session: "live" | "none";
}

export interface UserDetail {
  id: string;
  created_on: string;
  status: AccountStatus;
  last_active_on: string | null;
  counts: {
    contacts: number | null;
    blocks: number | null;
    conversations: number | null;
    media_objects: number;
    media_bytes: number;
  };
  devices: Device[];
  pin_guard: boolean;
}

export interface Storage {
  backend: "nebular" | "local";
  bucket: string | null;
  data_dir: string | null;
  objects: number;
  bytes: number;
  unlinked_objects: number;
  legacy_reads_total: number;
  migrated_total: number;
  max_object_bytes: number;
}

export type UpdateStatus = "current" | "update_available" | "update_required";

export interface AppRelease {
  latest: string | null;
  minimum: string | null;
  update_url: string | null;
}

export interface ClientVersions {
  server_version: string;
  ios: AppRelease;
  android: AppRelease;
  web: { build_file: string | null; deployed_build: string | null; fixed_build: string | null };
  told: Record<"ios" | "android" | "web", { versions: string; status: UpdateStatus }[]>;
}

export interface RateLimit {
  what: string;
  counted: "ip" | "account" | "username" | "device";
  limit: number;
  window_secs: number;
  note: string | null;
}

export interface Retention {
  automatic: { record: string; after_secs: number; every_secs: number; stays: string }[];
  kept: { record: string; goes_when: string }[];
  jobs: { name: string; every_secs: number | null; detail: string }[];
}

export interface Push {
  apns_tokens: number;
  apns_voip_tokens: number;
  web_push_subscriptions: number;
  unifiedpush_subscriptions: number;
  notifications_off: number;
  channels: { name: string; registered: number; sent_to: string; dropped_when: string }[];
}

/** One line of `GET /push/check` (§3.9 #10). */
export interface PushCheck {
  item: string;
  state: "ok" | "failed" | "off";
  detail: string;
}

export interface Calls {
  created_total: number;
  ice_servers: { urls: string }[];
  turn: { urls: string[]; credential_ttl_secs: number } | null;
  gc: { ringing_timeout_secs: number; participant_timeout_secs: number; sweep_secs: number };
}

export interface PrivacyCheck {
  item: string;
  state: "sealed" | "hashed" | "not_stored" | "stored";
  detail: string;
}

export interface ConfigurationGroup {
  group: string;
  variables: { name: string; value: string | null; secret: boolean; set: boolean }[];
}

export interface AuditEntry {
  at: string;
  operator: string;
  action: string;
  target_kind: string | null;
  target_id: string | null;
  outcome: "ok" | "refused" | "failed";
  detail: string | null;
}

export interface Operator {
  id: string;
  name: string;
  role: Role;
  enabled: boolean;
  last_sign_in_at: string | null;
  totp_enrolled: boolean;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  upstream?: "postgres" | "api";
}
