import { useState } from 'react';
import { Label } from '@/components/ui/label';

// Language dropdown. UI only for now: picking a language doesn't translate anything yet.
// To make it work later, either add a translation library (e.g. react-i18next) for the app's text,
// or send the language to the backend so YouTubeClient searches in it (it currently hardcodes
// relevanceLanguage=en).
const LANGUAGES = [
  { code: 'en', label: 'English' },
  { code: 'es', label: 'Español' },
  { code: 'fr', label: 'Français' },
  { code: 'de', label: 'Deutsch' },
  { code: 'pt', label: 'Português' },
  { code: 'zh', label: '中文' },
  { code: 'ja', label: '日本語' },
];

export default function LanguageSelect() {
  const [language, setLanguage] = useState('en');
  return (
    <Label className="font-normal text-muted-foreground">
      Language
      <select
        className="h-8 cursor-pointer rounded-md border border-input bg-background/60 px-2 text-sm text-foreground shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40"
        value={language}
        onChange={(e) => setLanguage(e.target.value)}
      >
        {LANGUAGES.map(({ code, label }) => (
          <option key={code} value={code}>
            {label}
          </option>
        ))}
      </select>
    </Label>
  );
}
