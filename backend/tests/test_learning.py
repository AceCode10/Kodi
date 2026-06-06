"""Tests for correction detection and the SSE sentence buffer."""
from app.agent_loop import _drain_sentences
from app.learning import detect_correction


def test_detect_correction_positive():
    for s in (
        "No, I meant my sister",
        "that's wrong",
        "I said tomorrow",
        "cancel that",
        "you got it wrong",
        "wrong contact",
    ):
        assert detect_correction(s), s


def test_detect_correction_negative():
    for s in (
        "call my sister",
        "what's the weather today",
        "set a timer for ten minutes",
        "",
    ):
        assert not detect_correction(s), s


def test_drain_sentences_splits_complete():
    sentences, rest = _drain_sentences("Hello there. How are you? ")
    assert sentences == ["Hello there.", "How are you?"]
    assert rest == ""


def test_drain_sentences_keeps_partial():
    sentences, rest = _drain_sentences("Done. Next part")
    assert sentences == ["Done."]
    assert rest == "Next part"


def test_drain_sentences_no_terminator():
    sentences, rest = _drain_sentences("still typing")
    assert sentences == []
    assert rest == "still typing"
