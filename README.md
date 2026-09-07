# Educational Scrolling

CPSC 488 Software Engineering

Matthew Ryboth, Brody Scott, Brandon Allshouse, Madeline Foradori

## What this is

Most short-form video apps are built to keep you scrolling, not to teach you anything.
This project is our attempt at flipping that: same familiar scrolling feed, but built
around educational content instead of whatever an algorithm thinks will keep you hooked.

Users pick a few topics they're interested in — programming, history, biology, whatever —
and get a feed of videos that actually explain or teach something about those topics,
pulled mainly from YouTube (possibly TikTok/Instagram later on for extra content).

## Features

- Sign up for an account, or just browse as a guest
- Pick interests up front, change them whenever
- Scrollable feed of educational videos
- Thumbs up / down on videos to improve what you're shown
- Less content from creators/channels you've marked as not interesting
- Preferences and history saved for registered users
- A random/discovery feed to branch out into new topics

## Stack (subject to change)

- **Backend:** Java, using Javalin 
- **Frontend:** React + TypeScript
- **External services:** YouTube Data API, plus an LLM API for content classification (and later, generation)

## Roadmap

This is a class project, so scope is going to move around as we go. Rough direction:

1. **Now:** basic feed + YouTube integration, accounts/guest mode, interest selection
2. **Next:** interested/not-interested feedback loop actually affecting recommendations, creator downweighting
3. **Later:** LLM-generated short summaries and quiz-style questions for videos/topics in your feed
4. **Maybe:** TikTok/Instagram as additional content sources, if time allows

Nothing above is locked in — just where things stand right now.

## Status

Just getting started — setting up the backend and figuring out the data model.
