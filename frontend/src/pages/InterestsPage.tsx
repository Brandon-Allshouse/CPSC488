import { useEffect, useState } from 'react';
import { fetchTopics, type Topic } from '../api/feed';
import { Check, ChevronRight, CircleAlert, Sparkles } from 'lucide-react';
import AuthLayout from '@/components/layout/AuthLayout';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import LanguageSelect from '@/components/LanguageSelect';
import TopicFinder from '@/components/TopicFinder';

interface Props {
  /** Topic ids that start out selected. */
  initial: number[];
  /** Saves the picks. Throws if saving fails; the error message is shown on the page. */
  onSave: (topicIds: number[]) => Promise<void>;
  /** Shown as a Cancel button when the user already has interests and is just editing them. */
  onCancel?: () => void;
  isGuest: boolean;
  /** A problem to point out before the user picks, e.g. their saved interests didn't load. */
  notice?: string;
}

/**
 * The interests picker: subjects and sub-subjects from /api/topics, "Select all", and the topic
 * box (TopicFinder), which narrows the list as you type and adds a topic on Enter. Shown on
 * first login and from "Edit interests".
 */
export default function InterestsPage({ initial, onSave, onCancel, isGuest, notice }: Props) {
  const [topics, setTopics] = useState<Topic[] | null>(null);
  const [selected, setSelected] = useState<Set<number>>(() => new Set(initial));
  // Sections that start open: ones with an earlier pick, plus the section of any topic added from
  // the topic box, so the user can see it there. Only ever grows, so sections the user opens or closes stay
  // that way while they pick.
  const [startOpen, setStartOpen] = useState(() => new Set(initial));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [query, setQuery] = useState('');

  useEffect(() => {
    fetchTopics()
      .then(setTopics)
      .catch(() => setError('Could not load topics. Please refresh the page.'));
  }, []);

  function pick(id: number) {
    setSelected((current) => new Set(current).add(id));
    setStartOpen((current) => new Set(current).add(id));
  }

  function unpick(id: number) {
    setSelected((current) => {
      const next = new Set(current);
      next.delete(id);
      return next;
    });
  }

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

  // A topic that's new to this page, from the topic box: put it in its subject and pick it.
  function addTopic(added: Topic) {
    setTopics((current) =>
      current?.some((topic) => topic.id === added.id) ? current : [...(current ?? []), added],
    );
    pick(added.id);
  }

  // Picking a subject already includes all of its sub-subjects (see VideoRepository.page), so
  // "Select all" only needs the subjects. It replaces any sub-subject picks, which are covered anyway.
  const subjectIds = topics ? topics.filter((topic) => topic.parentId === null).map((topic) => topic.id) : [];
  const allSelected = subjectIds.length > 0 && subjectIds.every((id) => selected.has(id));

  function toggleAll() {
    setSelected(allSelected ? new Set() : new Set(subjectIds));
  }

  // A subject matches if its name or any of its sub-subjects' names contain the search text.
  const search = query.trim().toLowerCase();
  function matches(subject: Topic, subs: Topic[]) {
    return !search || [subject, ...subs].some((topic) => topic.name.toLowerCase().includes(search));
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
    <AuthLayout
      name="interests"
      title="What do you want to learn about?"
      className="max-w-2xl"
      description={
        <>
          Open a subject and pick all of it, or just the parts you want, like Algebra 2. Pick as many as
          you like. You can change them any time
          {isGuest ? ', but as a guest they reset when you leave.' : '.'}
        </>
      }
    >
      {notice && (
        <Alert role="alert">
          <CircleAlert aria-hidden="true" />
          {notice}
        </Alert>
      )}

      {topics === null && !error && (
        <div className="flex flex-col gap-2" aria-hidden="true">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-12 animate-pulse rounded-lg bg-muted" />
          ))}
        </div>
      )}
      {topics === null && !error && <p className="text-sm text-muted-foreground">Loading topics…</p>}

      {topics && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <LanguageSelect />
          <Button variant="outline" size="sm" onClick={toggleAll}>
            {allSelected ? 'Clear all' : 'Select all subjects'}
          </Button>
        </div>
      )}

      {topics && (
        <TopicFinder
          topics={topics}
          selected={selected}
          onPick={pick}
          onUnpick={unpick}
          onAdded={addTopic}
          onQueryChange={setQuery}
          isGuest={isGuest}
        />
      )}

      {topics && search && !topics.some((topic) => topic.name.toLowerCase().includes(search)) && (
        <p className="no-match py-4 text-center text-sm text-muted-foreground">
          {isGuest
            ? `We don't have “${query.trim()}” yet. Log in to add it, or try a shorter word.`
            : `We don't have “${query.trim()}” yet. Press Enter to add it.`}
        </p>
      )}

      {topics && (
        <div id="subject-list" className="flex max-h-[55dvh] flex-col gap-2 overflow-y-auto pr-1">
          {topics
            .filter((topic) => topic.parentId === null)
            .map((subject) => {
              const subs = topics.filter((topic) => topic.parentId === subject.id);
              const picked = [subject, ...subs].filter((topic) => selected.has(topic.id)).length;
              // Sections start open if something in them was already picked.
              if (!matches(subject, subs)) return null;
              // While searching, matching subjects open so the matching topic is visible.
              const open = Boolean(search) || [subject, ...subs].some((topic) => startOpen.has(topic.id));
              return (
                <details
                  key={subject.id}
                  className="subject group rounded-lg border bg-background/40 transition-colors duration-150 open:bg-background/70"
                  open={open}
                >
                  <summary className="flex cursor-pointer items-center gap-2 px-4 py-3 font-medium select-none">
                    <ChevronRight
                      aria-hidden="true"
                      className="size-4 shrink-0 text-muted-foreground transition-transform duration-200 group-open:rotate-90"
                    />
                    {subject.name}
                    {picked > 0 && (
                      <>
                        {/* Read out as "Math · 2 picked"; the dot is hidden on screen. */}
                        <span className="sr-only"> · </span>
                        <span className="subject-count ml-auto rounded-full bg-primary/15 px-2 py-0.5 text-xs font-semibold text-primary">
                          {picked} picked
                        </span>
                      </>
                    )}
                  </summary>
                  <div className="topic-grid flex flex-wrap gap-2 px-4 pb-4">
                    {[subject, ...subs].map((topic) => {
                      const isPicked = selected.has(topic.id);
                      return (
                        <button
                          key={topic.id}
                          type="button"
                          aria-pressed={isPicked}
                          onClick={() => toggle(topic.id)}
                          className={cn(
                            'topic-chip inline-flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm transition-all duration-150 hover:border-primary/60 active:scale-95',
                            isPicked && 'border-primary bg-primary/15 text-primary',
                          )}
                        >
                          {isPicked && <Check aria-hidden="true" className="size-3.5" />}
                          {topic === subject ? `All of ${subject.name}` : topic.name}
                          {topic.ai && (
                            <>
                              <Sparkles aria-hidden="true" className="size-3 text-muted-foreground" />
                              <span className="sr-only">, added by a user</span>
                            </>
                          )}
                        </button>
                      );
                    })}
                  </div>
                </details>
              );
            })}
        </div>
      )}

      {error && (
        <Alert variant="destructive" role="alert">
          <CircleAlert aria-hidden="true" />
          {error}
        </Alert>
      )}

      <div className="flex flex-col gap-2 sm:flex-row-reverse">
        <Button
          size="lg"
          className="sm:flex-1"
          disabled={selected.size === 0 || saving || topics === null}
          onClick={handleSave}
        >
          {saving ? 'Saving…' : selected.size === 0 ? 'Pick at least one topic' : 'Show my feed'}
        </Button>
        {onCancel && (
          <Button variant="outline" size="lg" onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </AuthLayout>
  );
}
