-- Defense in depth: the database itself refuses data that the backend's validation should
-- already have rejected, in case a future code path forgets to validate.
-- The email and username patterns must match the ones in AuthController.java and
-- frontend/src/validation.ts. If you change a rule, change all three (here via a new migration).

ALTER TABLE users
    ADD CONSTRAINT users_email_format
        CHECK (char_length(email) <= 254 AND email = lower(email) AND email ~ '^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$'),
    ADD CONSTRAINT users_username_format
        CHECK (username ~ '^[A-Za-z0-9_]{3,30}$'),
    -- Only Argon2id hashes (PHC format) may be stored, never a plaintext password.
    ADD CONSTRAINT users_password_is_argon2id
        CHECK (password_hash ~ '^\$argon2id\$v=19\$m=[0-9]+,t=[0-9]+,p=[0-9]+\$[A-Za-z0-9+/]{22,}\$[A-Za-z0-9+/]{43,}$');

ALTER TABLE sessions
    -- Only SHA-256 hex digests may be stored, never a raw session token.
    ADD CONSTRAINT sessions_token_is_sha256
        CHECK (token_hash ~ '^[0-9a-f]{64}$');
