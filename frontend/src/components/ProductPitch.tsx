import { useEffect, useState, type ReactNode } from 'react';
import { BookOpen, ListVideo, PlaySquare } from 'lucide-react';
import { fetchTopics, type Topic } from '../api/feed';

// How many subject names to show as chips before "+ N more".
const SHOWN_SUBJECTS = 10;

// Only features that exist today. Add to this list when quizzes and progress ship, not before.
const FEATURES: { icon: ReactNode; title: string; text: string }[] = [
  { icon: <PlaySquare />, title: 'A feed from your topics', text: 'Pick whole subjects or just parts, like Algebra 2.' },
  { icon: <BookOpen />, title: 'Study notes', text: 'Save a video and write down what you learned.' },
  { icon: <ListVideo />, title: 'Playlists and history', text: 'Keep videos for later and find ones you watched.' },
];

// The left half of the login screen on wide screens: what BrainFeed is, for people seeing it for the
// first time. The subject list comes from /api/topics (public), so the numbers are always real.
export default function ProductPitch() {
  const [topics, setTopics] = useState<Topic[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchTopics()
      .then((loaded) => {
        if (!cancelled) setTopics(loaded);
      })
      // Just decoration; the login form works without it.
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const subjects = topics?.filter((topic) => topic.parentId === null) ?? [];
  const subTopics = (topics?.length ?? 0) - subjects.length;

  return (
    <section className="product-pitch flex flex-col gap-8" aria-labelledby="pitch-title">
      <div className="flex flex-col gap-2">
        <h2 id="pitch-title" className="text-3xl">
          Learn with short videos
        </h2>
        <p className="max-w-lg text-muted-foreground">A feed of educational videos on the subjects you pick.</p>
      </div>

      <ul className="flex flex-col gap-4">
        {FEATURES.map((feature) => (
          <li key={feature.title} className="flex gap-3">
            <span
              aria-hidden="true"
              className="flex size-10 shrink-0 items-center justify-center rounded-lg border bg-card text-primary shadow-sm [&_svg]:size-5"
            >
              {feature.icon}
            </span>
            <div>
              <h3 className="text-base">{feature.title}</h3>
              <p className="text-sm text-muted-foreground">{feature.text}</p>
            </div>
          </li>
        ))}
      </ul>

      {subjects.length > 0 && (
        <div className="flex flex-col gap-3">
          <p className="text-sm font-medium">
            {subjects.length} subjects and {subTopics} topics to choose from
          </p>
          <ul className="flex flex-wrap gap-2" aria-label="Subjects">
            {subjects.slice(0, SHOWN_SUBJECTS).map((subject) => (
              <li key={subject.id} className="rounded-full border bg-card px-3 py-1 text-xs font-medium shadow-xs">
                {subject.name}
              </li>
            ))}
            {subjects.length > SHOWN_SUBJECTS && (
              <li className="rounded-full px-3 py-1 text-xs font-medium text-muted-foreground">
                + {subjects.length - SHOWN_SUBJECTS} more
              </li>
            )}
          </ul>
        </div>
      )}
    </section>
  );
}
