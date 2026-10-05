// jsdom (the fake browser the component tests run in) doesn't have IntersectionObserver, which
// the feed uses to notice scrolling. This stand-in records what's being watched, and tests call
// scrollInto() to pretend an element scrolled into or out of view.
import { act } from '@testing-library/react';
import { vi } from 'vitest';

type Callback = (entries: Partial<IntersectionObserverEntry>[]) => void;

class FakeObserver {
  targets: Element[] = [];

  constructor(public callback: Callback) {
    observers.push(this);
  }

  observe(target: Element) {
    this.targets.push(target);
  }

  unobserve() {}

  disconnect() {
    observers.splice(observers.indexOf(this), 1);
  }

  takeRecords() {
    return [];
  }
}

const observers: FakeObserver[] = [];

export function useFakeIntersectionObserver() {
  observers.length = 0;
  vi.stubGlobal('IntersectionObserver', FakeObserver);
}

/** Pretends `target` scrolled so that `ratio` of it is visible (0 = gone, 1 = fully visible). */
export function scrollInto(target: Element, ratio = 1) {
  act(() => {
    for (const observer of [...observers]) {
      if (observer.targets.includes(target)) {
        observer.callback([{ target, isIntersecting: ratio > 0, intersectionRatio: ratio }]);
      }
    }
  });
}
