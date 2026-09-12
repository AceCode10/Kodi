"""Run the eval suite.

    python -m evals.runner --replay              # deterministic, free; what CI runs
    python -m evals.runner --record              # record cassettes against the real API
    python -m evals.runner --live                # call the real API without recording
    python -m evals.runner --replay --case sms_running_late

Output follows the layout the bundled report builder expects:

    .claude/hillclimb/kodi-agent/baseline/
        _state.json          metric and perf-field declarations
        results.jsonl        one row per (case, rep) that produced a score
        errors.jsonl         one row per attempt that failed before scoring
        traces/<id>_rep<k>.json

Two properties worth knowing about:

* **`errors.jsonl` is a sidecar, never a `results.jsonl` row.** A harness failure written
  at the `(case, rep)` key would make resume skip it forever and would score plumbing as
  a model failure.
* **Cases run sequentially.** The harness patches module-level globals (`anthropic.Anthropic`
  and `agent_loop._execute_server_tool`), so running cases in threads would let them
  clobber each other. Replay of the whole suite takes seconds; live takes a few minutes,
  which is fine for a nightly. Phase 2's injected core removes the obstacle.
"""
from __future__ import annotations

import argparse
import json
import os
import math
import random
import sys
import time
from pathlib import Path
from typing import Any, Iterable

from .cassettes import (
    CASSETTE_DIR,
    CassetteMismatch,
    CassetteMissing,
    is_synthetic,
    recording,
    replaying,
)
from .graders import grade
from .harness import CaseResult, run_case

CASES_DIR = Path(__file__).parent / "cases"
OUT_DIR = Path(__file__).resolve().parents[2] / ".claude" / "hillclimb" / "kodi-agent" / "baseline"

# A case that takes this long has hung; reclaim the slot rather than stall the run.
CASE_TIMEOUT_S = 120.0
MAX_ATTEMPTS = 3

METRICS = [
    # Order matters: the report's headline is the first binary metric.
    {"id": "end_state", "label": "End state", "kind": "binary"},
    {"id": "safety", "label": "Safety", "kind": "binary"},
    {"id": "contact", "label": "Contact", "kind": "binary"},
    {"id": "phrasing", "label": "Phrasing", "kind": "binary"},
]
PERF_FIELDS = ["latency_s", "tool_calls", "usage"]


# -- case loading ------------------------------------------------------------------


def load_cases(directory: Path = CASES_DIR, only: str | None = None) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for path in sorted(directory.glob("*.yaml")) + sorted(directory.glob("*.yml")):
        cases.extend(_read_yaml(path))
    for path in sorted(directory.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        cases.extend(data if isinstance(data, list) else [data])
    if only:
        cases = [c for c in cases if c.get("id") == only]
    _check_ids(cases)
    return cases


def _read_yaml(path: Path) -> list[dict[str, Any]]:
    try:
        import yaml
    except ImportError:  # pragma: no cover - dev dependency
        raise SystemExit("PyYAML is needed to read .yaml cases: pip install -r requirements-dev.txt")
    loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
    if loaded is None:
        return []
    return loaded if isinstance(loaded, list) else [loaded]


def _check_ids(cases: Iterable[dict[str, Any]]) -> None:
    seen: set[str] = set()
    for case in cases:
        case_id = case.get("id")
        if not case_id:
            raise SystemExit(f"case without an id: {case}")
        if case_id in seen:
            raise SystemExit(f"duplicate case id {case_id!r}; ids key the cassettes and results")
        seen.add(case_id)


# -- results -----------------------------------------------------------------------


def completed_keys(out_dir: Path) -> set[tuple[str, int]]:
    """(case, rep) pairs already scored, so a resumed run skips exactly those."""
    path = out_dir / "results.jsonl"
    if not path.exists():
        return set()
    done: set[tuple[str, int]] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError:
            continue
        done.add((row.get("prompt_id", ""), int(row.get("rep", 0))))
    return done


def _append(path: Path, row: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, default=str) + "\n")


def write_state(out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "_state.json").write_text(
        json.dumps({"flow": "kodi-agent", "metrics": METRICS, "perf_fields": PERF_FIELDS}, indent=2),
        encoding="utf-8",
    )


def build_trace(case: dict[str, Any], result: CaseResult) -> list[dict[str, Any]]:
    """The conversation, in the shape the report's transcript view reads."""
    from app.config import KODI_SYSTEM_PROMPT

    turns: list[dict[str, Any]] = [{"role": "system", "content": KODI_SYSTEM_PROMPT}]
    turns.append({"role": "user", "content": result.transcript})
    for (name, args), (_n, content, is_error) in zip(result.tool_calls, result.tool_results):
        turns.append({"role": "tool_call", "name": name, "content": json.dumps(args, indent=2)})
        turns.append({"role": "tool_result", "content": ("[error] " if is_error else "") + content})
    turns.append({"role": "assistant", "content": result.reply})
    return turns


# -- running -----------------------------------------------------------------------


def run_one(case: dict[str, Any], mode: str) -> CaseResult:
    case_id = case["id"]
    if mode == "replay":
        with replaying(case_id):
            return run_case(case)
    if mode == "record":
        with recording(case_id):
            return run_case(case)
    return run_case(case)  # live: real client, nothing captured


def run_suite(
    cases: list[dict[str, Any]],
    *,
    mode: str,
    reps: int,
    out_dir: Path,
    resume: bool,
) -> dict[str, Any]:
    write_state(out_dir)
    results_path = out_dir / "results.jsonl"
    errors_path = out_dir / "errors.jsonl"
    traces_dir = out_dir / "traces"
    traces_dir.mkdir(parents=True, exist_ok=True)

    already = completed_keys(out_dir) if resume else set()
    if not resume and results_path.exists():
        results_path.unlink()

    scores: dict[str, list[float]] = {m["id"]: [] for m in METRICS}
    errors = 0

    for case in cases:
        for rep in range(reps):
            if (case["id"], rep) in already:
                continue

            started = time.monotonic()
            result, failure = _attempt(case, mode)
            latency = round(time.monotonic() - started, 3)

            if failure is not None:
                errors += 1
                _append(
                    errors_path,
                    {
                        "prompt_id": case["id"],
                        "rep": rep,
                        "failure_class": failure["klass"],
                        "detail": failure["detail"],
                        "attempts": failure["attempts"],
                    },
                )
                print(f"  ERROR {case['id']} rep{rep}: {failure['klass']}: {failure['detail'][:160]}")
                continue

            case_scores, notes = grade(case, result, allow_judge=(mode != "replay"))
            for metric, value in case_scores.items():
                if value is not None:
                    scores[metric].append(value)

            (traces_dir / f"{case['id']}_rep{rep}.json").write_text(
                json.dumps(build_trace(case, result), indent=2), encoding="utf-8"
            )
            _append(
                results_path,
                {
                    "prompt_id": case["id"],
                    "rep": rep,
                    "prompt": result.transcript,
                    "tags": list(case.get("tags") or ["uncategorised"]),
                    "status": "ok",
                    "stop_reason": "end_turn",
                    "grade": case_scores,
                    "explanation": notes,
                    "model": result.model(),
                    "usage": result.usage(),
                    "latency_s": latency,
                    "tool_calls": len(result.tool_calls),
                },
            )
            headline = case_scores.get("end_state")
            mark = "." if headline in (1.0, None) else "F"
            print(f"  {mark} {case['id']} rep{rep}" + (f"  {notes.get('end_state', '')}" if mark == "F" else ""))

    return _summarise(scores, errors, len(cases), reps)


def _attempt(case: dict[str, Any], mode: str) -> tuple[CaseResult | None, dict[str, Any] | None]:
    """Run one case with retries. Returns (result, failure)."""
    last = ""
    for attempt in range(1, MAX_ATTEMPTS + 1):
        started = time.monotonic()
        try:
            result = run_one(case, mode)
        except (CassetteMissing, CassetteMismatch) as exc:
            # Not retryable and not a model failure: the recording is the problem.
            return None, {"klass": "cassette", "detail": str(exc), "attempts": attempt}
        except Exception as exc:
            last = f"{type(exc).__name__}: {exc}"
            if _retryable(exc) and attempt < MAX_ATTEMPTS:
                time.sleep(min(2**attempt, 8) * (0.5 + random.random()))
                continue
            return None, {"klass": "harness", "detail": last, "attempts": attempt}

        if time.monotonic() - started > CASE_TIMEOUT_S:
            return None, {"klass": "timeout", "detail": f">{CASE_TIMEOUT_S}s", "attempts": attempt}
        if not result.ok:
            # The agent itself failed the turn: that is a scorable outcome, not a
            # harness error, so it goes to results.jsonl with a zero.
            return result, None
        return result, None
    return None, {"klass": "harness", "detail": last, "attempts": MAX_ATTEMPTS}


def _retryable(exc: Exception) -> bool:
    text = f"{type(exc).__name__} {exc}".lower()
    return any(s in text for s in ("rate", "429", "overload", "timeout", "connection", "5xx"))


def _summarise(scores: dict[str, list[float]], errors: int, n_cases: int, reps: int) -> dict[str, Any]:
    summary: dict[str, Any] = {"errors": errors, "cases": n_cases, "reps": reps, "metrics": {}}
    for metric in METRICS:
        values = scores[metric["id"]]
        if not values:
            summary["metrics"][metric["id"]] = None
            continue
        mean = sum(values) / len(values)
        half = 1.96 * math.sqrt(max(mean * (1 - mean), 1e-9) / len(values))
        summary["metrics"][metric["id"]] = {
            "mean": round(mean, 4),
            "ci95": round(half, 4),
            "n": len(values),
        }
    return summary


def print_summary(summary: dict[str, Any]) -> None:
    print("\n" + "=" * 62)
    for metric in METRICS:
        stats = summary["metrics"].get(metric["id"])
        if stats is None:
            print(f"{metric['label']:<12} —  (no case declares this metric)")
            continue
        print(
            f"{metric['label']:<12} {stats['mean'] * 100:5.1f}%  ±{stats['ci95'] * 100:4.1f}  "
            f"(n={stats['n']})"
        )
    if summary["errors"]:
        print(f"\n{summary['errors']} attempt(s) failed before scoring — see errors.jsonl")

    # The resolution check from eval-audit.md §5, printed where it will be read.
    n = summary["cases"] * summary["reps"]
    if n:
        floor = 1 / math.sqrt(n) * 100
        print(
            f"\nNoise floor at {summary['cases']} cases x {summary['reps']} reps: ~±{floor:.0f} points. "
            f"A change smaller than that is not visible to this suite."
        )


# -- cli ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the Kodi agent evals.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--replay", action="store_const", const="replay", dest="mode")
    mode.add_argument("--record", action="store_const", const="record", dest="mode")
    mode.add_argument("--live", action="store_const", const="live", dest="mode")
    parser.add_argument("--case", help="run a single case by id")
    parser.add_argument("--reps", type=int, default=1)
    parser.add_argument("--out", type=Path, default=OUT_DIR)
    parser.add_argument("--cases-dir", type=Path, default=CASES_DIR)
    parser.add_argument("--resume", action="store_true", help="skip (case, rep) pairs already scored")
    parser.add_argument("--fail-under", type=float, default=None, help="exit 1 if end_state is below this")
    args = parser.parse_args(argv)

    selected = args.mode or "replay"
    cases = load_cases(args.cases_dir, args.case)
    if not cases:
        print("no cases found", file=sys.stderr)
        return 1

    if selected == "replay":
        # Replay serves every model call from disk, so no key is required - but the agent
        # refuses to start without one. A placeholder keeps replay runnable offline.
        os.environ.setdefault("ANTHROPIC_API_KEY", "replay-placeholder")
        from app import config

        config.get_settings.cache_clear()

    print(f"{selected}: {len(cases)} case(s) x {args.reps} rep(s)")
    if selected == "replay":
        print(f"cassettes: {CASSETTE_DIR}")
        synthetic = [c["id"] for c in cases if is_synthetic(c["id"])]
        if synthetic:
            print(
                f"\n  !! {len(synthetic)} of {len(cases)} cassettes are SYNTHETIC - the model's\n"
                f"     decisions in them were hand-written, not recorded. A pass means the\n"
                f"     pipeline works, not that the agent is good. Re-record against the real\n"
                f"     API once the real cases land.\n"
            )

    summary = run_suite(
        cases, mode=selected, reps=args.reps, out_dir=args.out, resume=args.resume
    )
    print_summary(summary)

    if args.fail_under is not None:
        headline = summary["metrics"].get("end_state")
        if headline is None or headline["mean"] < args.fail_under:
            got = "n/a" if headline is None else f"{headline['mean']:.2f}"
            print(f"\nFAIL: end_state {got} < {args.fail_under}", file=sys.stderr)
            return 1
    return 1 if summary["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
