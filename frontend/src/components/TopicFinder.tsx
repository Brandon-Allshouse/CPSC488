import { type FormEvent, useState } from 'react';
import { Check, CircleAlert, Loader2, Plus, Sparkles } from 'lucide-react';
import type { Topic } from '../api/feed';
import { addSuggestedTopic, suggestTopics, type TopicIdea, type TopicSuggestions } from '../api/topics';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

// Same limit as TopicPrompt.MAX_REQUEST on the backend.
export const MAX_REQUEST = 200;

// Shown as buttons that fill the box, so people see what kind of thing to type.
export const EXAMPLES = ['black holes', 'guitar chords, music theory', 'like the Roman Empire and the Cold War'];

interface Props {
  /** Topic ids picked so far on the interests page. */
  selected: Set<number>;
  /** Picks or unpicks a catalog topic. */
  onToggle: (id: number) => void;
  /** A suggested topic was added: put these in the list and pick the last one. */
  onAdded: (topics: Topic[]) => void;
}

/**
 * "Find topics with AI" on the interests page: type a few keywords or examples, and the backend's
 * LLM finds matching topics and proposes new ones. Signed-in users only (the page hides it for
 * guests). Everything shown came from the LLM, so it's rendered as plain text.
 */
export default function TopicFinder({ selected, onToggle, onAdded }: Props) {
  const [text, setText] = useState('');
  const [result, setResult] = useState<TopicSuggestions | null>(null);
  const [searching, setSearching] = useState(false);
  const [adding, setAdding] = useState<string | null>(null);
  const [added, setAdded] = useState<Set<string>>(() => new Set());
  const [error, setError] = useState<string | null>(null);

  async function find(e?: FormEvent) {
    e?.preventDefault();
    if (!text.trim()) return;
    setError(null);
    setSearching(true);
    try {
      setResult(await suggestTopics(text.trim()));
      setAdded(new Set());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setSearching(false);
    }
  }

  async function add(idea: TopicIdea) {
    setError(null);
    setAdding(idea.key);
    try {
      onAdded(await addSuggestedTopic(idea.key));
      setAdded((current) => new Set(current).add(idea.key));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setAdding(null);
    }
  }

  const nothingFound = result && result.matches.length === 0 && result.ideas.length === 0;

  return (
    <section className="topic-finder flex flex-col gap-3 rounded-lg border bg-background/40 p-4" aria-labelledby="topic-finder-title">
      <h2 id="topic-finder-title" className="flex items-center gap-2 text-base font-semibold">
        <Sparkles aria-hidden="true" className="size-4 text-primary" />
        Find topics with AI
      </h2>
      <p className="text-sm text-muted-foreground">
        Type a few keywords, or examples of things you like. If we don't have a topic yet, you can add it.
      </p>
      <form className="flex flex-col gap-2 sm:flex-row" onSubmit={find}>
        <Input
          id="topic-finder-text"
          aria-label="What do you want to learn?"
          placeholder="e.g. black holes, baking bread"
          maxLength={MAX_REQUEST}
          value={text}
          onChange={(e) => setText(e.target.value)}
          disabled={searching}
        />
        <Button type="submit" disabled={searching || !text.trim()}>
          {searching ? <Loader2 aria-hidden="true" className="animate-spin" /> : <Sparkles aria-hidden="true" />}
          {searching ? 'Looking…' : 'Find topics'}
        </Button>
      </form>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span>Try:</span>
        {EXAMPLES.map((example) => (
          <button
            key={example}
            type="button"
            className="topic-example cursor-pointer rounded-full border px-2.5 py-1 transition-colors duration-150 hover:border-primary/60 hover:text-foreground"
            onClick={() => setText(example)}
            disabled={searching}
          >
            {example}
          </button>
        ))}
      </div>

      {searching && (
        <p className="text-sm text-muted-foreground" role="status">
          Looking for topics. This takes a few seconds.
        </p>
      )}

      {error && (
        <Alert variant="destructive" role="alert">
          <CircleAlert aria-hidden="true" />
          {error}
        </Alert>
      )}

      {nothingFound && !searching && (
        <p className="text-sm text-muted-foreground" role="status">
          No topics for that. Try different words, or something people learn about.
        </p>
      )}

      {result && result.matches.length > 0 && (
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">Already on BrainFeed</h3>
          <div className="flex flex-wrap gap-2">
            {result.matches.map((topic) => {
              const isPicked = selected.has(topic.id);
              return (
                <button
                  key={topic.id}
                  type="button"
                  aria-pressed={isPicked}
                  onClick={() => onToggle(topic.id)}
                  className={cn(
                    'topic-chip inline-flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm transition-all duration-150 hover:border-primary/60 active:scale-95',
                    isPicked && 'border-primary bg-primary/15 text-primary',
                  )}
                >
                  {isPicked && <Check aria-hidden="true" className="size-3.5" />}
                  {topic.name}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {result && result.ideas.length > 0 && (
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">New topics you can add</h3>
          <ul className="flex flex-col gap-2">
            {result.ideas.map((idea) => {
              const done = added.has(idea.key);
              return (
                <li key={idea.key} className="topic-idea flex items-center justify-between gap-3 rounded-md border px-3 py-2 text-sm">
                  <span className="min-w-0">
                    <span className="font-medium">{idea.name}</span>
                    <span className="text-muted-foreground">
                      {' '}in {idea.subject}
                      {idea.newSubject && ' (new subject)'}
                    </span>
                  </span>
                  <Button
                    size="sm"
                    variant={done ? 'outline' : 'default'}
                    disabled={done || adding !== null}
                    aria-label={done ? `${idea.name} added` : `Add ${idea.name}`}
                    onClick={() => void add(idea)}
                  >
                    {done ? <Check aria-hidden="true" /> : adding === idea.key ? <Loader2 aria-hidden="true" className="animate-spin" /> : <Plus aria-hidden="true" />}
                    {done ? 'Added' : 'Add'}
                  </Button>
                </li>
              );
            })}
          </ul>
          <p className="text-xs text-muted-foreground">
            Added topics are picked for you and shared with everyone. Their videos show up in your feed in a few seconds.
          </p>
        </div>
      )}
    </section>
  );
}
