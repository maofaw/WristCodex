"""Successful native turn transitions; caller serializes access with its state lock."""
from collections import deque


class CompletionNotifications:
    LIMIT = 64
    ACTIVE = frozenset(('starting', 'running', 'waiting'))

    def __init__(self, scope):
        self.reset(scope)

    def reset(self, scope):
        self.scope = scope
        self.cursor = 0
        self.events = deque(maxlen=self.LIMIT)
        self.armed = {}
        # Keep only identity tombstones (no body/request data) until scope reset.
        # Evicting these with the 64-event buffer would permit duplicate alerts.
        self.settled = set()

    def observe(self, states, available):
        if not available:
            return
        present = set()
        for state in states:
            thread, turn = state.get('thread_id'), state.get('turn_id')
            if not thread or not turn:
                continue
            present.add(thread)
            key = (thread, turn)
            status = state.get('state')
            if state.get('readonly', True) or status in ('failed', 'interrupted'):
                previous = self.armed.pop(thread, None)
                if previous:
                    self.settled.add((thread, previous))
                self.settled.add(key)
                continue
            if key in self.settled:
                continue
            if status in self.ACTIVE:
                self.armed[thread] = turn
            elif status == 'completed':
                if self.armed.get(thread) != turn:
                    self.settled.add(key)  # First-seen history never becomes new.
                elif (state.get('can_send') is True and not state.get('pending')
                        and not state.get('pending_count', 0)):
                    self.cursor += 1
                    self.events.append(dict(sequence=self.cursor, thread_id=thread,
                        turn_id=turn, title=str(state.get('title') or '')[:160]))
                    self.settled.add(key)
                    self.armed.pop(thread, None)
        for thread in set(self.armed) - present:
            self.settled.add((thread, self.armed.pop(thread)))

    def snapshot(self, available):
        return dict(available=bool(available), scope=self.scope, cursor=self.cursor,
            oldest=self.events[0]['sequence'] if self.events else self.cursor + 1,
            events=[dict(event) for event in self.events])
