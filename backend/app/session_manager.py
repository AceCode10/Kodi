import time
from dataclasses import dataclass, field
from threading import Lock, RLock
from typing import Any
import uuid


SESSION_TTL_SECONDS = 3600  # 1 hour idle TTL


@dataclass
class SessionState:
    device_id: str
    claude_messages: list[dict[str, Any]] = field(default_factory=list)
    tools_used_this_command: int = 0
    last_transcript: str = ""
    pending_device_tool: dict[str, Any] | None = None
    pending_device_since: float | None = None
    created_at: float = field(default_factory=time.time)
    last_used: float = field(default_factory=time.time)
    lock: RLock = field(default_factory=RLock)


class SessionManager:
    def __init__(self) -> None:
        self._sessions: dict[str, SessionState] = {}
        self._lock = Lock()

    def create(self, device_id: str) -> str:
        sid = uuid.uuid4().hex
        with self._lock:
            self._evict_stale()
            self._sessions[sid] = SessionState(device_id=device_id)
        return sid

    def _evict_stale(self) -> None:
        """Remove sessions idle longer than SESSION_TTL_SECONDS. Call under _lock."""
        cutoff = time.time() - SESSION_TTL_SECONDS
        stale = [k for k, v in self._sessions.items() if v.last_used < cutoff]
        for k in stale:
            del self._sessions[k]

    def get(self, session_id: str) -> SessionState | None:
        with self._lock:
            s = self._sessions.get(session_id)
            if s is not None:
                s.last_used = time.time()
            return s

    def require(self, session_id: str) -> SessionState:
        s = self.get(session_id)
        if not s:
            raise KeyError("Unknown session")
        return s


sessions = SessionManager()
