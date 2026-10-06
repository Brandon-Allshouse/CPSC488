import { useEffect, useState } from 'react';
import { fetchTopics, type Topic } from '../api/feed';
import brainLogo from '../assets/brainfeed-logo.png';

interface Props {
  /** Topic ids that start out selected. */
  initial: number[];
  /** Saves the picks. Throws if saving fails; the error message is shown on the page. */
  onSave: (topicIds: number[]) => Promise<void>;
  /** Shown as a Cancel button when the user already has interests and is just editing them. */
  onCancel?: () => void;
  isGuest: boolean;
}

export default function InterestsPage({ initial, onSave, onCancel, isGuest }: Props) {
  const [topics, setTopics] = useState<Topic[] | null>(null);
  const [selected, setSelected] = useState<Set<number>>(() => new Set(initial));
  // Only decided once, so sections the user opens or closes stay that way while they pick.
  const [startOpen] = useState(() => new Set(initial));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    fetchTopics()
      .then(setTopics)
      .catch(() => setError('Could not load topics. Please refresh the page.'));
  }, []);

  function toggle(id: number) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  async function handleSave() {
    setError(null);
    setSaving(true);
    try {
      await onSave([...selected]);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
      setSaving(false);
    }
  }

  return (
    <main className="center-screen">
      <img className="auth-logo" src={brainLogo} alt="BrainFeed brain logo" />
      <section className="auth-card interests-card" aria-labelledby="interests-title">
        <header className="auth-header">
          <p className="brand">BrainFeed</p>
          <h1 id="interests-title">What do you want to learn about?</h1>
          <p className="muted">
            Open a subject and pick all of it, or just the parts you want, like Algebra 2. Pick as many
            as you like. You can change them any time
            {isGuest ? ', but as a guest they reset when you leave.' : '.'}
          </p>
        </header>

        {topics === null && !error && <p className="muted">Loading topics…</p>}

        {topics && (
          <div className="subject-list">
            {topics
              .filter((topic) => topic.parentId === null)
              .map((subject) => {
                const subs = topics.filter((topic) => topic.parentId === subject.id);
                const picked = [subject, ...subs].filter((topic) => selected.has(topic.id)).length;
                // Sections start open if something in them was already picked.
                const open = [subject, ...subs].some((topic) => startOpen.has(topic.id));
                return (
                  <details key={subject.id} className="subject" open={open}>
                    <summary>
                      {subject.name}
                      {picked > 0 && <span className="subject-count"> · {picked} picked</span>}
                    </summary>
                    <div className="topic-grid">
                      {[subject, ...subs].map((topic) => (
                        <button
                          key={topic.id}
                          type="button"
                          className="topic-chip"
                          aria-pressed={selected.has(topic.id)}
                          onClick={() => toggle(topic.id)}
                        >
                          {topic === subject ? `All of ${subject.name}` : topic.name}
                        </button>
                      ))}
                    </div>
                  </details>
                );
              })}
          </div>
        )}

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <button
          type="button"
          className="btn btn-primary"
          disabled={selected.size === 0 || saving || topics === null}
          onClick={handleSave}
        >
          {saving ? 'Saving…' : selected.size === 0 ? 'Pick at least one topic' : 'Show my feed'}
        </button>
        {onCancel && (
          <button type="button" className="btn btn-secondary" onClick={onCancel}>
            Cancel
          </button>
        )}
      </section>
    </main>
  );
}
