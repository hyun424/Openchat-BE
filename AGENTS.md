# Agent Rules for OpenChat

## Documentation Habit

Before significant work:

- Read `docs/OPENCHAT-BRANCH-DECISION-LOG.md`.
- Check related topic-specific docs under `docs/`.
- Identify where the task fits in the current project story.
- State whether the work is likely to require documentation updates.

After significant work:

- Decide whether docs need updates.
- Update docs when the work changes design, trade-offs, GCP smoke/load results, performance numbers, branch-level decisions, or meaningful failure analysis.
- If docs are not updated, say why in the final response.
- If docs are updated, say which docs changed in the final response.

## Append-Only Documentation Safety

- Do not delete, compress, or replace existing records unless the user explicitly asks.
- If older content is incomplete or outdated, add a new `Update`, `Correction`, `Follow-up`, or `Current Interpretation` section.
- Preserve old run ids, numbers, conclusions, and context even when later results supersede them.
- Ask for explicit approval before removing duplicate sections, rewriting history, or reorganizing large documentation areas.

## Primary Documentation Targets

- Decision log: `docs/OPENCHAT-BRANCH-DECISION-LOG.md`
- Resume/interview summary: `docs/OPENCHAT-EXPERIENCE-BANK.md`
- Detailed design/results: topic-specific docs under `docs/`
