import type { User } from '../api/auth';

interface Props {
  user: User | null;
  onLogout: () => void;
}

// Placeholder until the feed is built.
export default function HomePage({ user, onLogout }: Props) {
  return (
    <main className="center-screen">
      <div className="auth-card">
        <h1>{user ? `Hi, ${user.username}` : 'Browsing as guest'}</h1>
        <p className="muted">The video feed will live here.</p>
        <button type="button" className="btn btn-secondary" onClick={onLogout}>
          {user ? 'Log out' : 'Back to login'}
        </button>
      </div>
    </main>
  );
}
