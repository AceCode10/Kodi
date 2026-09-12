"""Behavioural evals for the Kodi agent.

Built to `shared/evals/build-eval.md` (bundled with the `claude-api` skill). The short
version of the design:

* The agent acts on a device, so the primary grader reads **end state** - what the
  FakeDevice was actually made to do - not the transcript. "Did it say the right
  things" is the weakest signal for an acting agent.
* Model responses are recorded once as cassettes and replayed thereafter, so a run is
  deterministic and free. Only a deterministic replay can answer "did the refactor
  change behaviour?", which is what these exist for.
"""
