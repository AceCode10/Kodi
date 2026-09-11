"""The forget commands delete user data before the model ever sees the utterance,
so the matcher must fire on a whole imperative command and nothing else."""
import pytest

from app.main import FORGET_ALL_RE, FORGET_LAST_RE


@pytest.mark.parametrize(
    "utterance",
    [
        "forget that",
        "Forget that.",
        "forget it",
        "please forget that",
        "ok forget that",
        "okay, forget the last one",
        "can you forget what I just said",
        "Kodi, forget the last thing",
        "actually forget that!",
    ],
)
def test_forget_last_matches_commands(utterance):
    assert FORGET_LAST_RE.match(utterance)


@pytest.mark.parametrize(
    "utterance",
    [
        # The regression this guard exists for: incidental use of the words.
        "I'll never forget that trip",
        "don't forget that I have a meeting",
        "remind me so I don't forget that",
        "I can't forget that song",
        "forget that guy, what's the weather",
        "did you forget that already",
        # Must not steal the forget-everything command.
        "forget everything",
        # Unrelated.
        "call my sister",
        "",
    ],
)
def test_forget_last_ignores_everything_else(utterance):
    assert not FORGET_LAST_RE.match(utterance)


@pytest.mark.parametrize(
    "utterance",
    [
        "forget everything",
        "Forget everything.",
        "forget everything about me",
        "please delete all my memories",
        "delete all memories",
        "erase my memories",
        "wipe my memory",
        "ok, delete all my memories",
    ],
)
def test_forget_all_matches_commands(utterance):
    assert FORGET_ALL_RE.match(utterance)


@pytest.mark.parametrize(
    "utterance",
    [
        "I want to forget everything about that meeting",
        "don't delete all my memories",
        # Ambiguous without naming memory - could mean a chat, a file, a list.
        "delete everything",
        "erase everything",
        "forget that",
        "what's the weather",
    ],
)
def test_forget_all_ignores_everything_else(utterance):
    assert not FORGET_ALL_RE.match(utterance)


def test_forget_all_requires_confirmation_then_deletes(monkeypatch):
    """First utterance arms a prompt; only the confirmation phrase deletes."""
    import app.main as main

    deleted: list[str] = []
    monkeypatch.setattr(main, "memory_forget_all", lambda uid: deleted.append(uid) or "cleared")
    monkeypatch.setattr(main, "memory_forget_last", lambda uid: "last gone")
    main._forget_all_pending_until.clear()

    first = main._forget_preprocess("forget everything", "dev1")
    assert "three minutes" in first
    assert deleted == []

    again = main._forget_preprocess("forget everything", "dev1")
    assert "confirm delete everything" in again
    assert deleted == []

    done = main._forget_preprocess("confirm delete everything", "dev1")
    assert done == "cleared"
    assert deleted == ["dev1"]


def test_confirmation_without_a_pending_prompt_does_nothing(monkeypatch):
    import app.main as main

    monkeypatch.setattr(main, "memory_forget_all", lambda uid: "cleared")
    main._forget_all_pending_until.clear()
    assert main._forget_preprocess("confirm delete everything", "dev2") is None


def test_incidental_phrase_does_not_delete(monkeypatch):
    import app.main as main

    calls: list[str] = []
    monkeypatch.setattr(main, "memory_forget_last", lambda uid: calls.append(uid) or "gone")
    main._forget_all_pending_until.clear()

    assert main._forget_preprocess("I'll never forget that trip to Livingstone", "dev3") is None
    assert calls == []
