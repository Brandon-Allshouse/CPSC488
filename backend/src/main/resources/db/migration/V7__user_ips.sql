-- IP addresses for the admin page: where each account was created and last logged in from, and
-- where each admin action came from. Like every migration: once it has run anywhere, don't edit it.
--
-- IP addresses are personal data, so they're only ever shown to admins (never in /api/auth/me or
-- any other user-facing response). INET makes Postgres reject anything that isn't an IP address.
-- Rows from before this migration have NULL, since we never knew their IPs.
ALTER TABLE users
    ADD COLUMN signup_ip     INET,
    ADD COLUMN last_login_ip INET,
    ADD COLUMN last_login_at TIMESTAMPTZ;

ALTER TABLE admin_actions
    ADD COLUMN ip INET;
