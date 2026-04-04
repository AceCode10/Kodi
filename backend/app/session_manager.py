from dataclasses import dataclass, field
from threading import Lock
from typing import Any
import uuid


@dataclass
class SessionState:
    device_id: str
    claude_messages: list[dict[str, Any]] = field(default_factory=list)
    tools_used_this_command: int = 0
    last_transcript: str = ""
    pending_device_tool: dict[str, Any] | None = None


class SessionManager:
    def __init__(self) -> None:
        self._sessions: dict[str, SessionState] = {}
        self._lock = Lock()

    def create(self, device_id: str) -> str:
        sid = uuid.uuid4().hex
        with self._lock:
            self._sessions[sid] = SessionState(device_id=device_id)
        return sid

    def get(self, session_id: str) -> SessionState | None:
        with self._lock:
            return self._sessions.get(session_id)

    def require(self, session_id: str) -> SessionState:
        s = self.get(session_id)
        if not s:
            raise KeyError("Unknown session")
        return s


sessions = SessionManager()
