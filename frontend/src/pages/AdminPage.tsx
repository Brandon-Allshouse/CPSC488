import { type FormEvent, type ReactNode, useEffect, useState } from 'react';
import {
  Ban,
  CircleAlert,
  CircleCheck,
  Film,
  History,
  Search,
  ShieldCheck,
  ShieldOff,
  UserX,
  Users,
} from 'lucide-react';
import {
  banUser,
  banVideo,
  fetchAdminActions,
  fetchAdminStats,
  fetchBannedVideos,
  searchUsers,
  searchVideos,
  unbanUser,
  unbanVideo,
  type AdminAction,
  type AdminStats,
  type AdminUser,
  type AdminVideo,
  type BannedVideo,
} from '../api/admin';
import { ApiError } from '../api/client';
import { parseYoutubeId } from '../validation';
import PageLayout, { type PageNavigation } from '@/components/layout/PageLayout';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';

// Same limit as AdminController.MAX_REASON on the backend.
const MAX_REASON = 200;

const ACTION_LABELS: Record<AdminAction['action'], string> = {
  ban_user: 'Banned user',
  unban_user: 'Unbanned user',
  ban_video: 'Banned video',
  unban_video: 'Unbanned video',
};

function formatDate(value: string) {
  return new Date(value).toLocaleDateString();
}

// Asks for the reason before a ban goes through, so a stray click can't ban anyone and the
// admin log always says why.
function BanReasonForm({ label, onConfirm, onCancel }: {
  label: string;
  onConfirm: (reason: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await onConfirm(reason.trim());
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="admin-ban-form flex min-w-64 animate-fade-in-up items-center gap-2" onSubmit={submit}>
      <Input
        aria-label={`Reason for banning ${label}`}
        placeholder="Reason"
        className="h-8"
        value={reason}
        maxLength={MAX_REASON}
        onChange={(e) => setReason(e.target.value)}
        autoFocus
      />
      <Button type="submit" size="sm" variant="destructive" disabled={busy || reason.trim() === ''}>
        Confirm ban
      </Button>
      <Button size="sm" variant="ghost" onClick={onCancel}>
        Cancel
      </Button>
    </form>
  );
}

// One boxed section of the page with a heading. role="region" + the heading make it a landmark
// screen reader users can jump to.
function AdminSection({ id, title, description, icon, children }: {
  id: string;
  title: string;
  description?: string;
  icon: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card role="region" aria-labelledby={id} className="admin-section">
      <CardHeader>
        <h2 id={id} className="flex items-center gap-2 text-lg [&_svg]:size-5 [&_svg]:text-muted-foreground">
          {icon}
          {title}
        </h2>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  );
}

function SearchForm({ label, placeholder, value, onChange, onSubmit }: {
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  onSubmit: (e: FormEvent) => void;
}) {
  return (
    <form className="admin-search flex gap-2" onSubmit={onSubmit} role="search">
      <div className="relative flex-1">
        <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          aria-label={label}
          placeholder={placeholder}
          className="pl-9"
          value={value}
          maxLength={100}
          onChange={(e) => onChange(e.target.value)}
        />
      </div>
      <Button type="submit" variant="secondary">
        Search
      </Button>
    </form>
  );
}

function EmptyRow({ columns, children }: { columns: number; children: ReactNode }) {
  return (
    <TableRow>
      <TableCell colSpan={columns} className="py-8 text-center text-muted-foreground">
        {children}
      </TableCell>
    </TableRow>
  );
}

// Only reachable from the profile menu when user.admin is true. Hiding the menu item is just
// convenience: every request here is checked again by the backend, which answers 403 to non-admins.
export default function AdminPage(navigation: PageNavigation) {
  const { user, onLogout } = navigation;
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [userSearch, setUserSearch] = useState('');
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [videoSearch, setVideoSearch] = useState('');
  const [videos, setVideos] = useState<AdminVideo[]>([]);
  const [bannedVideos, setBannedVideos] = useState<BannedVideo[]>([]);
  const [actions, setActions] = useState<AdminAction[]>([]);
  // Which row's "Ban" button was pressed, e.g. "user:5" or "video:dQw4w9WgXcQ".
  const [pendingBan, setPendingBan] = useState<string | null>(null);
  const [videoLink, setVideoLink] = useState('');
  const [videoLinkReason, setVideoLinkReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function handleError(err: unknown) {
    if (err instanceof ApiError && err.status === 401) {
      onLogout();
    } else if (err instanceof ApiError && err.status === 403) {
      setError('You need admin access to use this page.');
    } else {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
    }
  }

  // The parts of the page every ban or unban changes.
  async function refreshSummary() {
    const [newStats, newBanned, newActions] = await Promise.all([
      fetchAdminStats(),
      fetchBannedVideos(),
      fetchAdminActions(),
    ]);
    setStats(newStats);
    setBannedVideos(newBanned);
    setActions(newActions);
  }

  useEffect(() => {
    if (!user?.admin) return;
    Promise.all([refreshSummary(), searchUsers('').then(setUsers), searchVideos('').then(setVideos)]).catch(
      handleError,
    );
    // Runs once when the page opens; the user doesn't change while it's open.
  }, [user]);

  // Runs a ban or unban, then reloads what it changed. Shows the outcome either way.
  async function runAction(action: () => Promise<void>, done: string, reload: () => Promise<void>) {
    setError(null);
    setNotice(null);
    try {
      await action();
      setPendingBan(null);
      setNotice(done);
      await Promise.all([refreshSummary(), reload()]);
    } catch (err) {
      handleError(err);
    }
  }

  const reloadUsers = () => searchUsers(userSearch).then(setUsers);
  const reloadVideos = () => searchVideos(videoSearch).then(setVideos);

  function submitUserSearch(e: FormEvent) {
    e.preventDefault();
    setError(null);
    reloadUsers().catch(handleError);
  }

  function submitVideoSearch(e: FormEvent) {
    e.preventDefault();
    setError(null);
    reloadVideos().catch(handleError);
  }

  async function submitVideoLink(e: FormEvent) {
    e.preventDefault();
    const youtubeId = parseYoutubeId(videoLink);
    if (!youtubeId) {
      setError('That is not a YouTube video link or id.');
      return;
    }
    await runAction(() => banVideo(youtubeId, videoLinkReason.trim()), `Banned video ${youtubeId}.`, async () => {
      setVideoLink('');
      setVideoLinkReason('');
      await reloadVideos();
    });
  }

  if (!user?.admin) {
    return (
      <PageLayout {...navigation} name="admin" title="Admin">
        <Alert variant="destructive" role="alert">
          <ShieldOff aria-hidden="true" />
          You need admin access to use this page.
        </Alert>
      </PageLayout>
    );
  }

  const statTiles: [string, number | undefined, ReactNode][] = [
    ['Users', stats?.users, <Users key="users" />],
    ['Banned users', stats?.bannedUsers, <UserX key="banned-users" />],
    ['Admins', stats?.admins, <ShieldCheck key="admins" />],
    ['Videos', stats?.videos, <Film key="videos" />],
    ['Banned videos', stats?.bannedVideos, <Ban key="banned-videos" />],
  ];

  return (
    <PageLayout {...navigation} name="admin" title="Admin" description="Ban users and videos, and see what other admins did.">
      {error && (
        <Alert variant="destructive" role="alert">
          <CircleAlert aria-hidden="true" />
          {error}
        </Alert>
      )}
      {notice && (
        <Alert variant="success" role="status" className="admin-notice">
          <CircleCheck aria-hidden="true" />
          {notice}
        </Alert>
      )}

      <AdminSection id="admin-overview" title="Overview" icon={<ShieldCheck />}>
        <dl className="admin-stats grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {statTiles.map(([label, value, icon]) => (
            <div key={label} className="rounded-lg border bg-background/50 p-4 transition-colors duration-150 hover:border-primary/50">
              <dt className="flex items-center gap-1.5 text-xs text-muted-foreground [&_svg]:size-3.5">
                {icon}
                {label}
              </dt>
              <dd className="mt-1 text-2xl font-semibold tabular-nums">{value ?? '–'}</dd>
            </div>
          ))}
        </dl>
      </AdminSection>

      <AdminSection id="admin-users" title="Users" icon={<Users />} description="Banning logs the user out everywhere and blocks their login.">
        <SearchForm
          label="Search users"
          placeholder="Username or email"
          value={userSearch}
          onChange={setUserSearch}
          onSubmit={submitUserSearch}
        />
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">Username</TableHead>
              <TableHead scope="col">Email</TableHead>
              <TableHead scope="col">Joined</TableHead>
              <TableHead scope="col">Status</TableHead>
              <TableHead scope="col" className="text-right">Action</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {users.length === 0 && <EmptyRow columns={5}>No users found.</EmptyRow>}
            {users.map((row) => (
              <TableRow key={row.id}>
                <TableCell className="font-medium">{row.username}</TableCell>
                <TableCell className="text-muted-foreground">{row.email}</TableCell>
                <TableCell className="text-muted-foreground">{formatDate(row.createdAt)}</TableCell>
                <TableCell>
                  {row.admin ? (
                    <Badge>Admin</Badge>
                  ) : row.bannedAt ? (
                    <Badge variant="destructive">{`Banned: ${row.banReason}`}</Badge>
                  ) : (
                    <Badge variant="success">Active</Badge>
                  )}
                </TableCell>
                <TableCell className="text-right">
                  {row.admin || row.id === user.id ? null : row.bannedAt ? (
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`Unban ${row.username}`}
                      onClick={() => runAction(() => unbanUser(row.id), `Unbanned ${row.username}.`, reloadUsers)}
                    >
                      Unban
                    </Button>
                  ) : pendingBan === `user:${row.id}` ? (
                    <BanReasonForm
                      label={row.username}
                      onCancel={() => setPendingBan(null)}
                      onConfirm={(reason) => runAction(() => banUser(row.id, reason), `Banned ${row.username}.`, reloadUsers)}
                    />
                  ) : (
                    <Button
                      size="sm"
                      variant="destructive-outline"
                      aria-label={`Ban ${row.username}`}
                      onClick={() => setPendingBan(`user:${row.id}`)}
                    >
                      Ban
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </AdminSection>

      <AdminSection
        id="admin-videos"
        title="Videos"
        icon={<Film />}
        description="Banned videos leave the feed and everyone's liked lists, and are never fetched again."
      >
        <form className="admin-search flex flex-col gap-2 rounded-lg border border-dashed p-3 sm:flex-row" onSubmit={submitVideoLink}>
          <Input
            aria-label="YouTube link or video id"
            placeholder="YouTube link or video id"
            className="sm:flex-[2]"
            value={videoLink}
            onChange={(e) => setVideoLink(e.target.value)}
          />
          <Input
            aria-label="Reason for banning this link"
            placeholder="Reason"
            className="sm:flex-1"
            value={videoLinkReason}
            maxLength={MAX_REASON}
            onChange={(e) => setVideoLinkReason(e.target.value)}
          />
          <Button type="submit" variant="destructive" disabled={videoLink.trim() === '' || videoLinkReason.trim() === ''}>
            <Ban aria-hidden="true" />
            Ban video
          </Button>
        </form>
        <p className="admin-hint -mt-2 text-xs text-muted-foreground">Works for any YouTube video, even ones not in the feed yet.</p>
        <SearchForm
          label="Search videos"
          placeholder="Title, channel or id"
          value={videoSearch}
          onChange={setVideoSearch}
          onSubmit={submitVideoSearch}
        />
        {/* Up to 50 results, so this list scrolls on its own instead of making the page huge. */}
        <div className="max-h-[28rem] overflow-y-auto rounded-md border">
          <Table>
            <TableHeader className="sticky top-0 z-10 bg-card">
              <TableRow>
                <TableHead scope="col">Title</TableHead>
                <TableHead scope="col">Channel</TableHead>
                <TableHead scope="col">Topic</TableHead>
                <TableHead scope="col" className="text-right">Action</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {videos.length === 0 && <EmptyRow columns={4}>No videos found.</EmptyRow>}
              {videos.map((video) => (
                <TableRow key={video.youtubeId}>
                  <TableCell className="max-w-80 truncate font-medium" title={video.title}>
                    {video.title}
                  </TableCell>
                  <TableCell className="text-muted-foreground">{video.channelTitle}</TableCell>
                  <TableCell>
                    <Badge variant="outline">{video.topic}</Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    {pendingBan === `video:${video.youtubeId}` ? (
                      <BanReasonForm
                        label={video.title}
                        onCancel={() => setPendingBan(null)}
                        onConfirm={(reason) =>
                          runAction(() => banVideo(video.youtubeId, reason), `Banned "${video.title}".`, reloadVideos)
                        }
                      />
                    ) : (
                      <Button
                        size="sm"
                        variant="destructive-outline"
                        aria-label={`Ban ${video.title}`}
                        onClick={() => setPendingBan(`video:${video.youtubeId}`)}
                      >
                        Ban
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </AdminSection>

      <AdminSection
        id="admin-banned-videos"
        title="Banned videos"
        icon={<Ban />}
        description="An unbanned video comes back the next time its topic is refreshed."
      >
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">Video</TableHead>
              <TableHead scope="col">Reason</TableHead>
              <TableHead scope="col">Banned by</TableHead>
              <TableHead scope="col">Date</TableHead>
              <TableHead scope="col" className="text-right">Action</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {bannedVideos.length === 0 && <EmptyRow columns={5}>No banned videos.</EmptyRow>}
            {bannedVideos.map((video) => (
              <TableRow key={video.youtubeId}>
                <TableCell className="max-w-72 truncate font-medium">{video.title ?? video.youtubeId}</TableCell>
                <TableCell>{video.reason}</TableCell>
                <TableCell className="text-muted-foreground">{video.bannedBy ?? 'Deleted account'}</TableCell>
                <TableCell className="text-muted-foreground">{formatDate(video.bannedAt)}</TableCell>
                <TableCell className="text-right">
                  <Button
                    size="sm"
                    variant="outline"
                    aria-label={`Unban ${video.title ?? video.youtubeId}`}
                    onClick={() =>
                      runAction(() => unbanVideo(video.youtubeId), `Unbanned ${video.title ?? video.youtubeId}.`, reloadVideos)
                    }
                  >
                    Unban
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </AdminSection>

      <AdminSection id="admin-actions" title="Recent admin actions" icon={<History />}>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">Date</TableHead>
              <TableHead scope="col">Admin</TableHead>
              <TableHead scope="col">Action</TableHead>
              <TableHead scope="col">Target</TableHead>
              <TableHead scope="col">Reason</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {actions.length === 0 && <EmptyRow columns={5}>Nothing yet.</EmptyRow>}
            {actions.map((entry) => (
              <TableRow key={entry.id}>
                <TableCell className="text-muted-foreground">{formatDate(entry.createdAt)}</TableCell>
                <TableCell>{entry.admin ?? 'Deleted account'}</TableCell>
                <TableCell>
                  <Badge variant={entry.action.startsWith('unban') ? 'secondary' : 'destructive'}>
                    {ACTION_LABELS[entry.action]}
                  </Badge>
                </TableCell>
                <TableCell className="font-mono text-xs">{entry.target}</TableCell>
                <TableCell>{entry.reason ?? ''}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </AdminSection>
    </PageLayout>
  );
}
