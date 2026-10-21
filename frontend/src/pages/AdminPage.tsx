import { useState, type FormEvent } from 'react';
import brainLogo from '../assets/brainfeed-logo.png';

type Kind = 'user' | 'creator' | 'video';
type Tab = Kind | 'logs';

interface Entry {
  id: number;
  value: string;
  reason: string;
  addedAt: Date;
}

interface LogLine {
  id: number;
  at: Date;
  message: string;
}

const TABS: { key: Tab; label: string }[] = [
  { key: 'user', label: 'Users' },
  { key: 'creator', label: 'Creators' },
  { key: 'video', label: 'Videos' },
  { key: 'logs', label: 'Activity log' },
];

const KIND_TEXT: Record<Kind, { title: string; field: string; placeholder: string; noun: string }> = {
  user: {
    title: 'Banned users',
    field: 'Username',
    placeholder: 'e.g. spammer123',
    noun: 'user',
  },
  creator: {
    title: 'Blacklisted creators',
    field: 'YouTube channel name or ID',
    placeholder: 'e.g. UCxxxxxxxxxxxxxxxxxxxxxx',
    noun: 'creator',
  },
  video: {
    title: 'Blacklisted videos',
    field: 'YouTube video ID or link',
    placeholder: 'e.g. dQw4w9WgXcQ',
    noun: 'video',
  },
};

let nextId = 1;

interface Props {
  adminName: string;
  onBack: () => void;
}

export default function AdminPage({ adminName, onBack }: Props) {
  const [tab, setTab] = useState<Tab>('user');
  const [lists, setLists] = useState<Record<Kind, Entry[]>>({ user: [], creator: [], video: [] });
  const [logs, setLogs] = useState<LogLine[]>([]);

  function log(message: string) {
    setLogs((current) => [{ id: nextId++, at: new Date(), message: `${adminName} ${message}` }, ...current]);
  }

  function add(kind: Kind, value: string, reason: string) {
    setLists((current) => ({
      ...current,
      [kind]: [{ id: nextId++, value, reason, addedAt: new Date() }, ...current[kind]],
    }));
    const verb = kind === 'user' ? 'banned' : 'blacklisted';
    log(`${verb} ${KIND_TEXT[kind].noun} "${value}"${reason ? ` (reason: ${reason})` : ''}`);
  }

  function remove(kind: Kind, entry: Entry) {
    setLists((current) => ({ ...current, [kind]: current[kind].filter((e) => e.id !== entry.id) }));
    log(kind === 'user' ? `unbanned user "${entry.value}"` : `removed ${KIND_TEXT[kind].noun} "${entry.value}" from the blacklist`);
  }

  return (
    <div className="admin-page">
      <img className="feed-logo" src={brainLogo} alt="BrainFeed brain logo" />
      <header className="feed-header">
        <div className="feed-brand">
          <img className="feed-brand-logo" src={brainLogo} alt="" />
          <p className="brand">BrainFeed Admin</p>
        </div>
        <div className="feed-actions">
          <span className="muted feed-user">{adminName}</span>
          <button type="button" className="link-button" onClick={onBack}>
            Back to feed
          </button>
        </div>
      </header>

      <main className="admin-main">
        <p className="admin-notice" role="note">
          Preview: these tools aren't connected to the server yet. Changes only last until you leave this page.
        </p>

        <div className="library-tabs admin-tabs" role="tablist">
          {TABS.map(({ key, label }) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={tab === key}
              className="library-tab"
              onClick={() => setTab(key)}
            >
              {label}
              {key !== 'logs' && <span className="muted"> ({lists[key].length})</span>}
            </button>
          ))}
        </div>

        {tab === 'logs' ? (
          <section className="admin-card" aria-label="Activity log">
            <h2>Activity log</h2>
            {logs.length === 0 ? (
              <p className="muted">Admin actions will be listed here.</p>
            ) : (
              <ul className="admin-log">
                {logs.map((line) => (
                  <li key={line.id}>
                    <time className="muted" dateTime={line.at.toISOString()}>
                      {line.at.toLocaleTimeString()}
                    </time>{' '}
                    {line.message}
                  </li>
                ))}
              </ul>
            )}
          </section>
        ) : (
          <BlacklistSection
            key={tab}
            kind={tab}
            entries={lists[tab]}
            onAdd={(value, reason) => add(tab, value, reason)}
            onRemove={(entry) => remove(tab, entry)}
          />
        )}
      </main>
    </div>
  );
}

interface SectionProps {
  kind: Kind;
  entries: Entry[];
  onAdd: (value: string, reason: string) => void;
  onRemove: (entry: Entry) => void;
}

function BlacklistSection({ kind, entries, onAdd, onRemove }: SectionProps) {
  const text = KIND_TEXT[kind];
  const [value, setValue] = useState('');
  const [reason, setReason] = useState('');

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const trimmed = value.trim();
    if (!trimmed) return;
    onAdd(trimmed, reason.trim());
    setValue('');
    setReason('');
  }

  return (
    <section className="admin-card" aria-label={text.title}>
      <h2>{text.title}</h2>
      <form className="admin-form" onSubmit={handleSubmit}>
        <label className="field">
          {text.field}
          <input value={value} onChange={(e) => setValue(e.target.value)} placeholder={text.placeholder} maxLength={200} />
        </label>
        <label className="field">
          Reason (optional)
          <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} />
        </label>
        <button type="submit" className="btn btn-primary" disabled={!value.trim()}>
          {kind === 'user' ? 'Ban user' : 'Add to blacklist'}
        </button>
      </form>

      {entries.length === 0 ? (
        <p className="muted">Nothing blacklisted yet.</p>
      ) : (
        <ul className="admin-list">
          {entries.map((entry) => (
            <li key={entry.id}>
              <span>
                <strong>{entry.value}</strong>
                {entry.reason && <span className="muted"> · {entry.reason}</span>}
              </span>
              <button type="button" className="btn btn-secondary" onClick={() => onRemove(entry)}>
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
