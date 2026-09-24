import { useEffect, useState } from 'react';
import { fetchCurrentUser, logout, type User } from './api/auth';
import LoginPage from './pages/LoginPage';
import HomePage from './pages/HomePage';

// Who is using the app right now. Decides which page is shown.
type Session =
  | { kind: 'loading' } // still asking the backend whether there's a login cookie
  | { kind: 'anonymous' } // not logged in: show the login page
  | { kind: 'guest' } // chose "Continue as guest"; exists only in the browser, nothing saved
  | { kind: 'user'; user: User }; // logged in

export default function App() {
  const [session, setSession] = useState<Session>({ kind: 'loading' });

  // On page load, restore an existing login. The session cookie is HttpOnly, so the page can't
  // see it directly and has to ask the backend.
  useEffect(() => {
    fetchCurrentUser()
      .then((user) => setSession(user ? { kind: 'user', user } : { kind: 'anonymous' }))
      .catch(() => setSession({ kind: 'anonymous' }));
  }, []);

  async function handleLogout() {
    if (session.kind === 'user') {
      // Even if the request fails (e.g. backend down), still show the login page locally.
      await logout().catch(() => undefined);
    }
    setSession({ kind: 'anonymous' });
  }

  switch (session.kind) {
    case 'loading':
      return <div className="center-screen muted">Loading…</div>;
    case 'anonymous':
      return (
        <LoginPage
          onAuthenticated={(user) => setSession({ kind: 'user', user })}
          onGuest={() => setSession({ kind: 'guest' })}
        />
      );
    case 'guest':
      return <HomePage user={null} onLogout={handleLogout} />;
    case 'user':
      return <HomePage user={session.user} onLogout={handleLogout} />;
  }
}
