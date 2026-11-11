import { useEffect, useRef, useState } from 'react';
import { CheckCircle2, Loader2, RotateCcw, Sparkles, XCircle } from 'lucide-react';
import { fetchVideoStudy, type StudyQuestion, type VideoStudy } from '../api/study';
import { cn } from '@/lib/utils';

// How often to ask whether the summary is done, and when to stop asking. A CPU usually takes
// 10-60 seconds, more when the model has to load first.
export const STUDY_POLL_MS = 3000;
export const STUDY_GIVE_UP_MS = 3 * 60 * 1000;

type State =
  | { kind: 'idle' }
  | { kind: 'working' }
  | { kind: 'ready'; summary: string; questions: StudyQuestion[] }
  | { kind: 'busy' | 'unavailable' | 'slow' | 'error' };

const MESSAGES: Record<'busy' | 'unavailable' | 'slow' | 'error', string> = {
  busy: 'Lots of videos are waiting for a summary right now. Try again in a minute.',
  unavailable: "The summary writer isn't running right now. Try again later.",
  slow: 'This one is taking longer than usual. Try again in a minute.',
  error: "Couldn't load the summary. Check your connection and try again.",
};

interface Props {
  youtubeId: string;
  /** Starts loading without a click, e.g. once the video is playing. */
  autoStart?: boolean;
}

/**
 * The "Video Summary" box under a video: a short summary and a quick quiz, both written by the
 * backend's LLM from the video's title and description. Loaded only when asked for (or when the
 * video plays), so scrolling past videos doesn't queue work for every one of them.
 */
export default function StudyBox({ youtubeId, autoStart = false }: Props) {
  const [state, setState] = useState<State>({ kind: 'idle' });
  const run = useRef(0);

  // Stop checking when the card goes away.
  useEffect(() => () => void (run.current += 1), []);

  async function load() {
    const id = ++run.current;
    const started = Date.now();
    setState({ kind: 'working' });
    while (id === run.current) {
      let study: VideoStudy;
      try {
        study = await fetchVideoStudy(youtubeId);
      } catch {
        if (id === run.current) setState({ kind: 'error' });
        return;
      }
      if (id !== run.current) return;
      if (study.status === 'ready') {
        setState({ kind: 'ready', summary: study.summary, questions: study.questions });
        return;
      }
      if (study.status !== 'pending') {
        setState({ kind: study.status });
        return;
      }
      if (Date.now() - started >= STUDY_GIVE_UP_MS) {
        setState({ kind: 'slow' });
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, STUDY_POLL_MS));
    }
  }

  useEffect(() => {
    if (autoStart && state.kind === 'idle') void load();
    // Only autoStart matters here: load() reads youtubeId, which never changes for a card.
  }, [autoStart]);

  return (
    <div className="video-summary-label study-box mt-1 flex flex-col gap-3 rounded-lg bg-muted/60 p-3 text-sm">
      <p className="flex items-center gap-2">
        <Sparkles aria-hidden="true" className="size-4 text-primary" />
        <strong>Video Summary</strong>
      </p>

      {state.kind === 'idle' && (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-muted-foreground">A short summary and a 2-question quiz on this video.</p>
          <button
            type="button"
            className="study-start inline-flex cursor-pointer items-center gap-1.5 rounded-md border bg-card px-3 py-1.5 text-xs font-medium transition-colors duration-150 hover:bg-accent"
            onClick={() => void load()}
          >
            Summarize and quiz me
          </button>
        </div>
      )}

      {state.kind === 'working' && (
        <p className="flex items-center gap-2 text-muted-foreground" role="status">
          <Loader2 aria-hidden="true" className="size-4 animate-spin" />
          Writing a summary and quiz. This usually takes under a minute.
        </p>
      )}

      {(state.kind === 'busy' || state.kind === 'unavailable' || state.kind === 'slow' || state.kind === 'error') && (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-muted-foreground" role="status">{MESSAGES[state.kind]}</p>
          <button
            type="button"
            className="inline-flex cursor-pointer items-center gap-1.5 rounded-md border bg-card px-3 py-1.5 text-xs font-medium transition-colors duration-150 hover:bg-accent"
            onClick={() => void load()}
          >
            <RotateCcw aria-hidden="true" className="size-3.5" />
            Try again
          </button>
        </div>
      )}

      {state.kind === 'ready' && (
        <>
          {/* Written by an LLM. Plain JSX text only; never dangerouslySetInnerHTML. */}
          <p className="study-summary leading-relaxed">{state.summary}</p>
          <Quiz youtubeId={youtubeId} questions={state.questions} />
          <p className="text-xs text-muted-foreground">
            Written by AI from the video&apos;s title and description, so it can get things wrong.
          </p>
        </>
      )}
    </div>
  );
}

function Quiz({ youtubeId, questions }: { youtubeId: string; questions: StudyQuestion[] }) {
  // The option each question was answered with, or undefined if not answered yet.
  const [picked, setPicked] = useState<(number | undefined)[]>(() => questions.map(() => undefined));
  const answered = picked.filter((p) => p !== undefined).length;
  const correct = picked.filter((p, i) => p === questions[i].answerIndex).length;
  const prefix = `quiz-${encodeURIComponent(youtubeId)}`;

  return (
    <section className="study-quiz flex flex-col gap-3" aria-label="Quiz">
      {questions.map((q, qi) => {
        const choice = picked[qi];
        const done = choice !== undefined;
        return (
          <fieldset key={qi} className="study-question flex flex-col gap-2 rounded-lg border bg-card p-3">
            <legend id={`${prefix}-q${qi}`} className="float-left mb-1 w-full font-medium">
              {qi + 1}. {q.question}
            </legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {q.options.map((option, oi) => {
                const isAnswer = oi === q.answerIndex;
                const isChoice = oi === choice;
                return (
                  <button
                    key={oi}
                    type="button"
                    className={cn(
                      'study-option flex cursor-pointer items-start gap-2 rounded-md border px-3 py-2 text-left transition-colors duration-150 hover:bg-accent disabled:cursor-default',
                      done && isAnswer && 'border-success/60 bg-success/10 hover:bg-success/10',
                      done && isChoice && !isAnswer && 'border-destructive/60 bg-destructive/10 hover:bg-destructive/10',
                      done && !isAnswer && !isChoice && 'opacity-60 hover:bg-transparent',
                    )}
                    aria-pressed={isChoice}
                    disabled={done}
                    onClick={() => setPicked((prev) => prev.map((p, i) => (i === qi ? oi : p)))}
                  >
                    {done && isAnswer && <CheckCircle2 aria-label="Correct answer" className="mt-0.5 size-4 shrink-0 text-success" />}
                    {done && isChoice && !isAnswer && <XCircle aria-label="Your answer" className="mt-0.5 size-4 shrink-0 text-destructive" />}
                    <span>{option}</span>
                  </button>
                );
              })}
            </div>
            {done && (
              <p className="study-explanation text-muted-foreground" role="status">
                <strong className={choice === q.answerIndex ? 'text-success' : 'text-destructive'}>
                  {choice === q.answerIndex ? 'Correct. ' : 'Not quite. '}
                </strong>
                {q.explanation}
              </p>
            )}
          </fieldset>
        );
      })}
      {answered === questions.length && (
        <div className="flex items-center justify-between gap-2">
          <p className="study-score font-medium">
            You got {correct} of {questions.length} right.
          </p>
          <button
            type="button"
            className="inline-flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium transition-colors duration-150 hover:bg-accent"
            onClick={() => setPicked(questions.map(() => undefined))}
          >
            <RotateCcw aria-hidden="true" className="size-3.5" />
            Retake
          </button>
        </div>
      )}
    </section>
  );
}
