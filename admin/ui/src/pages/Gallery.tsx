import { Download, Globe, Plus, Smartphone, Trash2, User } from "lucide-react";
import {
  Banner,
  Button,
  Card,
  CardHead,
  Cell,
  Chip,
  KeyValueRows,
  Notice,
  PageHeader,
  SearchField,
  StatTile,
  StatusPill,
  TableHead,
  TableRow,
} from "../components/ui";

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="gallery__row">
      <span className="gallery__label">{label}</span>
      {children}
    </div>
  );
}

/** Every component beside the values its frame in design/admin.pen uses (task C0.3). */
export function Gallery() {
  return (
    <>
      <PageHeader title="Components" meta="design/admin.pen · Components frame · both themes follow the system">
        <Button icon={Download}>Export CSV</Button>
      </PageHeader>
      <div className="gallery">
        <Row label="StatTile">
          <div style={{ width: 260 }}>
            <StatTile label="Registered accounts" value="1,284" sub="+12 this week" />
          </div>
        </Row>
        <Row label="StatusPill">
          <StatusPill tone="ok">Healthy</StatusPill>
          <StatusPill tone="warn">Degraded</StatusPill>
          <StatusPill tone="danger">Down</StatusPill>
          <StatusPill tone="neutral">Configured</StatusPill>
        </Row>
        <Row label="Chip">
          <Chip>per IP</Chip>
          <Chip tone="accent">per account</Chip>
          <Chip tone="ok">Current</Chip>
          <Chip tone="warn">Update offered</Chip>
          <Chip tone="danger">Update required</Chip>
          <Chip mono>/health/ready</Chip>
        </Row>
        <Row label="Button">
          <Button variant="primary" icon={Plus}>
            Button · Primary
          </Button>
          <Button icon={Plus}>Button · Secondary</Button>
          <Button variant="danger" icon={Trash2}>
            Button · Danger
          </Button>
          <Button disabled>Disabled</Button>
        </Row>
        <Row label="SearchField">
          <SearchField placeholder="Search by account ID" />
        </Row>
        <Row label="Notice">
          <Notice>Read-only. To change a value, update the deployment's environment and restart the server.</Notice>
        </Row>
        <Row label="Banner">
          <div style={{ width: 358 }}>
            <Banner title="No minimum client version set" body="Older builds still accept forgeable messages · see Client versions" />
          </div>
        </Row>
        <Row label="Card + table">
          <div style={{ width: 760 }}>
            <Card>
              <CardHead title="Devices" sub="3 active · 1 removed">
                <StatusPill tone="ok">Ready</StatusPill>
              </CardHead>
              <TableHead>
                <Cell>Account</Cell>
                <Cell width={130}>Created</Cell>
                <Cell width={90}>Devices</Cell>
                <Cell width={150}>Push</Cell>
                <Cell width={120}>Status</Cell>
              </TableHead>
              <TableRow link>
                <Cell>
                  <span className="avatar">
                    <User aria-hidden="true" />
                  </span>
                  <span className="mono text-primary">7f3c9a1e</span>
                </Cell>
                <Cell width={130}>12 Mar 2026</Cell>
                <Cell width={90} className="mono text-primary">
                  3
                </Cell>
                <Cell width={150}>
                  <Smartphone size={15} aria-label="iOS" />
                  <Globe size={15} aria-label="Web" />
                </Cell>
                <Cell width={120}>
                  <StatusPill tone="ok">Active</StatusPill>
                </Cell>
              </TableRow>
            </Card>
          </div>
        </Row>
        <Row label="Key-value rows">
          <div style={{ width: 380 }}>
            <Card>
              <CardHead title="Since the last restart" sub="From /operator/metrics" />
              <KeyValueRows
                rows={[
                  { key: "HTTP requests", value: <span className="mono text-primary">4,812,330</span> },
                  { key: "HTTP errors", value: <span className="mono text-primary">1,206 · 0.03 %</span> },
                ]}
              />
            </Card>
          </div>
        </Row>
      </div>
    </>
  );
}
