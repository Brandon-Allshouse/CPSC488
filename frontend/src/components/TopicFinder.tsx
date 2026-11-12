import { type FormEvent, type KeyboardEvent, useId, useMemo, useState } from 'react';
import { Check, CircleAlert, Loader2, Plus } from 'lucide-react';
import type { Topic } from '../api/feed';
import { addTopic } from '../api/topics';
import { cleanTopicName, findSameTopic, MAX_LENGTH, suggestTopics, topicNameProblem } from '../lib/topicMatch';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

interface Props {
  /** Every topic loaded on the interests page. Suggestions come from here, not from a request. */
  topics: Topic[];
  /** Topic ids picked so far. */
  selected: Set<number>;
  /** Picks a topic (never unpicks it). */
  onPick: (id: number) => void;
  /** Unpicks a topic, for "Add Generics instead" after we picked Genetics. */
  onUnpick: (id: number) => void;
  /** A topic that's new to this page (just added, or added by someone else since it loaded). */
  onAdded: (topic: Topic) => void;
  /** The text in the box, so the page can narrow its subject list to match. */
  onQueryChange?: (text: string) => void;
  /** Guests can pick topics we have, but adding new ones needs a login. */
  isGuest: boolean;
}

/** The last thing the box did, shown under it. */
interface Feedback {
  message: string;
  /** A close match was picked for what they typed: offer to add their own spelling. */
  instead?: { typed: string; pickedId: number; pickedNow: boolean };
}

/**
 * The topic box on the interests page: type one to three words and press Enter to follow that
 * topic. Topics we have are picked right away, in the browser. New ones are added by the
 * backend in well under a second (it checks the name, then an AI reviews it later). While
 * typing, matching topics are suggested from the list already loaded, with no request per
 * keystroke. Arrow keys move through the suggestions.
 */
export default function TopicFinder({ topics, selected, onPick, onUnpick, onAdded, onQueryChange, isGuest }: Props) {
  const [text, setText] = useState('');
  const [active, setActive] = useState(-1);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const listId = useId();

  const suggestions = useMemo(() => suggestTopics(text, topics), [text, topics]);
  const byId = useMemo(() => new Map(topics.map((topic) => [topic.id, topic])), [topics]);

  function change(value: string) {
    setText(value);
    setActive(-1);
    setError(null);
    onQueryChange?.(value);
  }

  function clear() {
    change('');
  }

  // "in Space", so people can tell Physics' Mechanics from another one
  function where(topic: Topic) {
    const subject = topic.parentId === null ? undefined : byId.get(topic.parentId);
    return subject ? ` in ${subject.name}` : '';
  }

  /** Picks a topic we already have and says what happened. */
  function pickExisting(topic: Topic, instead?: { typed: string }) {
    const already = selected.has(topic.id);
    if (!already) onPick(topic.id);
    setFeedback({
      message: already ? `You already follow ${topic.name}.` : `Added ${topic.name} to your topics.`,
      instead: instead && { typed: instead.typed, pickedId: topic.id, pickedNow: !already },
    });
    clear();
  }

  async function submit(typed: string, asTyped = false) {
    setError(null);
    setFeedback(null);
    const problem = topicNameProblem(typed);
    if (problem) {
      setError(problem);
      return;
    }
    const name = cleanTopicName(typed);
    const same = asTyped ? undefined : findSameTopic(name, topics);
    if (same) {
      pickExisting(same);
      return;
    }
    if (isGuest) {
      setError(`We don't have “${name}” yet. Log in to add new topics.`);
      return;
    }
    setBusy(true);
    try {
      const result = await addTopic(name, asTyped);
      if (!byId.has(result.topic.id)) {
        // new to this page: the page puts it in its subject and picks it
        onAdded(result.topic);
        setFeedback({
          message: `Added ${result.topic.name} to your topics${where(result.topic)}.${result.created ? ' Finding its videos now.' : ''}`,
        });
        clear();
      } else {
        pickExisting(result.topic, result.similar ? { typed: name } : undefined);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  // "No, I meant Generics": undo the close match (if we picked it just now) and add theirs.
  function addInstead(instead: NonNullable<Feedback['instead']>) {
    if (instead.pickedNow) onUnpick(instead.pickedId);
    void submit(instead.typed, true);
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    if (active >= 0 && suggestions[active]) {
      pickExisting(suggestions[active]);
    } else {
      void submit(text);
    }
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (suggestions.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => (i + 1) % suggestions.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => (i <= 0 ? suggestions.length - 1 : i - 1));
    } else if (e.key === 'Escape') {
      setActive(-1);
    }
  }

  const showList = text.trim() !== '' && suggestions.length > 0;
  const optionId = (index: number) => `${listId}-option-${index}`;

  return (
    <section className="topic-finder flex flex-col gap-2" aria-label="Add a topic">
      <form className="flex flex-col gap-2 sm:flex-row" onSubmit={onSubmit}>
        <div className="relative flex-1">
          <Input
            id="topic-finder-text"
            role="combobox"
            aria-label="Type a topic"
            aria-autocomplete="list"
            aria-expanded={showList}
            aria-controls={listId}
            aria-activedescendant={active >= 0 && showList ? optionId(active) : undefined}
            autoComplete="off"
            placeholder="Type a topic, like black holes or guitar"
            maxLength={MAX_LENGTH * 2}
            value={text}
            onChange={(e) => change(e.target.value)}
            onKeyDown={onKeyDown}
          />
        </div>
        <Button type="submit" disabled={busy || !text.trim()}>
          {busy ? <Loader2 aria-hidden="true" className="animate-spin" /> : <Plus aria-hidden="true" />}
          {busy ? 'Adding…' : 'Add'}
        </Button>
      </form>

      {showList && (
        <ul id={listId} role="listbox" aria-label="Topics we have" className="topic-suggestions flex flex-wrap gap-2">
          {suggestions.map((topic, index) => {
            const isPicked = selected.has(topic.id);
            return (
              <li
                key={topic.id}
                id={optionId(index)}
                role="option"
                aria-selected={index === active}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => pickExisting(topic)}
                className={cn(
                  'topic-chip inline-flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm transition-all duration-150 hover:border-primary/60',
                  isPicked && 'border-primary bg-primary/15 text-primary',
                  index === active && 'ring-2 ring-primary/60',
                )}
              >
                {isPicked && <Check aria-hidden="true" className="size-3.5" />}
                {topic.name}
                <span className="text-muted-foreground">{where(topic)}</span>
              </li>
            );
          })}
        </ul>
      )}

      {!showList && !error && !feedback && (
        <p className="text-xs text-muted-foreground">
          {isGuest
            ? 'Press Enter to pick a topic. Log in to add ones we don’t have yet.'
            : 'Press Enter to follow it. If we don’t have it yet, it’s added for you.'}
        </p>
      )}

      {busy && (
        <p className="sr-only" role="status">
          Adding the topic…
        </p>
      )}

      {feedback && (
        <p className="topic-finder-feedback flex flex-wrap items-center gap-2 text-sm" role="status">
          <Check aria-hidden="true" className="size-4 text-primary" />
          {feedback.message}
          {feedback.instead && (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => addInstead(feedback.instead!)}>
              Add “{feedback.instead.typed}” instead
            </Button>
          )}
        </p>
      )}

      {error && (
        <Alert variant="destructive" role="alert">
          <CircleAlert aria-hidden="true" />
          {error}
        </Alert>
      )}
    </section>
  );
}
