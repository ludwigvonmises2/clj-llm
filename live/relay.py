"""In-process relay bus: one subscriber registry shared by the desk and
the virtual venue modules. A venue module never opens a venue WebSocket
— the ONE owner is feeds.py in the desk; venue engines consume the
in-process broadcast so venue rate limits are consumed once."""
import queue

_subs = []  # (venue-prefix, Queue)

def subscribe(prefix=""):
    """Queue of broadcast events whose venue starts with prefix."""
    q = queue.Queue(maxsize=10_000)
    _subs.append((prefix, q))
    return q

def publish(ev):
    venue = ev.get("venue", "")
    for prefix, q in _subs:
        if venue.startswith(prefix):
            try:
                q.put_nowait(ev)
            except queue.Full:
                pass
