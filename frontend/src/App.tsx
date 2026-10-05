import { useEffect, useState } from 'react';
import { fetchCurrentUser, logout, type User } from './api/auth';
import { fetchMyInterests, saveMyInterests } from './api/feed';
import LoginPage from './pages/LoginPage';
import InterestsPage from './pages/InterestsPage';
import FeedPage from './pages/FeedPage';

// Who is using the app right now. Decides which page is shown.
type Session =
  | { kind: 'loading' } // still asking the backend whether there's a login cookie
  | { kind: 'anonymous' } // not logged in: show the login page
  | { kind: 'guest' } // chose "Continue as guest"; exists only in the browser, nothing saved
  | { kind: 'user'; user: User }; // logged in

export default function App() {
  const [session, setSession] = useState<Session>({ kind: 'loading' });
  // Topic ids the feed is built from. null while a logged-in user's saved interests are loading.
  // Guests' interests only live here, so they're gone after a page reload.
  const [interests, setInterests] = useState<number[] | null>(null);
  const [editingInterests, setEditingInterests] = useState(false);

  function startUserSession(user: User) {
    setSession({ kind: 'user', user });
    setInterests(null);
    // If loading fails, show the picker; saving there replaces whatever was stored.
    fetchMyInterests()
      .then(setInterests)
      .catch(() => setInterests([]));
  }

  // On page load, restore an existing login. The session cookie is HttpOnly, so the page can't
  // see it directly and has to ask the backend.
  useEffect(() => {
    fetchCurrentUser()
      .then((user) => (user ? startUserSession(user) : setSession({ kind: 'anonymous' })))
      .catch(() => setSession({ kind: 'anonymous' }));
  }, []);

  async function handleLogout() {
    if (session.kind === 'user') {
      // Even if the request fails (e.g. backend down), still show the login page locally.
      await logout().catch(() => undefined);
    }
    setSession({ kind: 'anonymous' });
    setInterests(null);
    setEditingInterests(false);
  }

  async function handleSaveInterests(topicIds: number[]) {
    // Logged-in users' picks are saved on the server; guests' stay in the browser.
    const saved = session.kind === 'user' ? await saveMyInterests(topicIds) : topicIds;
    setInterests(saved);
    setEditingInterests(false);
  }

  switch (session.kind) {
    case 'loading':
      return <div className="center-screen muted">Loading…</div>;
    case 'anonymous':
      return (
        <LoginPage
          onAuthenticated={startUserSession}
          onGuest={() => {
            setSession({ kind: 'guest' });
            setInterests([]);
          }}
        />
      );
    case 'guest':
    case 'user': {
      const user = session.kind === 'user' ? session.user : null;
      if (interests === null) {
        return <div className="center-screen muted">Loading…</div>;
      }
      if (editingInterests || interests.length === 0) {
        return (
          <InterestsPage
            initial={interests}
            onSave={handleSaveInterests}
            onCancel={interests.length > 0 ? () => setEditingInterests(false) : undefined}
            isGuest={user === null}
          />
        );
      }
      return (
        <FeedPage
          // A new key when interests change makes React start a fresh feed.
          key={interests.join(',')}
          topicIds={interests}
          user={user}
          onEditInterests={() => setEditingInterests(true)}
          onLogout={handleLogout}
        />
      );
    }
  }
}
