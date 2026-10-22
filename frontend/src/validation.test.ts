import { describe, expect, it } from 'vitest';
import { parseYoutubeId, passwordLength, validateEmail, validateNewPassword, validateUsername } from './validation';

describe('passwordLength', () => {
  it('counts each emoji as one character', () => {
    expect(passwordLength('🦉🐙🦊')).toBe(3);
  });

  it('counts an accented letter the same no matter how it was typed', () => {
    // é as one character vs e + an accent mark
    expect(passwordLength('café')).toBe(passwordLength('café'));
  });
});

describe('validateEmail', () => {
  it('accepts a normal email', () => {
    expect(validateEmail('test@sru.edu')).toBeNull();
  });

  it('ignores spaces around it', () => {
    expect(validateEmail('  test@sru.edu  ')).toBeNull();
  });

  it('says when the email is missing', () => {
    expect(validateEmail('   ')).toBe('Email is required.');
  });

  it('rejects emails that are not valid', () => {
    expect(validateEmail('testuser')).not.toBeNull();
    expect(validateEmail('test@sru')).not.toBeNull();
    expect(validateEmail('test @sru.edu')).not.toBeNull();
  });

  it('rejects emails over 254 characters', () => {
    expect(validateEmail('a'.repeat(250) + '@sru.edu')).not.toBeNull();
  });
});

describe('validateUsername', () => {
  it('accepts letters, numbers and underscores', () => {
    expect(validateUsername('test_user_2')).toBeNull();
  });

  it('rejects usernames that are too short or too long', () => {
    expect(validateUsername('ab')).not.toBeNull();
    expect(validateUsername('a'.repeat(31))).not.toBeNull();
  });

  it('rejects spaces and symbols', () => {
    expect(validateUsername('test user')).not.toBeNull();
    expect(validateUsername('test-user')).not.toBeNull();
  });
});

describe('validateNewPassword', () => {
  it('needs at least 15 characters', () => {
    expect(validateNewPassword('fourteen chars')).not.toBeNull();
    expect(validateNewPassword('fifteen chars!!')).toBeNull();
  });

  it('allows up to 128 characters', () => {
    expect(validateNewPassword('a'.repeat(128))).toBeNull();
    expect(validateNewPassword('a'.repeat(129))).not.toBeNull();
  });

  it('does not require numbers or symbols', () => {
    expect(validateNewPassword('just some lowercase words')).toBeNull();
  });
});

describe('parseYoutubeId', () => {
  it('accepts a bare video id', () => {
    expect(parseYoutubeId(' dQw4w9WgXcQ ')).toBe('dQw4w9WgXcQ');
  });

  it('reads the id out of the usual YouTube links', () => {
    expect(parseYoutubeId('https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s')).toBe('dQw4w9WgXcQ');
    expect(parseYoutubeId('youtube.com/watch?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(parseYoutubeId('https://youtu.be/dQw4w9WgXcQ?si=abc')).toBe('dQw4w9WgXcQ');
    expect(parseYoutubeId('https://m.youtube.com/shorts/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(parseYoutubeId('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
  });

  it('rejects other sites and things that are not video ids', () => {
    expect(parseYoutubeId('https://evil.example/watch?v=dQw4w9WgXcQ')).toBeNull();
    expect(parseYoutubeId('https://www.youtube.com/watch?v=short')).toBeNull();
    expect(parseYoutubeId('https://www.youtube.com/@SomeChannel')).toBeNull();
    expect(parseYoutubeId('not a link')).toBeNull();
    expect(parseYoutubeId('')).toBeNull();
  });
});
