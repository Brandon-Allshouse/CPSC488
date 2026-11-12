import { useEffect, useRef, useState } from 'react';
import { CheckCircle2, Flame, Loader2, RotateCcw, Sparkles, Trophy, XCircle } from 'lucide-react';
import { ApiError } from '../api/client';
import { answerVideoQuestion, fetchVideoStudy, type AnswerResult, type StudyQuestion, type VideoStudy } from '../api/study';
import { cn } from '@/lib/utils';

// How often to ask whether the summary is done, and when to stop asking. One usually takes about
// 10 seconds now (often 0, thanks to feed prefetch), so 2 s polls add ~1 s of wait on average.
// A poll for a finished video is one indexed lookup, so this is cheap.
export const STUDY_POLL_MS = 2000;
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
  /** Shows a "See the leaderboard" button once the quiz is done. */
  onShowLeaderboard?: () => void;
}

/**
 * The "Video Summary" box under a video: a short summary and a quick quiz, both written by the
 * backend's LLM from the video's title and description. Loaded only when asked for (or when the
 * video plays), so scrolling past videos doesn't queue work for every one of them. Answers are
 * checked by the backend, which also keeps score for signed-in users (first answer only).
 */
export default function StudyBox({ youtubeId, autoStart = false, onShowLeaderboard }: Props) {
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
          <Quiz youtubeId={youtubeId} questions={state.questions} onShowLeaderboard={onShowLeaderboard} />
          <p className="text-xs text-muted-foreground">
            Written by AI from the video&apos;s title and description, so it can get things wrong.
          </p>
        </>
      )}
    </div>
  );
}

// Where each question is at. The right answer only arrives from the backend after answering.
type QuestionState =
  | { kind: 'open'; error?: string }
  | { kind: 'checking'; choice: number }
  | { kind: 'done'; choice: number; result: AnswerResult };

function Quiz({
  youtubeId,
  questions,
  onShowLeaderboard,
}: {
  youtubeId: string;
  questions: StudyQuestion[];
  onShowLeaderboard?: () => void;
}) {
  const [states, setStates] = useState<QuestionState[]>(() => questions.map(() => ({ kind: 'open' })));
  const done = states.filter((s) => s.kind === 'done');
  const correct = done.filter((s) => s.result.correct).length;
  const points = done.reduce((sum, s) => sum + s.result.pointsEarned, 0);
  const signedIn = done.some((s) => s.result.signedIn);
  const prefix = `quiz-${encodeURIComponent(youtubeId)}`;

  function update(qi: number, next: QuestionState) {
    setStates((prev) => prev.map((s, i) => (i === qi ? next : s)));
  }

  async function pick(qi: number, choice: number) {
    update(qi, { kind: 'checking', choice });
    try {
      update(qi, { kind: 'done', choice, result: await answerVideoQuestion(youtubeId, qi, choice) });
    } catch (err) {
      // Backend messages (like the rate limit) are written for users; anything else gets ours.
      const message = err instanceof ApiError && err.status !== 0 ? err.message : "Couldn't check your answer. Try again.";
      update(qi, { kind: 'open', error: message });
    }
  }

  return (
    <section className="study-quiz flex flex-col gap-3" aria-label="Quiz">
      {questions.map((q, qi) => {
        const state = states[qi];
        const result = state.kind === 'done' ? state.result : undefined;
        const choice = state.kind === 'open' ? undefined : state.choice;
        return (
          <fieldset key={qi} className="study-question flex flex-col gap-2 rounded-lg border bg-card p-3">
            <legend id={`${prefix}-q${qi}`} className="float-left mb-1 w-full font-medium">
              {qi + 1}. {q.question}
            </legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {q.options.map((option, oi) => {
                const isAnswer = result !== undefined && oi === result.answerIndex;
                const isChoice = oi === choice;
                return (
                  <button
                    key={oi}
                    type="button"
                    className={cn(
                      'study-option flex cursor-pointer items-start gap-2 rounded-md border px-3 py-2 text-left transition-colors duration-150 hover:bg-accent disabled:cursor-default',
                      isAnswer && 'border-success/60 bg-success/10 hover:bg-success/10',
                      result && isChoice && !isAnswer && 'border-destructive/60 bg-destructive/10 hover:bg-destructive/10',
                      result && !isAnswer && !isChoice && 'opacity-60 hover:bg-transparent',
                    )}
                    aria-pressed={isChoice}
                    // also off while checking, so a double click can't send two answers
                    disabled={state.kind !== 'open'}
                    onClick={() => void pick(qi, oi)}
                  >
                    {state.kind === 'checking' && isChoice && (
                      <Loader2 aria-label="Checking" className="mt-0.5 size-4 shrink-0 animate-spin" />
                    )}
                    {isAnswer && <CheckCircle2 aria-label="Correct answer" className="mt-0.5 size-4 shrink-0 text-success" />}
                    {result && isChoice && !isAnswer && (
                      <XCircle aria-label="Your answer" className="mt-0.5 size-4 shrink-0 text-destructive" />
                    )}
                    <span>{option}</span>
                  </button>
                );
              })}
            </div>
            {state.kind === 'open' && state.error && (
              <p className="study-answer-error text-destructive" role="alert">
                {state.error}
              </p>
            )}
            {result && (
              <div className="study-explanation flex flex-col gap-1 text-muted-foreground" role="status">
                <p>
                  <strong className={result.correct ? 'text-success' : 'text-destructive'}>
                    {result.correct ? 'Correct. ' : 'Not quite. '}
                  </strong>
                  {result.explanation}
                </p>
                <PointsNote result={result} />
              </div>
            )}
          </fieldset>
        );
      })}
      {done.length === questions.length && (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="study-score font-medium">
            You got {correct} of {questions.length} right.
            {signedIn && points > 0 && ` +${points} points.`}
          </p>
          <div className="flex items-center gap-1">
            {onShowLeaderboard && (
              <button
                type="button"
                className="study-leaderboard inline-flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium transition-colors duration-150 hover:bg-accent"
                onClick={onShowLeaderboard}
              >
                <Trophy aria-hidden="true" className="size-3.5" />
                See the leaderboard
              </button>
            )}
            <button
              type="button"
              className="inline-flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium transition-colors duration-150 hover:bg-accent"
              onClick={() => setStates(questions.map(() => ({ kind: 'open' })))}
            >
              <RotateCcw aria-hidden="true" className="size-3.5" />
              Retake
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

// What the answer did for your score. Only the first answer to each question counts.
function PointsNote({ result }: { result: AnswerResult }) {
  if (!result.signedIn) {
    return <p className="study-points text-xs">Log in to earn points.</p>;
  }
  if (!result.counted) {
    return <p className="study-points text-xs">You answered this one before, so it doesn&apos;t earn points again.</p>;
  }
  if (result.pointsEarned > 0) {
    return (
      <p className="study-points flex items-center gap-1 text-xs font-medium text-success">
        <Flame aria-hidden="true" className="size-3.5" />+{result.pointsEarned} points
        {result.streak !== null && result.streak > 0 && ` · ${result.streak}-day streak`}
      </p>
    );
  }
  return null;
}
